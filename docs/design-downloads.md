# Design — Chapter downloads (offline reading)

Status: **phases 0–7 built** (the storage spine, the engine, the offline read path, the manga screen
that drives them, the queue screen that controls them, the platform runners that keep them going, and the
settings that automate them — see §12–§19). The feature is usable end to end; the queue is visible,
pausable and clearable, on Android it survives leaving the app, and downloads can now happen — and be
cleaned up — without being asked for each time.
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
| **3** ✅ | `PageImage` + `GetReaderPages` + `ToImageRequest`/`ReaderState` ripple | **A downloaded chapter reads in airplane mode** (once something downloads one) |
| **4** ✅ | Manga screen: row states, bulk actions, "download next N" | **The feature becomes usable** |
| **5** ✅ | `:features:downloads` queue screen + `Destination.Downloads` + Settings entry, pause | The queue is visible and controllable |
| **6** ✅ | Android FGS worker + progress notification; iOS background task + completion notification | Queues survive leaving the screen |
| **7** ✅ | Settings → Downloads (auto-download, Wi‑Fi only, delete-after-read, storage usage); library badge + `downloaded` filter | Automation + housekeeping |
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

---

## 15. As built — phase 3 (2026-09-06)

The offline read path. A chapter with a committed manifest is now read from disk, with no network and no
source involved; everything else reads exactly as before. What landed:

| Where | What |
|---|---|
| `:core:domain` `chapter/model/` | `ReaderPage`, `PageImage` (`Remote` / `Local`) |
| `:core:domain` `chapter/usecase/` | `GetReaderPages`; `GetChapterPages` KDoc narrowed to "the source's pages" |
| `:core:domain` `download/usecase/` | `GetLocalChapterPages` |
| `:features:reader` | `ToImageRequest` takes a `ReaderPage`; `ReaderItem.PageItem.page` is one; `ReaderViewModel` asks `GetReaderPages`; `PagePrefetch` simplified; the page composable renamed `ReaderPageImage` |

9 tests (`GetReaderPagesTest`) over the download tests' own in-memory store, so "downloaded" means to
them what it means to the engine — a committed manifest, not a row.

### Where the code differs from §6 above, and why

- **`GetChapterPages` did *not* become download-aware.** The decision recorded on 2026-09-06: the
  download engine is one of its callers and must always reach the source, so a local-first branch inside
  it would have the engine fetch a chapter from the copy it is in the middle of writing. The choice moved
  up into a new `GetReaderPages`, and the two callers now ask the two different questions they actually
  have — "what does the source serve" and "what should the reader show".
- **`ReaderPage` carries no `index`.** The design's sketch had one; the reader already carries a page's
  position on `ReaderItem.PageItem`, and a second copy could only ever disagree with the first. The type
  is kept (rather than collapsing to `PageImage`) because it is the seam the deferred descramble
  descriptor extends — see `design-page-image-abi.md`.
- **A page with neither an image URL nor a page URL is dropped at this boundary**, so nothing below it
  carries a "might have nothing to fetch" case. `PagePrefetch` lost exactly that check.
- **A local page is handed to Coil as an `okio.Path`**, not as a `file://` string. Coil 3.5 registers
  `PathMapper` + `FileUriFetcher` in common code, and it builds the URI structurally — which matters
  because the iOS downloads root lives under "Application Support", so a hand-assembled `file://` string
  would need escaping to survive the space.
- **A chapter whose files vanished reads as not-downloaded** and goes to the source. The authority is the
  manifest, never the `chapter_downloads` row, because only the row can outlive the bytes. A manifest
  listing *no* pages is treated the same way — the engine refuses to commit one, so it can only be a
  corrupted directory, and reading it as a download would show a blank reader instead of the chapter.

As §6 promised, a downloaded chapter never enters the image loader's *disk* cache — that tier only backs
the network fetcher — so a download costs its bytes once. Decode sizing, prefetch, the sliding chapter
window and the zoom/strip layouts are untouched.

---

## 16. As built — phase 4 (2026-09-06)

The manga screen now drives the whole subsystem: a chapter can be queued, watched, cancelled, retried and
deleted, and a series can be queued or cleared in bulk. This is the phase where phases 1–3 become
reachable. What landed:

| Where | What |
|---|---|
| `:core:domain` `download/usecase/` | `ObserveChapterDownloads` |
| `:assets:designSystem` | `TanukiProgressRing` (determinate, the component §8 said was missing); `DownloadedIcon`, `ErrorIcon`; `TanukiMenu`/`TanukiMenuItem`/`TanukiMenuDivider`; a fix to `CheckOffIcon` |
| `:features:manga` viewmodel | `Content.downloads` + `hasDownloads`; `ChapterSelection.canDownload` / `canDeleteDownload`; seven actions |
| `:features:manga` UI | `ChapterDownloadButton` (five row states), `MangaDownloadMenu` (app-bar bulk menu + confirm), two more cells in the selection bar |
| `:features:manga` strings | 15 new keys in `values/` and `values-pt-rBR/`, including a `download next N` plural |

### Where the code differs from §8.1, and why

- **The bulk menu hangs off the detail app bar, not the bottom bar's overflow.** The bottom bar is a
  single full-width "Read" button with no overflow to put anything in, and the design project's own
  `ui_kits/android/AndroidMangaDetailScreen.jsx` puts a `download` action in the top bar. Same contents
  as §8.1 — next 1 / 5 / 10, all unread, all, and a destructive delete-all behind a confirm.
- **The row's affordance opens a menu only when the chapter is finished.** §8.1 asked for that, and the
  reason is worth keeping: queue, cancel and retry are single reversible acts, so putting them behind a
  menu would tax the common case to protect the rare one. Deleting files is the only irreversible tap.
- **`canDeleteDownload` is "has any record", not "is downloaded".** One verb removes a finished chapter's
  files *and* takes an unfinished one out of the queue, so a selection spanning both does the obvious
  thing instead of making the user work out which of two verbs applies to which row.
- **The selection bar is six cells, and that is its ceiling.** At 360dp each cell is ~58dp wide and 56dp
  tall — past the 44dp tap floor — with two-line labels. A seventh verb would have to displace one into
  the top bar rather than squeeze in.
- **The trailing slot is a fixed `tapMin` box in both selected and unselected rows**, so starting a
  selection swaps the mark without re-flowing the list — the same reason the existing selection marks sit
  *around* the row layout rather than inserting a leading checkbox column.
- **Both download menus are `TanukiMenu`, not a bare `DropdownMenu`.** Phase 4 is the first surface in
  the app to need a dropdown, and Material's default arrives tonally elevated — on a near-black canvas
  that reads as a lighter, bluer card belonging to a different app than the screen behind it. The themed
  wrapper pins the surface, radius and hairline to the same tokens `TanukiDialog` and the sheets use, and
  gives destructive items a `danger` label; tonal elevation is zeroed so depth comes from the shadow and
  border, as it does on a dialog.
- **`TanukiProgressRing` treats unknown progress as a state, not a zero.** A queued chapter's page count
  is only discovered when the download reaches it (§5), so `ChapterDownload.progress` is nullable and the
  ring sweeps instead of sitting empty — a ring pinned at 0% and a ring that does not know are otherwise
  the same picture, and the first reads as "stuck".

### Still open going into phase 5

- **No queue screen yet**, so a `NeedsVerification` failure has no "verify to continue" affordance — the
  row shows the error glyph and a tap retries, which will fail the same way until the user browses to the
  source. That is phase 5's to fix.
- **Pause is still not implemented** (§4's `downloadsPaused`), for the same reason: the queue screen is
  the first surface that can toggle it.
- **`Manga.isDownloaded` is still hardcoded `false`.** There are now real rows to count, so projecting
  `downloaded_count` beside `unread_count` is finally worth doing — it lands with the library badge and
  filter in phase 7.
- **No `MangaViewModel` test.** No `:features:*` module has a test source set, and the ViewModel takes a
  dozen sealed-interface use cases that cannot be doubled from a test compilation, so covering the two new
  selection flags means standing up a fixture of a dozen real `Default`s over fake repositories. Worth
  doing deliberately rather than as a side effect of this phase.


---

## 17. As built — phase 5 (2026-09-06)

The queue became a place rather than an implementation detail: it can be looked at, paused, retried and
emptied, and a chapter that stopped on a Cloudflare challenge finally has somewhere to be dealt with.
What landed:

| Where | What |
|---|---|
| `:core:domain` `settings/` | `SettingsRepository.observeDownloadsPaused` / `setDownloadsPaused` |
| `:core:domain` `download/repository/` | `observePending` → **`observeQueue`**, widened to include `Failed` |
| `:core:domain` `download/model/` | `DownloadQueueSeries` + `QueuedChapter` |
| `:core:domain` `download/usecase/` | `ObserveDownloadQueue`, `ObserveDownloadQueueCount`, `CancelAllDownloads`, `ObserveDownloadsPaused`, `SetDownloadsPaused`; pause handling in `DownloadManager` |
| `:core:domain` `chapter/model/` | `ChapterNumber.display`, moved down from `:features:manga` |
| `:core:data` | the `downloads_paused` DataStore key; `QUEUE_STATES` |
| `:core:navigation` | `Destination.Downloads` |
| `:assets:designSystem` | `PauseIcon`, `PlayIcon` |
| **`:features:downloads`** (new module) | `DownloadsRoute`/`DownloadsScreen`, `DownloadsViewModel`/`DownloadsState`, `DownloadSeriesHeader`, `DownloadQueueRow`, 24 strings × 2 languages |
| `:features:settings` | a Downloads row on the index, subtitled with the queue count |
| `:features:manga` | `Content.hasPendingDownloads` + a *Downloads queue* item in the series download menu |
| `:shared` | the `Downloads` leaf in `TanukiNavHost` |

12 new tests (`DownloadPauseTest`, `DownloadQueueViewTest`) over the same `DownloadWorld` fixture.

### Where the code differs from §8.2, and why

- **`observePending()` became `observeQueue()` rather than gaining a sibling.** It had no production
  caller — it was written *for* this screen — and the screen must show failed chapters, which are
  precisely not "pending". Keeping the old name would have put the repository at odds with
  `ChapterDownload.isPending`, which still means "the engine owes work". `Downloaded` stays out: a queue
  that never empties makes "is anything still downloading?" unanswerable at a glance.
- **The header counts chapters, not "3 of 12".** A `Downloaded` row persists for the life of the
  download, so a denominator taken from all of a series' records would grow forever and describe batches
  the user queued months ago. What the screen can say honestly is how many chapters are left, and — when
  any of them stopped — how many failed, which is the only number on the row that needs acting on.
- **Pause is re-read per page, not mirrored into a field.** The first cut kept a `MutableStateFlow`
  updated by a collector for the length of a run. It is measurably worse: whether the mirror is current
  depends on that collector being scheduled between two pages, so how promptly the queue stops comes down
  to a race — which is exactly what the pause test caught. One cached preference read against an HTTP
  image download is not a cost worth a race.
- **A paused chapter is written back to `Queued`, with the run's counters.** Left `Downloading` it would
  be requeued at the next launch as debris from a dead process, and shown in this very screen as active
  while nothing ran. Its `.part` directory stays, so resuming re-fetches only what is missing —
  `DownloadManager` writes the live page/byte totals rather than the snapshot the chapter's turn started
  with, which would otherwise roll the row back behind the pages that had already landed.
- **A chapter that finished every page while the user was reaching for pause is committed**, not thrown
  back in the queue — the same rule that makes `CancelChapterDownloads` keep a chapter that just landed.
- **"Verify to continue" is the retry path, not a separate call.** A challenge is raised by the request
  itself, and it can only be solved while the app is on screen (`decisions.md`, 2026-08-22) — which is
  the one thing being on this screen guarantees. So a `NeedsVerification` row relabels its button and
  calls `EnqueueChapterDownloads` like every other retry, and the existing interceptor does the rest.
- **The group header opens the series; it does not collapse the group.** Collapsing is what a header of
  this shape usually does and is the wrong verb here: groups are a handful of rows, the queue is
  transient, and the question the screen raises — "what is this and why is it downloading?" — is answered
  by the series page.
- **Only "cancel all" confirms.** Cancelling one chapter or one series costs a re-tap of a button the
  user has just found; emptying the queue is not something they can reconstruct. The dialog also says
  explicitly that downloaded chapters are kept, because "cancel all" and "delete all downloads" are the
  one pair of actions in this feature that must never be confusable.
- **`ChapterNumber.display` moved onto the domain model.** It was an `internal` extension in
  `:features:manga`, and the queue names the same chapters. Copying it would let "Chapter 4" and
  "Chapter 4.0" drift apart across two screens, and a feature→feature dependency is not allowed — so how
  a source's chapter number is written now lives with the number.
- **The queue rows are `TanukiListRow`s, not a bespoke row.** The row's anatomy — leading status glyph,
  title over a secondary line, trailing control — *is* the design system's list row, so the screen
  inherits its 56dp floor (past the 44dp tap minimum), its leading gap, its type styles and its
  truncation rather than restating them. The one thing the component could not express was a subtitle
  that reports a *failure* rather than a value, so it gained an optional `subtitleColor`; the Sources
  screen has the same need for its failed installs.
- **The series header is deliberately *not* a `TanukiListRow`.** It has the identical anatomy, which is
  exactly the problem: a list row fixes its title to `body`, so a header built from one would weigh the
  same as the rows it heads and the group would read as four peers instead of a heading over three. The
  queue is a flat lazy list — it cannot use the recessed `TanukiSectionGroup` panel that gives the
  Settings index its grouping, because that would collapse every chapter into one un-recycled item — so
  hierarchy comes from `headline` weight and the cover instead. Everything else is borrowed: the same
  56dp floor, spacing tokens, `MangaCoverTile` and `TanukiIconButton`.
- **Group separation is two weights of `TanukiRowDivider`**: full-bleed between series, inset to the
  title column (`TanukiListRowDefaults.dividerInset`) between chapters. That difference is what says "new
  series" versus "next chapter" without a second heading style.
- **Three text colours were wrong in the light dialect, and two were mine to fix.** Measured against the
  paper canvas: `textFaint` is 2.5:1 and `accent` 3.35:1 — both under the floor for small text, and
  `textFaint` under even the 3:1 secondary bar. The status line and the series' chapter count moved to
  `onSurfaceVariant` (5.2:1 light, 8.4:1 dark), which is also the token `TanukiListRow` gives its own
  subtitles, so the whole screen's second tier now matches. The paused message no longer carries its
  meaning in the accent hue at all: it is a pause glyph plus full-weight `onSurface` text, which reads
  for a colour-blind user and outranks the count by weight rather than by colour. The third,
  `TanukiPaletteLight.Danger` at 4.14:1, is a **token** problem and is left flagged — deepening it
  changes every destructive button in the app.
- **The unknown-progress ring now honours reduce-motion.** A queue shows a column of perpetually sweeping
  rings, which is precisely the case the OS setting exists for, and the design system had no way to see
  it: the platform call lives in `:shared`, which depends on the design system rather than the reverse.
  So `TanukiTheme` takes `reducedMotion` and provides `LocalReducedMotion`, `App` passes
  `rememberReducedMotion()` into it, and the ring holds its arc still instead of spinning. The same pass
  stopped the infinite transition being *started* for determinate rings, where it animated a value
  nothing read, once per row, for as long as the screen was open.
- **Two icon buttons in the app bar, no overflow.** Pause/resume and cancel-all are the whole control
  surface, and a menu holding two items costs a tap to reveal what a bar can just show.

### Still open going into phase 6

- **Nothing keeps the queue running once the screen is left.** `ForegroundDownloadScheduler` launches on
  the application scope, so it survives navigation within the app, but not the process being backgrounded
  and killed. The Android FGS worker and the iOS background task are phase 6, and the queue screen is
  what phase 6's progress notification will deep-link into.
- **`DownloadError.OutOfSpace` is still never produced** (phase 7, with storage usage).
- **`Manga.isDownloaded` is still hardcoded `false`** (phase 7, with the library badge and filter).
- **Settings → Downloads (§8.3) does not exist yet.** The Settings row added here opens the *queue*; the
  settings sub-screen with auto-download, Wi-Fi-only and delete-after-read is phase 7, and will sit
  beside it rather than replace it — a queue you watch and a set of rules you configure are different
  errands.
- **No ViewModel tests for either screen.** Unchanged from phase 4: no `:features:*` module has a test
  source set, and standing one up is worth doing deliberately.
- **`TanukiPaletteLight.Danger` is 4.14:1 on the paper canvas**, just under the 4.5:1 floor for small
  text. It reaches caption-sized text here (a failed row's reason, a group's failure count) and on every
  destructive menu item elsewhere. Deepening the token is an app-wide visual change and belongs to the
  design system rather than to this screen; in the meantime no failure rests on colour alone — the row's
  glyph changes shape and the reason is spelled out.
- **`TanukiListRow` and `TanukiIconButton` suppress their press indication** (`indication = null`,
  written explicitly in both), so a tap on a row or an icon button gives no ripple. That reads as a
  deliberate design-system decision rather than an oversight, so this phase did not override it — but it
  is the one item on the platform interaction checklist the screen does not meet, and it is app-wide, not
  local to the queue.
- **Domain models are unstable to the Compose compiler.** `:core:domain` has no Compose plugin and there
  is no stability configuration file, so every `Chapter`/`Manga`/`ChapterDownload` is treated as
  unstable and every visible row recomposes on each per-page progress emission. Lazy-list keys keep this
  cheap enough today; a `stabilityConfigurationFile` listing `app.tanuki.core.domain.*` would fix it for
  this screen, the library grid and the chapter list at once.


---

## 18. As built — phase 6 (2026-09-06)

The queue stopped being tied to the screen. On Android it is a `dataSync` foreground-service job that
keeps fetching with the app closed; on iOS it is a foreground run with a grace period and a
`BGProcessingTask` continuation, and the screen now says so. What landed:

| Where | What |
|---|---|
| `:core:domain` `download/model/` | `DownloadProgress` |
| `:core:domain` `download/service/` | `DownloadNotifier`; `DownloadScheduler.runsInBackground` |
| `:core:domain` `download/usecase/` | `RunDownloadQueue` |
| `:shared` `androidMain/download/` | `DownloadWorker`, `WorkManagerDownloadScheduler`, `AndroidDownloadNotifier`; `ic_stat_download` |
| `:shared` `androidMain/di/` | `TanukiWorkerFactory` moved here, now building two workers |
| `:shared` `iosMain/download/` | `IosDownloadScheduler`, `IosDownloadTasks`, `IosDownloadNotifier` |
| `:shared` commonMain | `ForegroundDownloadScheduler` **deleted**; 12 notification strings × 2 languages |
| `androidApp` | `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC`; `foregroundServiceType="dataSync"` merged onto WorkManager's service |
| `iosApp` | `processing` background mode, the `app.tanuki.downloads` task identifier, `registerDownloadTask()` |
| `:features:downloads` | `runsInBackground` in state; the iOS-only "keep Tanuki open" footnote |

6 new tests (`RunDownloadQueueTest`).

### Where the code differs from §7, and why

- **The notification is *observed*, not reported.** §4 sketched a `DownloadNotifier` the engine calls.
  It is driven from `ObserveDownloadQueue` instead — the same flow the queue screen renders — inside a
  new `RunDownloadQueue` that composes the engine with the notifier. Three things come out of that:
  `DownloadManager` keeps its three ports and never has to resolve a series title mid-download; the
  notification and the queue screen cannot disagree, because there is one source and it is the database;
  and a lagging collector costs a stale notification frame rather than correctness — unlike the pause
  flag, where the same shape *was* a correctness problem and was removed for it (§17).
- **`DownloadNotifier.onProgress` is a legitimate no-op on iOS.** Android needs it — the queue runs in a
  foreground service and the notification is what makes that legal. iOS has no stretch of time where a
  progress notification would be seen instead of the queue screen itself, so posting one would mean a
  notification appearing and vanishing while the app is open in front of the user. The interface is
  shaped so that asymmetry is stated rather than faked.
- **There is no separate in-app runner on Android.** `ensureRunning()` enqueues the worker whether or not
  the app is open. Two runners would be two ways for the same rows to be picked up, and the guarantee the
  subsystem rests on is that exactly one thing works the queue — a design that cannot collide beats one
  that detects collisions.
- **`ExistingWorkPolicy.KEEP`, one-time work.** A queue is not a schedule: it exists because the user
  asked for chapters and it is done when they are on the device. `KEEP` is what lets every enqueue, the
  launch resume and the un-pause all call `ensureRunning()` without knowing about each other — a request
  arriving mid-run is dropped, because the run in flight re-reads the queue after every chapter.
- **`setForeground` is allowed to fail.** It throws when Android will not permit a foreground-service
  start (background-start restrictions, notifications revoked). The response is to download anyway under
  the ordinary background limits rather than abandon a queue the user asked for.
- **Two Android channels, not one.** Progress is `IMPORTANCE_LOW` (a status line; fifty chapters must not
  buzz fifty times) and the summary is `IMPORTANCE_DEFAULT`. A user who wants one and not the other can
  turn each off, which is what channels are for. `setOnlyAlertOnce` matters just as much: without it a
  forty-page chapter is forty notification sounds.
- **iOS submits a continuation only when the run left work behind.** §7 implied one per run. Submitting
  after a run that emptied the queue asks iOS to wake the app for nothing, and that is precisely what
  spends the background budget the runs with real work then need.
- **`requiresExternalPower = false` on the continuation.** A queue is work the user explicitly asked for;
  requiring the phone to be plugged in would mean most queues never resume.
- **A run that did nothing says nothing.** Every launch asks the queue to run, and a "0 downloaded"
  notification each time would train the user to dismiss the ones that matter. A *skipped* run touches
  the notifier not at all — the run that owns the queue owns what is shown about it, and clearing from a
  skipped one would take down a live progress notification for work still in flight.
- **`runsInBackground` lives on `DownloadScheduler`.** The scheduler is the thing that decides where work
  runs, so it is the thing that can answer whether that survives the app; a separate capability port
  would be a second answer to a question this interface already owns. The queue screen renders it as a
  footnote, in `onSurfaceVariant` rather than a warning colour — it is a platform fact with an action
  attached ("keep Tanuki open"), not a fault.

### Still open going into phase 7

- **Wi-Fi-only is not wired.** The worker's constraint is `NetworkType.CONNECTED`; the
  `downloadOnWifiOnly` setting (§8.3) and the `UNMETERED` constraint that follows from it are phase 7.
  Imposing it now would silently strand every mobile-only user's queue.
- **The notifications have no actions.** §7 asked for pause and cancel buttons on the progress
  notification. Both need a `BroadcastReceiver` (or a trampolining Activity) that can reach the graph,
  which is a piece of Android plumbing this app does not have yet — and the queue screen the notification
  opens has both controls.
- **Neither notification deep-links.** A tap opens the app where the launcher would, because destinations
  live in `:core:navigation` and the host that renders them is a single Activity `:shared` cannot name —
  the same limitation `AndroidLibraryUpdateNotifier` already documents, and the same fix for both.
- **`DownloadError.OutOfSpace` is still never produced** (phase 7, with storage usage). The worker's
  `requiresStorageNotLow` constraint keeps a queue from *starting* on a full device, which is not the
  same as failing gracefully partway through one.
- **The iOS grace period is unverified on a device.** `beginBackgroundTask` gives a system-determined
  window that the simulator does not model, and `BGProcessingTask` is never delivered there at all —
  both need a real device to observe.


---

## 19. As built — phase 7 (2026-09-06)

The subsystem stopped needing to be asked. Chapters can arrive downloaded, read ones can clear
themselves away, and what the whole thing costs is finally visible next to the button that empties it.
What landed:

| Where | What |
|---|---|
| `:core:domain` `settings/model/` | `AutoDownloadMode`, `DeleteAfterReading` |
| `:core:domain` `settings/repository/` | four new settings on `SettingsRepository` (auto-download mode + categories, Wi-Fi only, delete-after-reading) |
| `:core:domain` `download/model/` | `DownloadSettings`, `DownloadStorageUsage` + `SeriesStorage` |
| `:core:domain` `download/usecase/` | `ObserveDownloadSettings`, `SetAutoDownload`, `SetDownloadOnWifiOnly`, `SetDeleteAfterReading`, `ObserveDownloadStorageUsage`, `AutoDownloadNewChapters`, `DeleteReadChapterDownloads` |
| `:core:domain` hooks | `SyncSourceChapters` → auto-download; `SetChaptersRead` and `UpdateReadingProgress` → delete-after-reading |
| `:core:domain` `library/` | `LibraryFilter.downloaded` (+ `isActive`, + `ObserveLibrary`) |
| `:core:data` | the four DataStore keys; `downloaded_count` in all three `MangaDao` queries → real `Manga.isDownloaded` |
| `:shared` androidMain | `WorkManagerDownloadScheduler` reads the Wi-Fi setting and picks `UNMETERED`/`CONNECTED` |
| `:assets:designSystem` | `TanukiSwitchRow`, `TanukiChoiceRow`; `TanukiListRow.destructive` |
| `:core:navigation` | `Destination.SettingsDownloads` |
| `:features:settings` | the Downloads settings screen + ViewModel/state, byte formatting, 35 strings × 2 languages; `PrivacyScreen` refactored onto `TanukiSwitchRow` |
| `:features:library` | the `downloaded` tri-state control in the filter sheet |

11 new tests (`DownloadAutomationTest`).

### Where the code differs from §8.3–§8.4, and why

- **"Keep last N" is a fixed set of options, not a number picker.** `DeleteAfterReading` is
  `Off / Immediately / KeepLastOne / KeepLastFive`. A stepper implies the difference between 3 and 4
  matters; it does not. What matters is the shape of the choice — never delete, delete at once, or keep
  enough slack to flick back — and four named options say that in a way a number never does.
- **The rule is applied to the *series*, not to the chapters it was handed.** "Keep the last N read" can
  only be answered by looking at all of them: marking chapter 40 read is what makes chapter 35 the one to
  delete. So the ids that trigger it are a trigger, and `Immediately` is the same rule with `N = 0`
  rather than a special case.
- **Bookmarked chapters are never deleted.** Not in §8.3, and it is the one exception the rule needs: a
  bookmark is the user saying "come back to this", which is precisely the opposite of what
  delete-after-reading is for.
- **Turning delete-after-reading on is not retroactive.** It changes what happens from now on and sweeps
  nothing. A setting that silently removed a hundred existing downloads the moment it was switched on is
  the one outcome the user cannot undo, and nothing in the row they tapped would have suggested it. The
  screen says so, in a hint that appears only once the setting actually deletes something.
- **An empty category selection downloads nothing.** "Download for these categories", with none picked,
  cannot mean "all of them" — the generous reading would spend a user's data on a half-configured
  setting. The screen says so at exactly that moment rather than as permanent copy.
- **Auto-download skips series outside the library.** A sync also runs while the user is merely browsing
  a source; downloading a series they have not saved would spend their storage on something they were
  only looking at. `SyncSourceChapters` already applies the same guard to chapter updates.
- **The hook lives inside `SyncSourceChapters`, not in its callers.** Both callers — the scheduled
  library update and a manual pull on a series page — mean the same thing by "a new chapter arrived", so
  neither needs to know the rule.
- **Storage is measured, not summed.** The totals come from `ChapterDownloadStore` walking the files
  rather than from adding up `ChapterDownload.bytes`. The two drift (a write that died, a `.part` an
  abandoned run left, storage cleared from the OS), and the number this screen exists to justify —
  "should I delete something?" — is only useful if it matches what the system would report.
- **…and therefore the store is the trigger, not the records.** This shipped wrong and is worth keeping
  written down: the first cut measured the files but woke on `observeDownloadedCounts`, and every delete
  path removes the rows *before* the files. So the measurement ran while the bytes were still on disk and
  was then never woken again, leaving "1.2 GB used" on screen over an empty directory. Whatever is the
  authority for a number has to be the thing that says the number moved —
  `ChapterDownloadStore.observeChanges()` now does. It also catches the changes no record can describe,
  which is exactly what phase 8's orphan sweep will produce.
- **Decimal units, not binary.** `1000`, so "2.1 GB" here is "2.1 GB" in the OS storage screen the user
  is about to go and check. Being 7% off the binary figure matters far less than disagreeing with the
  thing being compared against.
- **The breakdown stops at five series.** A full list would be a second library grid on a settings
  screen; the question here is "what is taking the room", which the largest few answer. Anything finer is
  a per-series decision, and the series' own page is where that is made.
- **`downloaded_count` is a correlated subquery, mirroring `unread_count`.** Not a column on `manga`: it
  is a count over another table, and a stored one would need invalidating from every write that touches
  `chapter_downloads`. `Manga.isDownloaded` is "has at least one committed chapter", which is what both
  the library badge and the filter ask.
- **The settings index row now opens the settings, which link on to the queue.** Two destinations, one
  entry point. The row's subtitle still names the queue's count — a download that stopped on an error is
  otherwise invisible from anywhere the user is likely to be.

### On the UI

- **Two new design-system components, both encoding a rule callers forget.** `TanukiSwitchRow` makes the
  *whole row* the target (a switch is ~52×32dp; the row is 56dp and full-width) and announces
  `Role.Switch`. `TanukiChoiceRow` reserves the tick's slot whether or not it is showing one, so changing
  the selection does not shift the list sideways, and announces `Role.RadioButton` with a `selected`
  state so a screen reader hears one group of alternatives rather than a stack of buttons.
- **Rows, not a segmented control, for these choices.** The decision is about labels, not counts: a
  segmented control needs every label to survive being a third of the screen wide. "Off / Light / Dark"
  does; "Keep the last five read chapters" does not.
- **Every option carries a subtitle saying what it does.** These settings spend storage and mobile data,
  and "Keep the last five" means nothing alone. A control whose consequence has to be guessed at is one
  the user will either avoid or regret.
- **The per-category mode is hidden, not disabled, when there are no categories.** A disabled row invites
  the user to work out how to enable it, and the answer — "go and make a library category" — is on a
  different screen with no way to say so from here.
- **`TanukiListRow` gained `destructive`, matching `TanukiMenuItem`'s.** The first cut set `subtitleColor`
  on a row with no subtitle, so the delete-everything row rendered as an ordinary one — the exact defect
  the `destructive-emphasis` rule names. Same word, same meaning in both components now, so a
  "delete everything" reads identically whether it is a menu item or a row.
- **The confirmation says what is *not* touched.** The fear the dialog has to answer is "will I lose my
  library", and only naming it answers it.

### Still open going into phase 8

- **Wi-Fi-only is not enforced on iOS.** Android gets it free from WorkManager's `UNMETERED` constraint.
  iOS needs a metered-connection signal (`NWPathMonitor.isExpensive`), and the natural home for it is the
  `ConnectionObserver` seam that is currently in flight — so the toggle is stored and honoured on one
  platform only. The setting is not hidden on iOS: a preference that exists on one device and vanishes on
  another is worse than one that is not yet applied, and the row says as much.
- **`DownloadError.OutOfSpace` is still never produced.** Free-space checking needs a port neither okio
  nor common Kotlin offers. The storage screen now at least tells the user what they are using, which is
  the half that does not need one.
- **No ViewModel tests.** Unchanged since phase 4: no `:features:*` module has a test source set.
- **`VerifyDownloads` and the cascade hooks are phase 8** — the orphan sweep is what reconciles the
  measured storage total against the records, and it is the reason the total is measured rather than
  summed.
