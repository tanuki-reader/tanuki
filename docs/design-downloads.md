# Design — Chapter downloads (offline reading)

Status: **phases 0–2 built** (the storage spine and the engine; nothing user-visible yet — see §12, §13 and §14).
Read alongside `decisions.md`, `design-page-image-abi.md` and `session-source-and-browse.md`.

Goal: the user can keep chapters on the device and read them with no network, with a queue they can
see and control, and the storage they cost being visible and reclaimable.

---

## 0. What already exists (the feature was anticipated)

| Hook already in the code | Where |
|---|---|
| `Manga.isDownloaded` — hardcoded `false` at the mapper | `core/data/…/manga/MangaEntity.kt:104` |
| Downloaded badge on the cover tile | `assets/designSystem/…/MangaCover.kt` (`downloaded` param) |
| `DownloadIcon` | `assets/designSystem/…/TanukiIcons.kt:554` |
| `TriState` — written for "must be downloaded / must not / don't care" | `core/domain/…/common/model/TriState.kt` |
| `LibraryFilter` — TODO naming the download subsystem as the blocker | `core/domain/…/library/model/LibraryFilter.kt:13` |
| Side-table-on-`chapters` pattern to copy | `chapter_updates` (`ChapterUpdateEntity`) |
| Background-work seam to copy | `LibraryUpdateScheduler` + WorkManager / `BGTaskScheduler` actuals |
| Bulk-run engine to copy | `UpdateLibrary` (mutex, per-source concurrency, failure isolation) |
| Shared CF-interceptor HTTP client | `AppGraph.sourceHttpClient` |

So this is mostly *filling in* a shape the codebase already reserved, not adding a new axis.

---

## 1. Architecture map

Dependencies point down only; nothing here breaks the `sharedLogic`-never-imports-Compose rule.

```
:core:domain      download/model/       ChapterDownload, DownloadState, DownloadError, DownloadRequest
                  download/repository/  DownloadRepository          (DB index — interface)
                  download/service/     ChapterDownloadStore        (files — port)
                                        PageFetcher                 (bytes — port, impl in :core:source)
                                        DownloadScheduler           (platform bg — port, impl in :shared)
                                        DownloadNotifier            (platform notif — port, impl in :shared)
                  download/usecase/     Enqueue/Cancel/Delete/Observe/Verify… + DownloadManager
:core:data        repository/download/  ChapterDownloadEntity, ChapterDownloadDao, DownloadRepositoryImp
                  store/                FileSystemChapterDownloadStore (okio)   ← or keep in :core:data
:core:source      net/                  KtorPageFetcher (uses the shared CF/UA client)
:shared           download/             WorkManager worker + FGS (android), BGProcessingTask (ios),
                                        notifiers, DI bindings
:features:manga   chapter row states, bulk actions, "download next N" menu
:features:downloads  NEW module — the queue screen
:features:settings   SettingsDownloads screen
:features:library    downloaded badge + `downloaded` filter
:features:reader     reads local pages (via GetChapterPages, no reader rewrite)
```

`DownloadManager` lives in `:core:domain` even though it is an *engine*: it is pure orchestration
over ports (`PageFetcher`, `ChapterDownloadStore`, `DownloadRepository`), exactly like `UpdateLibrary`
orchestrates `SyncSourceChapters`. The HTTP is in `:core:source` behind `PageFetcher`, so the domain
never sees Ktor.

**A new `:features:downloads` module** rather than a screen inside `:features:manga`: the queue is not
a manga-scoped surface (it spans series), and a feature→feature dependency is forbidden.

---

## 2. On-disk layout

```
<downloads root>/
  <mangaId>/                       ← local Uuid, not the title
    <chapterId>/
      manifest.json                ← written last; its presence == "this chapter is complete"
      000.jpg 001.jpg 002.png …    ← zero-padded, extension from the response content-type
    <chapterId>.part/              ← in-progress; pages land here, resumable
```

- **Uuid directories, not titles.** Titles need sanitizing (`/`, `:`, emoji, RTL), collide across
  sources, and change when a source edits its metadata — each of which silently orphans a folder.
  Cost: the tree is not human-browsable. Mihon chooses the opposite because its users copy folders to
  a PC; if we want that later it is an *export* feature (a zip/CBZ writer over this layout), not a
  reason to make the live layout fragile.
- **Completion is `manifest.json` existing**, and it is written by `atomicMove(<id>.part → <id>)` after
  every page is on disk. A crash mid-chapter can therefore never leave a half-chapter that reads as
  downloaded. The `.part` dir survives for resume; pages already in it are skipped on the retry.
- `manifest.json`: `{ version, chapterId, sourceId, pageCount, pages:[{index, file, bytes}], totalBytes, downloadedAt }`.
  It is what the offline read path trusts, so page order never depends on filename sorting or on
  re-asking the source.

### Where the root goes (platform)

| | Path | Why |
|---|---|---|
| Android | `context.filesDir/downloads` | **Not** `cacheDir` — the OS reclaims that, and a download the user asked for is not a cache. Mirrors the `.wasm` bundle store (`PlatformSourceBindings.android.kt`). |
| iOS | `<Application Support>/downloads`, **`isExcludedFromBackup = true`** | Same location as bundles. The exclusion flag is mandatory: without it a 4 GB library goes into iCloud backup and Apple's review/user complaints follow. |

Both are provided the way `SourceBundleStore` already is — a `@Provides` in the platform binding
container, with the store itself taking `FileSystem` + `Path` so it is testable on `FakeFileSystem`.

---

## 3. Database (schema v10)

A side table on `chapters`, exactly like `chapter_updates` — the chapter row is source-owned and
rewritten on every sync, so anything the app decides about a chapter lives beside it.

```kotlin
@Entity(
    tableName = "chapter_downloads",
    foreignKeys = [ForeignKey(ChapterEntity::class, ["id"], ["chapter_id"], onDelete = CASCADE)],
    indices = [Index("manga_id"), Index("state"), Index("queued_at")],
)
data class ChapterDownloadEntity(
    @PrimaryKey @ColumnInfo("chapter_id") val chapterId: Uuid,
    @ColumnInfo("manga_id")        val mangaId: Uuid,      // denormalized: per-series counts + bulk delete without a join
    val state: DownloadState,                              // QUEUED DOWNLOADING DOWNLOADED FAILED
    @ColumnInfo("page_count")      val pageCount: Int?,    // null until the page list is fetched
    @ColumnInfo("downloaded_pages")val downloadedPages: Int,
    val bytes: Long,
    @ColumnInfo("error")           val error: String?,     // DownloadError name; message is localized in the UI
    @ColumnInfo("queued_at")       val queuedAt: Instant,  // the queue's order
    @ColumnInfo("completed_at")    val completedAt: Instant?,
)
```

- **`PAUSED` is not a row state.** Pausing is a property of the *queue*, not of a chapter (a paused
  chapter that resumes must keep its place), so it is one boolean in `SettingsRepository`
  (`downloadsPaused`) and rows stay `QUEUED`. This avoids the classic bug where "resume all" has to
  rewrite N rows and loses ordering.
- `Migration9To10` — plain `CREATE TABLE` + three indexes, no data rewrite. Also update the library
  read query to project `downloaded_count` next to the existing `unread_count`, which is what finally
  makes `Manga.isDownloaded` (`MangaEntity.kt:104`) real.
- **DB is an index over the disk, and they can drift** (user clears app storage, a manual delete, a
  crashed write). `VerifyDownloads` at app start reconciles: rows whose directory or manifest is gone
  are deleted; directories with no row (chapter pruned by a sync, manga removed) are deleted from
  disk. It is a cheap directory listing, not a per-file stat.

---

## 4. Domain surface

```kotlin
enum class DownloadState { Queued, Downloading, Downloaded, Failed }

sealed interface DownloadError {                 // never a display string — the UI localizes it
    data object Network : DownloadError
    data object SourceUnavailable : DownloadError // source missing/uninstalled, or its page list failed
    data object NeedsVerification : DownloadError // Cloudflare challenge, and no UI to solve it in
    data object OutOfSpace : DownloadError
    data object Unknown : DownloadError
}

data class ChapterDownload(
    val chapterId: Uuid, val mangaId: Uuid, val state: DownloadState,
    val downloadedPages: Int, val pageCount: Int?, val bytes: Long,
    val error: DownloadError?, val queuedAt: Instant,
) { val progress: Float? get() = pageCount?.takeIf { it > 0 }?.let { downloadedPages.toFloat() / it } }
```

Ports:

```kotlin
interface ChapterDownloadStore {                       // files; okio impl, FakeFileSystem in tests
    suspend fun manifest(chapterId: Uuid): DownloadManifest?
    suspend fun openPart(mangaId: Uuid, chapterId: Uuid): PartHandle   // resumable; knows what it already has
    suspend fun commit(part: PartHandle, manifest: DownloadManifest)   // atomic move, manifest last
    suspend fun delete(chapterId: Uuid); suspend fun deleteManga(mangaId: Uuid)
    suspend fun usedBytes(): Long
    suspend fun orphans(known: Set<Uuid>): List<Uuid>                  // for VerifyDownloads
}

interface PageFetcher {                                // :core:source — the shared CF/UA-carrying client
    suspend fun fetch(page: Page): FetchedPage          // bytes + content-type; honours Page.headers
}

interface DownloadScheduler { fun ensureRunning(); fun stop() }   // WorkManager / BGProcessingTask
interface DownloadNotifier {                                       // progress + terminal states
    suspend fun onProgress(active: ChapterDownload, remaining: Int)
    suspend fun onFinished(completed: Int, failed: Int)
    suspend fun clear()
}
```

Use cases (each its own file, `sealed interface` + `@Inject class Default`, per house style):

| Use case | Notes |
|---|---|
| `EnqueueChapterDownloads(chapterIds)` | Dedupes against existing rows; drops already-`Downloaded`; stamps `queuedAt`; calls `DownloadScheduler.ensureRunning()`. |
| `EnqueueNextChapters(mangaId, count)` | Backs "Download next 5 / next 10 / all unread / all". Uses the same scanlator filter the chapter list uses (`isHiddenBy`) so it can't queue a chapter the user can't see. |
| `CancelChapterDownloads(ids)` / `CancelAll` | Cancels the in-flight job if it is one of them; deletes rows; deletes `.part`. |
| `DeleteChapterDownloads(ids)` / `DeleteMangaDownloads(mangaId)` / `DeleteAllDownloads` | Disk **and** rows. FK cascade only takes the rows. |
| `SetDownloadsPaused(paused)` | The single queue-wide switch. |
| `ObserveDownloadQueue()` | For the queue screen: rows joined to chapter+manga titles, ordered by `queuedAt`. |
| `ObserveChapterDownloads(mangaId)` | `Map<Uuid, ChapterDownload>` for the chapter list badges. |
| `ObserveDownloadedCounts()` | Feeds the library badge + `downloaded` filter. |
| `GetLocalChapterPages(chapterId)` | Manifest → `List<PageImage.Local>`; `null` when not fully downloaded. |
| `AutoDownloadNewChapters(newChapters)` | Called by `UpdateLibrary`/`SyncSourceChapters` when the setting is on. |
| `DeleteReadChapterDownloads(chapterIds)` | Called from `SetChaptersRead` / `UpdateReadingProgress` when "delete after reading" is on. |
| `ObserveDownloadStorageUsage()` | Settings: bytes on disk, per-series breakdown. |
| `VerifyDownloads()` | Startup reconciliation (§3). |

---

## 5. The engine — `DownloadManager`

`@SingleIn(AppScope::class)`, one long-lived loop, deliberately shaped like `UpdateLibrary`:

- **One run at a time.** A `Mutex.tryLock()`; a second `ensureRunning()` is a no-op, not a second loop.
- **Sequential per source, a few sources at once** (`MAX_CONCURRENT_SOURCES = 3`, same reasoning as
  `UpdateLibrary`: bursting one host is what earns a rate-limit or a Cloudflare challenge). **Inside a
  chapter, 2–3 pages in parallel** — enough to hide latency, not enough to look like a scraper.
- **The page list is fetched at download time, per chapter, immediately before its pages** — never
  reused from the reader or from an earlier queue pass. MangaDex `@Home` URLs expire in minutes; a
  50-chapter queue that resolved everything up front would 403 halfway through.
- **Failure isolation is per chapter.** Per page: 3 attempts with exponential backoff. A page that
  still fails fails its chapter (`Failed` + error), the `.part` stays for resume, and the queue moves
  on. `CancellationException` always rethrown (the app is being torn down), matching `UpdateLibrary`.
- **Cloudflare during a queue** is handled in three tiers (see §5.1). While the app is on screen, a
  challenge is solved *silently* and the user never sees it. With no window at all there is nothing to
  paint in, so the chapter goes `Failed(NeedsVerification)` and the queue screen offers a "verify to
  continue" row. **The silent tier is a prerequisite, not a nicety** — without it a queue hijacks the
  screen mid-run.
- **Warm the clearance before a queue starts.** While the app is foreground, run a silent solve for
  each host the queue will touch. `cf_clearance` TTL is set by the site (commonly ~30 min), so this
  covers a burst, not an hour-long queue.
- **Out of space**: check free bytes before a chapter; on a write failure, pause the whole queue and
  surface it once, rather than failing 200 chapters one by one.
- Progress is written to the DB **per page** (cheap, one row update) so the UI, a process death, and a
  resume all agree without any in-memory queue state being authoritative.

### 5.1 Silent-first Cloudflare solving

The 2026-07-30 headless solvers failed because their WebView was **zero-size and never attached to a
window**, so it never rasterized and Turnstile never ran. The property that mattered was *painting*,
not *being seen by the user* — occlusion happens in the native compositor and is invisible to the
page's JS. `SiteSessionHost` already relies on this: it draws `ConnectingCover` over a live,
still-solving WebView (`SiteSessionHost.kt:241`).

So the silent tier is a **reveal policy**, not a new solver:

| Tier | Runs | Ends on |
|---|---|---|
| 0 | No browser — replay a stored clearance; `isCloudflareChallenge()` keeps non-challenges out | built |
| 1 — silent | The same layer and the same WebView, full-size and painting, z-ordered *behind* the app UI, no chrome, no input capture | `isSatisfiedBy` (built) |
| 2 — reveal | Budget expired and `snapshot.onChallengePage` still true → raise z-order, animate chrome in | today's UX |

- `SiteSessionLayer` holds `revealed`, initialised `true` for `SiteSessionReason.LOGIN` (nothing to
  solve on the user's behalf) and `false` for a challenge. Silent = `Modifier.zIndex(-1f)`, no chrome,
  no `pointerInput` swallower, invisible to a11y. The app stays usable throughout.
- **Budget comes from `_cf_chl_opt.cType`**, returned by the probe that already polls every 400ms
  (extend `ON_CHALLENGE_PAGE_JS`, add a field to `SiteSessionResult`): `interactive` reveals at once,
  `managed` gets ~8s then reveals (it can escalate to a checkbox — MangaFire's case),
  `non-interactive`/`jschal` gets ~12s. Never below ~6s: Cloudflare's JS challenge has a deliberate
  ~5s delay, so a tight budget reveals challenges that were about to clear.
- **Reveal must not remount the WebView.** `webView` is `remember(context, url)`, so flipping z-order
  and chrome in place is safe; moving the composable to a different parent creates a new engine and
  restarts the challenge from zero.
- **Verify on iOS first.** Placing the WebView behind Compose content assumes `UIKitView` composites
  in-order rather than always-on-top. `ConnectingCover` suggests it does, but it draws over the
  interop view inside the same layer — a device spike before committing.

---

## 6. Reader integration — offline reading with no reader rewrite

`Page.imageUrl` cannot hold a local path: `Url` requires a scheme *and host*, so `file:///…` fails
validation by construction. Introduce a small domain type instead:

```kotlin
sealed interface PageImage {
    data class Remote(val url: Url, val headers: Map<String, String>) : PageImage
    data class Local(val path: String) : PageImage       // opaque; okio Path is built in the UI layer
}
data class ReaderPage(val index: UInt, val image: PageImage)
```

`GetChapterPages` becomes download-aware and stays the *only* place that decides:

```
GetChapterPages(chapterId)
  ├─ GetLocalChapterPages(chapterId) != null → List<ReaderPage> of PageImage.Local   (works offline)
  └─ else → resolve source → sourceLoader.fetchChapterPages(…) → PageImage.Remote
```

UI ripple is confined to two files: `ToImageRequest.kt` maps `Local` → `.data(path.toPath())` (Coil 3
fetches `okio.Path` natively) and `Remote` → today's URL + headers; `ReaderState.PageItem` carries
`ReaderPage` instead of `Page`. Decode sizing, prefetch, and the sliding chapter window are untouched
— and prefetch/render still share one request builder, which is what keeps them on one cache entry
(see the KDoc there).

Consequence worth stating: a downloaded chapter never touches Coil's page **disk cache**, so a
download does not double the bytes on disk.

---

## 7. Platform execution

| | Android | iOS |
|---|---|---|
| Runner | `DownloadWorker` (`CoroutineWorker`, unique work, `ExistingWorkPolicy.KEEP`) via the existing `TanukiWorkerFactory` | Foreground while the app is open; `UIApplication.beginBackgroundTask` for a grace period; `BGProcessingTask` (`requiresNetworkConnectivity`) to continue later |
| Long queues | `setForeground()` + FGS type **`dataSync`** (manifest + `FOREGROUND_SERVICE_DATA_SYNC` permission; Android 14+ requires the type) | **No equivalent.** iOS will not run a Ktor download loop in the background for minutes |
| Constraints | `NetworkType.CONNECTED` (or `UNMETERED` when "Wi‑Fi only" is on), `requiresStorageNotLow` | Checked in-process before each chapter |
| Notifications | Progress notification (chapter, x/y, pause/cancel actions) + a completion/failure one, through `DownloadNotifier` | Completion/failure local notification only — same interface, thinner implementation |

**Decision to take explicitly:** on iOS, v1 downloads run *while the app is in the foreground*, with a
background grace period and a `BGProcessingTask` continuation. Truly out-of-process downloading needs
`URLSession(background:)`, which is per-request and out-of-process and therefore cannot reuse the
Ktor client — which would mean the Cloudflare interceptor, the cookie jar, and the host-owned
User-Agent are all bypassed for downloads. That trade is not worth it now; revisit if users report
queues stalling. Say so in the UI (the queue screen tells the user to keep the app open on iOS).

---

## 8. UI surfaces

Every string below is new copy in both `values/` and `values-pt-rBR/` of the owning feature module.
Visuals come from the **remote design project** (`claude.ai/design/p/425e7085-…`) — the download
affordance, the progress ring, and the queue row must be pulled from `components/` / `ui_kits/`
before anything is drawn. The one component that provably does not exist yet in
`assets/designSystem` is a **determinate progress ring** for the chapter row; add it to the design
project first, then to `assets/designSystem/component/`.

### 8.1 Manga screen (`:features:manga`)
- **Chapter row** gains a trailing download affordance with four states: idle (`DownloadIcon`),
  queued (dimmed), downloading (progress ring, tap = cancel), downloaded (filled check, tap = menu
  with *Delete download*), failed (error glyph, tap = retry). State comes from
  `ObserveChapterDownloads(mangaId)` folded into `MangaState.Content` as
  `downloads: Map<Uuid, ChapterDownload>` — a map lookup per row, no per-row flow.
- **Selection bar** gains *Download* / *Delete download*, with `canDownload` / `canDeleteDownload`
  computed once in the ViewModel next to the existing `canMarkRead` flags (same rationale: each is a
  scan over a list that runs to thousands of rows).
- **Bottom bar overflow**: *Download → next 1 / next 5 / next 10 / all unread / all*, and *Delete all
  downloaded chapters* (destructive, confirm first).

### 8.2 Downloads queue — new `:features:downloads` + `Destination.Downloads`
Grouped by series: header row (cover, title, "3 of 12"), chapter rows with progress and a cancel
action. Top bar: pause/resume all, cancel all. Empty state via `TanukiEmptyState`. Failed rows show a
localized reason and a retry; `NeedsVerification` rows open the visible Cloudflare browser.
Entry points: a row on the Settings index, plus the manga screen's overflow when something is queued.
(Not a bottom-bar tab — the tab set stays Library/Home/Activity/Settings.)

### 8.3 Settings → Downloads — `Destination.SettingsDownloads`
- Auto-download new chapters: Off / All library / Selected categories (multi-select over `Category`).
- Only on Wi‑Fi.
- Delete after reading (Off / after reading / keep last N read chapters).
- Storage used, with a per-series breakdown and *Delete all downloads* (confirm).
- (Later) user-chosen download location — Android SAF, iOS Files. Explicitly out of scope for v1.

New `SettingsRepository` keys (DataStore, per `datastore-preferences-pattern`): `downloadsPaused`,
`autoDownloadMode`, `autoDownloadCategories`, `downloadOnWifiOnly`, `deleteAfterRead`.

### 8.4 Library (`:features:library`)
- Wire the existing `downloaded` badge on `MangaCover` to the real `Manga.isDownloaded`.
- Add `downloaded: TriState` to `LibraryFilter` (+ `isActive`, + the bottom-sheet control) — the type
  was written for this case, and the "must NOT be downloaded" arm is what makes a cleanup pass easy.

### 8.5 Reader (`:features:reader`)
No new screen. Optionally a *Download this chapter* action in the chrome overflow, and an offline pill
when the visible chapter is served from disk. Both are cheap once §6 lands.

---

## 9. Lifecycle & maintenance (the parts that leak if forgotten)

- **Remove from library / delete manga** → `DeleteMangaDownloads` must run; the FK cascade only takes
  the rows, never the files. Hook `RemoveMangaFromLibrary` / `DeleteManga`.
- **A sync prunes chapters** → their rows cascade away, their directories do not. Covered by
  `VerifyDownloads`' orphan sweep, which is the reason it exists.
- **Uninstalling a source** → offer to delete its downloads; do not do it silently (the user may
  reinstall).
- **Downloads are user data, not cache** — never evicted by an LRU budget, never placed in a
  system-reclaimable directory, and excluded from iCloud backup.

---

## 10. Testing

| Target | How |
|---|---|
| `FileSystemChapterDownloadStore` | okio `FakeFileSystem` (already a dependency): commit atomicity, resume from `.part`, orphan detection, `usedBytes`. |
| `DownloadManager` | Fake `PageFetcher` + fake store: dedupe, ordering by `queuedAt`, per-page retry/backoff, per-chapter failure isolation, cancel mid-chapter, pause/resume, `NeedsVerification` short-circuit, "page list re-fetched per chapter". |
| `GetChapterPages` | Prefers local when the manifest is complete; falls back to the source when it is missing or short; existing tests stay green. |
| Use cases | `EnqueueNextChapters` honours the scanlator filter; `DeleteReadChapterDownloads` fires only when the setting is on. |
| Migration | `9.json → 10.json` schema export + a migration test. |
| ViewModels | `MangaViewModel` selection flags; `DownloadsViewModel` grouping/ordering. |

Commands unchanged: `./gradlew :core:domain:testAndroidHostTest :core:data:testAndroidHostTest`,
`:core:domain:iosSimulatorArm64Test` (see `gradle-build-verify-gotchas` — no `-q`, no piping through
`tail`).

---

## 11. Open decisions to settle before coding

1. **Silent-first Cloudflare solving** (§5.1) — blocking prerequisite, and worth doing on its own
   merits: it removes the mid-browse screen hijack for every non-interactive challenge, downloads or
   not. Needs the iOS interop-layering spike before it is committed to.
2. **iOS background policy** — foreground + grace + `BGProcessingTask` (recommended) vs. a background
   `URLSession` that bypasses the interceptor (not recommended) (§7).
3. **Uuid directories vs. human-readable** (§2) — recommend Uuid, with CBZ export as the later answer
   to portability.
4. **`:features:downloads` as its own module** vs. a screen in `:features:manga` — recommend its own
   module (a feature→feature dependency is otherwise unavoidable).
5. Whether auto-download is per-category from day one or a simple all/off toggle (recommend per
   category; `Category` already exists and retrofitting the setting is a migration).

---

## 12. Delivery order

Each phase is independently mergeable and leaves both platforms green.

| Phase | Contents | Visible outcome |
|---|---|---|
| **0** ✅ | `decisions.md` entries for §11 (2026-09-05) | — |
| **1** ✅ | Schema v10 + entity/DAO/repository, `ChapterDownloadStore` + okio impl, domain models, platform path bindings, tests | Nothing user-visible; storage layer proven on `FakeFileSystem` |
| **2** ✅ | `PageFetcher` in `:core:source`, `DownloadManager`, enqueue/cancel/delete use cases, tests | A queued chapter is fetched and committed; nothing queues one yet (phase 4) |
| **3** | `PageImage` + download-aware `GetChapterPages` + `ToImageRequest`/`ReaderState` ripple | **Downloaded chapters read in airplane mode** |
| **4** | Manga screen: row states, bulk actions, "download next N" | The feature becomes usable |
| **5** | `:features:downloads` queue screen + `Destination.Downloads` + Settings entry | The queue is visible and controllable |
| **6** | Android FGS worker + progress notification; iOS background task + completion notification | Queues survive leaving the screen |
| **7** | Settings → Downloads (auto-download, Wi‑Fi only, delete-after-read, storage usage); library badge + `downloaded` filter | Automation + housekeeping |
| **8** | `VerifyDownloads`, cascade hooks, source-uninstall prompt | No orphaned bytes |

Phases 1–3 are the spine: after phase 3 the app genuinely reads offline, and everything after is
control surface. Phase 5 depends on phase 2's `NeedsVerification` path existing, and phase 6 on
phase 5 (there is no point notifying about a queue the user cannot open).

---

## 13. As built — phase 1 (2026-09-05)

The storage spine, with no engine on top of it yet: nothing writes a row or a page, so the app behaves
exactly as it did before. What landed:

| Where | What |
|---|---|
| `:core:domain` `download/model/` | `DownloadState`, `DownloadError`, `ChapterDownload`, `DownloadManifest` + `DownloadedPage` |
| `:core:domain` `download/repository/` | `DownloadRepository` |
| `:core:domain` `download/service/` | `ChapterDownloadStore`, `ChapterDownloadPart`, `StoredChapter` |
| `:core:data` `repository/download/` | `ChapterDownloadEntity` + mappers, `ChapterDownloadDao`, `DownloadRepositoryImp` |
| `:core:data` `store/` | `FileSystemChapterDownloadStore` (okio), `DownloadManifestDto`, `DownloadDirectoryFactory` (expect/actual) + DI |
| `:core:data` `database/` | schema **v10**, `Migration9To10`, `DownloadState`/`DownloadError` converters, DAO binding |
| `androidApp` | `backup_rules.xml` / `data_extraction_rules.xml` excluding `files/downloads` and `files/sources` |

16 tests over `FakeFileSystem` (`FileSystemChapterDownloadStoreTest`), covering commit atomicity, resume
from a `.part`, refusal to commit a short or gappy chapter, re-commit, an unparsable manifest, the
delete paths, `usedBytes`, and what `storedChapters` does and does not report.

### Where the code differs from §3–§4 above, and why

- **`DownloadError` is an `enum`, not a sealed interface.** No arm carries a payload, and the `name` is
  what gets persisted — which is exactly an enum, and lets it reuse the by-name `Converters` pattern.
- **The store is addressed by `(mangaId, chapterId)`, not by chapter alone.** The layout is
  `<mangaId>/<chapterId>`, so a chapter-only API would have to scan the whole root to find one
  directory. Every caller has the series id anyway — it is denormalized onto the row for this reason.
- **`storedChapters(): List<StoredChapter>` replaces `orphans(known: Set<Uuid>)`.** The store reports
  what is on disk; deciding what is an orphan stays in the domain (see the `decisions.md` entry). It
  also reports leftover `.part` directories, which the original signature had no way to express.
- **`commit(part, expectedPageCount)` builds the manifest from the directory** rather than accepting one
  from the caller, and **refuses a short or non-contiguous chapter**. Committing a short chapter would
  mark it readable offline and then fail in the reader — the one failure the `.part` split exists to
  prevent — and the last point where the truth is still on disk is the right place to check it.
- **`pagePaths(mangaId, chapterId)`** is the phase-3 read path's whole surface, so no caller outside the
  store has to know how a chapter is laid out or how paths are joined.
- **`manifest.json` carries no `sourceId`, and no separate `pageCount`** (it is `pages.size`). Its file
  format is `DownloadManifestDto` in `:core:data` — a type separate from the domain model on purpose:
  it is written today and read by a build shipped months later, so its field names are a compatibility
  promise and must not move when the domain model is refactored. Every field is a primitive (`Uuid` as
  its canonical string, the timestamp as epoch millis) so the format never depends on which serializers
  a given kotlinx.serialization version ships for Kotlin's own value types.
- **`DownloadRepository.requeueInterrupted()`** was added: `Downloading` only ever describes a running
  engine, so a row still in it at startup was left by a process that died, and without this it would sit
  in the queue screen looking active while nothing fetched it.
- **Android backup exclusion was not in the design and had to be.** See the `decisions.md` entry — Auto
  Backup's 25 MB quota is per-app and all-or-nothing.

### Still open going into phase 2

- `Manga.isDownloaded` is **still hardcoded `false`** (`MangaEntity.kt`). The table exists now, but
  projecting a `downloaded_count` next to `unread_count` would be dead code until something writes rows;
  it lands with phase 4/7, when there is a count to show.
- Free-space checking (`DownloadError.OutOfSpace`) has no implementation yet — it belongs with the
  engine, which is what decides to stop the queue rather than fail chapters one by one.
- The silent-first Cloudflare tier (§5.1) is implemented but **still unverified on an iOS device**: that
  `UIKitView` composites *under* Compose content is assumed, not measured. It gates phase 2's promise
  that a queue never hijacks the screen.

---

## 14. As built — phase 2 (2026-09-06)

The engine, on top of phase 1's storage spine. A chapter that reaches the queue is now fetched,
committed and marked downloaded, and everything the UI will bind to exists — but nothing in the app
enqueues anything yet, so the behaviour is still unchanged for the user. What landed:

| Where | What |
|---|---|
| `:core:domain` `download/service/` | `PageFetcher` + `FetchedPage` + `PageFetchException`, `DownloadScheduler` |
| `:core:domain` `download/usecase/` | `DownloadManager` + `DownloadRunResult`, `EnqueueChapterDownloads`, `EnqueueNextChapters`, `CancelChapterDownloads`, `DeleteChapterDownloads`, `DeleteMangaDownloads`, `DeleteAllDownloads` |
| `:core:source` `net/` | `KtorPageFetcher` over the shared CF/UA-carrying client |
| `:shared` `download/` | `ForegroundDownloadScheduler`; `AppGraph.downloadScheduler` + the launch-time resume in `App` |

41 tests: 16 on the engine (`DownloadManagerTest`), 14 on the actions (`DownloadUseCasesTest`), 11 on
the fetcher's classification (`KtorPageFetcherTest`, over Ktor's `MockEngine`). The engine tests run the
**real** `GetChapterPages` over fakes rather than a double — a use case is a sealed interface and cannot
be implemented from a test compilation, and it also proves the downloader resolves a chapter exactly the
way the reader does.

### Where the code differs from §4–§5 above, and why

- **One chapter at a time, in queue order** — not `MAX_CONCURRENT_SOURCES = 3`. A queue is something the
  user *watches*, and they expect the first chapter to finish first; three at once also triples the page
  bytes held in memory and makes "downloading 2 of 12" untrue. Parallelism moved inside the chapter
  (`MAX_CONCURRENT_PAGES = 3`), which is where it hides latency without bursting a host. `nextQueued()`,
  which phase 1 already committed to, is exactly this shape.
- **Pages are addressed by their position in the source's list, not by `Page.index`.** The list is
  already in reading order and the manifest requires contiguous indices from `0`, so a source that
  numbers from `1` or leaves a gap would otherwise produce a chapter that can never commit. A part whose
  held pages no longer fit the current list (the source's page list shrank) is discarded and **reopened**
  rather than reused — a discarded part is gone from disk, and so is the handle to it.
- **`DownloadScheduler` has no `stop()`.** A run ends when the queue is empty, so the way to end one
  early is to take its work away — `DownloadManager.abort` plus deleting the rows, which is what
  cancelling and deleting already do. A `stop()` would add a window in which a stopped engine still had
  rows waiting for it.
- **`abort` is a flag, not a coroutine cancellation.** A page already in flight is allowed to arrive and
  is then dropped, so abandoning costs at most one page's latency and can never leave a half-written
  file. Holding the run's `Job` to cancel it would need atomic cross-thread state for a saving measured
  in seconds.
- **`EnqueueChapterDownloads` re-queues a `Failed` chapter, keeping its original `queuedAt`.** The design
  said "dedupes, drops already-downloaded"; in practice tapping download on a failed chapter is the retry
  affordance, and a chapter that failed at position three belongs back at position three rather than
  behind everything queued since.
- **`EnqueueNextChapters(mangaId, count = null)` means "all unread".** "All chapters", read ones
  included, is `EnqueueChapterDownloads` over the visible list and belongs to the UI that has that list.
- **The engine requeues interrupted rows itself**, at the top of every run, rather than exposing a
  startup use case: it is the only thing that ever writes `Downloading`, so it is the only thing that can
  tell a live row from debris.
- **`DownloadRunResult.Completed` counts an aborted chapter as neither downloaded nor failed** — the user
  asked for it not to happen, which is not a failure.
- **The queue drain has a re-pick guard.** A chapter still `Queued` after its turn would be selected
  forever; every path leaves a terminal state or deletes the row, so seeing one is a bug, and stopping the
  run is far better than spinning on it.

### Still open going into phase 3

- **Nothing enqueues a chapter yet.** The engine is reachable only from the use cases, and their callers
  are phase 4's chapter rows and bulk actions. The launch-time `ensureRunning()` in `App` covers the one
  case that exists today: a queue an earlier process was killed in the middle of.
- **Pause is not implemented.** `SettingsRepository.downloadsPaused` (§4) and the loop's check on it land
  with the queue screen in phase 5, which is the first thing that can turn it on or off.
- **`DownloadError.OutOfSpace` is still never produced.** Free-space checking needs a port neither okio
  nor common Kotlin offers; a write failure currently fails its chapter as `Unknown`. It belongs with the
  settings surface that shows storage usage (phase 7).
- **`DownloadNotifier` (§4) was not built** — it is only meaningful alongside the platform runners in
  phase 6.
- **Phase 3 must not make `GetChapterPages` download-aware**, contrary to §6's sketch. The downloader is
  a caller of it that must *always* reach the source, so a local-first decision inside it would have the
  engine download a chapter from its own copy. The local-or-remote choice belongs in a new use case
  *above* it (`GetReaderPages`), leaving `GetChapterPages` as what its name and KDoc already say: the
  source's pages.
- **Cancel racing a page write** can leave a part directory behind — abort is checked either side of the
  fetch, but not inside the store's write. Reclaiming it is `VerifyDownloads`' job in phase 8, which is
  the same sweep that already has to handle a user clearing app storage.
