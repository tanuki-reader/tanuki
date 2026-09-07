# Tanuki Efficiency Backlog

## Context

Tanuki currently does a lot of avoidable work: it re-fetches from sources on every screen
entry, refreshes every library series on every update run regardless of how often that series
actually publishes, holds the entire library (descriptions included) in RAM, and re-runs a
per-series query loop on every history write.

The prompt for this was the manga detail screen: opening a series fires **two** source calls
(`SyncSourceChapters` + `SyncSourceMangaDetails`) every single time, with no staleness check.
Mihon solved the general version of this with *smart updates* — a per-manga `fetch_interval`
derived from how frequently the series actually publishes, and a `next_update` timestamp the
library run honors.

This document is a **backlog, not a single change**. Every item is independently completable
and independently shippable; work them one at a time in roughly the order given (ordering is
by leverage-per-risk, and later items in a phase sometimes build on earlier ones — noted where
that's true).

**Decided:** the detail screen keeps refreshing on open, but TTL-gated (item 1.3), not
`next_update`-gated — a manually opened series should never show days-old chapters.

---

## Phase 1 — Smart updates (the headline change)

The three items below are one coherent feature and are best done in order.

### 1.1 Add sync bookkeeping columns to `manga` (schema v11)

**Problem:** there is no timestamp anywhere saying when a series was last polled. The only
time-ish column, `manga.cover_last_modified`, is an image cache key that
`SyncSourceMangaDetails` deliberately avoids bumping — it cannot be repurposed.

**Fix:** add three nullable columns to `manga`:

| column | meaning |
|---|---|
| `chapters_fetched_at` | when `SyncSourceChapters` last completed for this series |
| `details_fetched_at`  | when `SyncSourceMangaDetails` last completed |
| `next_update_at`      | when the library run should next consider this series |
| `fetch_interval`      | days between expected releases; negative = user-pinned override |

`fetch_interval` mirrors Mihon's sign convention: a negative value means "the user chose this,
don't recompute" (see `FetchInterval.toMangaUpdate` in the reference repo). Even if the UI to
set it doesn't exist yet, encoding it now avoids a second migration.

**Files:**
- `core/data/src/commonMain/kotlin/app/tanuki/core/data/repository/manga/MangaEntity.kt`
  (fields + `toDomain`/`toEntity`)
- `core/data/src/commonMain/kotlin/app/tanuki/core/data/database/Migrations.kt` (v10 → v11)
- `core/domain/src/commonMain/kotlin/app/tanuki/core/domain/manga/model/Manga.kt`
- `core/data/schemas/app.tanuki.core.data.database.TanukiDatabase/11.json` (generated)

**Important:** `MangaRepository.upsertManga` writes the whole row. Do **not** use it to stamp
these columns — follow the existing targeted-`@Query` pattern already used by `setReadingMode` /
`setReadingStatus` in `MangaDao.kt:155-174`, and add e.g. `markChaptersFetched(id, at, nextUpdate)`.
Otherwise a stamp race can clobber a concurrent detail write.

### 1.2 `FetchInterval` use case + per-manga skipping in `UpdateLibrary`

**Problem:** `UpdateLibrary.Default.run()`
(`core/domain/.../update/usecase/UpdateLibrary.kt:144-155`) takes `observeLibrary().first()`
and fans out **every** series, unfiltered. A finished series from 2009 is polled exactly as
often as a weekly ongoing one. With a 500-title library on the default `DAILY` interval that's
500 source round-trips a day, nearly all of them returning an unchanged list.

**Fix:** port Mihon's `FetchInterval`
(`~/Documents/personal_projects/tanuki/domain/src/main/java/tachiyomi/domain/manga/interactor/FetchInterval.kt`
— read it, the algorithm is subtle) as a new use case in
`core/domain/.../manga/usecase/FetchInterval.kt`:

- window of the last 3 chapters if the series has ≤ 8, else 10;
- prefer distinct `date_upload` days; fall back to distinct `date_fetch` days; default 7 days;
- interval = **median** of the day-gaps between consecutive dates, clamped to `1..28`;
- `next_update_at` = the last-update day + `(cycle + 1) * interval`, where the interval doubles
  for series we've missed more than ~10 check cycles on (`increaseInterval`) — this is what makes
  a dead series decay toward 28-day polling instead of staying at 7;
- a ±1-day grace window so a `next_update_at` already inside today's window isn't recomputed.

Then, in `UpdateLibrary.Default.run()`, filter the fan-out to series where
`next_update_at == null || next_update_at <= now`, and after each successful `refresh(manga)`
recompute the interval and stamp `next_update_at`. Keep the existing `Semaphore(MAX_CONCURRENT_SOURCES)`
grouping untouched.

**Also skip on publish status:** `Manga.publishStatus` already exists. A `COMPLETED`/`CANCELLED`
series should get the 28-day interval floor immediately rather than earning it over months.

**Edge case to get right:** `Trigger.Manual` currently bypasses `isDue()`. It must also bypass
`next_update_at` — an explicit "update library now" means *all* of it.

**Files:** `UpdateLibrary.kt`, new `FetchInterval.kt`, `MangaDao`/`MangaRepository` for the stamp.

**Note on porting:** Mihon uses `java.time.ZonedDateTime`; this is KMP, so express it with
`kotlinx-datetime` (`Instant` + `TimeZone.currentSystemDefault()` + `LocalDate`). Day-boundary
arithmetic is the whole point of the algorithm — do not simplify it to "hours since last fetch".

### 1.3 TTL-gate the detail screen's two refreshes

**Problem:** `MangaViewModel.init` (`features/manga/.../viewmodel/MangaViewModel.kt:156-157`)
calls `refreshChapters()` and `refreshDetails()` unconditionally. The VM is scoped to the
`NavBackStackEntry`, so **every push of the detail route = 2 source calls.** Browsing back and
forth through 10 series is 20 round-trips. Worse, they're serialized behind `WasmSourceLoader`'s
per-source `Mutex` (`WasmSourceLoader.kt:219-226`), so they also stall any concurrent browse or
reader-page call to that same source.

**Fix:** add a `force: Boolean = false` parameter to both `SyncSourceChapters.invoke` and
`SyncSourceMangaDetails.invoke`. When `force` is false, return early if
`chapters_fetched_at` / `details_fetched_at` is within a TTL. Suggested TTLs — chapters 30 min,
details 24 h (descriptions and authors change far more rarely than chapter lists).

`UpdateLibrary` passes `force = true` (it does its own `next_update_at` gating and must not be
double-gated). The detail screen passes `force = false`.

**Do the gating inside the use cases, not in the ViewModel** — that keeps the rule in one place
and testable without a Compose or ViewModel harness.

**Watch out:** `SyncSourceChapters` has real side effects beyond the fetch — it writes
`chapter_updates` rows and triggers `AutoDownloadNewChapters`. Returning early must skip all of
it cleanly, which it does naturally if the guard is the first statement.

### 1.4 Pull-to-refresh on the detail screen

**Problem:** once 1.3 lands, a TTL-suppressed refresh leaves the user with no way to force one.
There is currently **no pull-to-refresh anywhere in the app** (grep for `pullToRefresh` — zero
hits), and `MangaViewModel`'s own KDoc at line 165 flags the gap.

**Fix:** add `PullToRefreshBox` to `MangaScreen`, calling a new `MangaViewModel.onRefresh()` that
invokes both syncs with `force = true`. This is also the natural place to finally surface the
refresh error the KDoc admits is only logged today.

**Files:** `features/manga/.../MangaScreen.kt`, `MangaViewModel.kt`, plus new
`composeResources` strings in **both** `values/` and `values-pt-rBR/` (CLAUDE.md → Localization).

---

## Phase 2 — Kill the N+1 query loops

This is the single largest source of wasted CPU and allocation in the app, and it is
independent of Phase 1.

### 2.1 Add batch DAO reads — **DONE**

Shipped as `MangaDao.getMangaByIds` / `ChapterDao.getChaptersByIds`, surfaced on the repositories as
`getMangaByIds` / `getChaptersByIds` and chunked under SQLite's bind limit by
`core/data/.../repository/BatchReads.kt`. All five call sites below now do two batch reads instead of a
per-id loop, and `ObserveDownloadQueue.resolve` was moved onto them too (it keeps its memoization).
Chapters are read **by chapter id**, so no call site loads a whole series' chapter list any more.

**Problem:** the pattern `mangaRepository.observeManga(id).first()` inside a loop appears in five
use cases. Each call opens a **new Room subscription** running the full `MangaWithDetails` query
— which includes two correlated subqueries, one of which scans `chapters ⋈ reading_progress` —
and immediately tears it down.

The worst offender is `ObserveReadHistory`
(`core/domain/.../history/usecase/ObserveReadHistory.kt:49-50`), which does this over the
**entire unbounded history**. For 300 read series that's 600 heavyweight queries, plus every
chapter row of all 300 series materialized into domain objects — re-run on **every history
write**, and the reader writes history on every chapter open.

**Fix:** add to `MangaDao` / `ChapterDao`:
- `getMangaByIds(ids: List<Uuid>): List<MangaWithDetails>` — `WHERE manga.id IN (:ids)`
- `getChaptersByIds(ids: List<Uuid>): List<ChapterWithProgress>`

then replace the loops. Note `ResolveChapterUpdates.kt:52-62` loads *every chapter of a series*
only to `firstOrNull { it.id == update.chapterId }` — `ChapterDao.getChapter(chapterId)` already
exists and is unused; the batch form is what it wants.

**Call sites:** `ResolveChapterUpdates.kt:54-55`, `ObserveReadHistory.kt:49-50`,
`ObserveReadingStats.kt:69`, `GetMostRead.kt:63`, `ObserveDownloadStorageUsage.kt:74`.

**Reuse the pattern that's already right:** `ObserveDownloadQueue.kt:60-77` has the same shape
but memoizes — `resolve()` only re-runs when the *set* of ids changes, not on every progress
emission. Copy that structure into the others.

### 2.2 Fix the `ObserveNewChapters` staleness bug

**Problem:** the KDoc on `ObserveNewChapters` claims it "re-emits whenever the records change
**or** the underlying manga/chapters do." It does not — `ResolveChapterUpdates` takes snapshots
with `.first()` and unsubscribes, so only writes to `chapter_updates` retrigger it. Home's "New
Chapters" list can show a stale title or a chapter that's since been read.

**Fix:** either correct the doc, or (better, and near-free once 2.1 lands) make the resolver
depend on the batch query as a real `Flow` so the claim becomes true.

### 2.3 Bound the history table

**Problem:** `HistoryDao.observeHistorySince(since)` is called with `DISTANT_PAST` at every call
site — an unbounded `SELECT * FROM history`. Nothing ever prunes `history`; `HistoryDao` has
`clear()` and `deleteForManga` but no retention policy, so this grows forever.

**Fix:** either a retention sweep (drop rows older than N months, or cap at N rows per manga) or
push a `LIMIT` into the callers that only need recent activity. `ObserveReadingStats` genuinely
needs the whole table — that one argues for retention rather than `LIMIT`.

---

## Phase 3 — Memory: stop holding the whole library

### 3.1 Route screen state through `TanukiViewModel.asState`

**Problem:** `TanukiViewModel.asState` (`core/presentation/.../TanukiViewModel.kt:89-95`)
correctly uses `SharingStarted.WhileSubscribed(5_000L)` — but **almost nothing uses it for main
screen state.** `LibraryViewModel`, `HomeViewModel`, `MangaViewModel`, `ActivityViewModel`,
`DownloadsViewModel` and `ReaderViewModel` all end their pipeline with:

```kotlin
}.flowOn(dispatcher.default)
    .onEach { uiState.value = it }
    .launchIn(viewModelScope)
```

That bypasses `WhileSubscribed` entirely. The pipelines start in `init` and run for the
ViewModel's whole lifetime whether or not any UI is subscribed. Since `LibraryViewModel`,
`HomeViewModel`, `ActivityViewModel` and `DownloadsViewModel` are all `@ContributesIntoMap(AppScope::class)`,
that means **four always-on Room subscription trees** — one of them Activity's four-way
fan-out — running for the life of the process, including while the user is deep in the reader.

**Fix:** convert each to `val uiState = pipeline.asState(Loading)`. This is a mechanical change
per ViewModel and can be done one screen at a time.

**Constraint from CLAUDE.md:** `uiState` uses Kotlin's explicit backing field
(`val uiState: StateFlow<S>` / `field = MutableStateFlow(...)`). Converting to `asState` means
dropping the mutable backing field for screens whose state is purely derived. `MangaViewModel`
and `ReaderViewModel` also push *imperative* state updates, so check each one — some may need to
keep the `MutableStateFlow` and instead gain a `subscriptionCount`-based upstream gate.

### 3.2 Narrow the library projection

**Problem:** `MangaDao.observeLibrary()` (`MangaDao.kt:62-91`) does `SELECT manga.*`. The library
grid (`LibraryGrid.kt:71-81`) uses only `id`, `title`, `cover.url`, `cover.headers`,
`unreadCount`, `isDownloaded` — but every row drags along `description` (potentially multi-KB of
prose), `filtered_scanlators`, `url`, `source_manga_key`. **Every description in the library sits
in RAM for as long as the Library tab is warm.**

And it's not just held — `MangaWithDetails.toDomain` (`MangaEntity.kt:82-119`) allocates
`Text(title)`, `Text(author)`, `Text(description)`, a `MangaCover`, `decodeHeaders()` and
`decodeScanlators()` **per row per emission**; then `ObserveLibrary.kt:76-79` produces three more
full list copies (`applyFilter` → `applySearch` → `applySort`). That's ~4–5 full library
allocations **per keystroke** in the search box.

**Fix:** add a `LibraryCardProjection` — `id, title, cover_url, cover_headers,
cover_last_modified, unread_count, downloaded_count` plus whatever the sort/filter needs — and a
`LibraryEntry` domain type for the grid. Keep the full `Manga` for the detail screen only.

The sort/filter work in `ObserveLibrary` is already correctly designed (knobs passed as `Flow`s
so the Room subscription happens once) — only the row shape needs narrowing.

### 3.3 `distinctUntilChanged()` after `observeLibrary()`

**Problem:** the library query is invalidated by writes to six tables, including
`chapter_downloads`. `ChapterDownloadDao.setProgress` (`ChapterDownloadDao.kt:61-69`) fires
**once per downloaded page**. So during any download, the entire library query — full `manga.*`
plus the per-row correlated unread subquery — re-runs per page fetched, even though
`downloaded_count` almost never changes.

**Fix:** `.distinctUntilChanged()` on the repository flow. Cheap, high payoff, and independent of
3.2 (do both; 3.2 makes the comparison cheaper too).

Same treatment for `CategoryDao.observeMangaCategories()`, which is additionally subscribed
**twice** by `CategoryRepositoryImp` (`:30` and `:42`) — two independent subscriptions over the
same full join table, each re-grouped in Kotlin on every change. Share one flow.

### 3.4 Index `manga(title)`

`observeLibrary()` and `observeMangaBySource()` both `ORDER BY manga.title` with no index — a
full sort on every emission. Minor at 200 titles, real at 2000+. Folds naturally into whichever
migration comes next.

### 3.5 Fix the `LibraryViewModel.latestCategories` data race

`LibraryViewModel.kt:72` is a `var` mutated from inside the `combine` block (running on
`dispatcher.default`) and read from `onFilterClick()` on the main thread. Not a leak, but an
unsynchronized cross-thread read. Fold it into the state or make it a `StateFlow`.

---

## Phase 4 — Reader memory

### 4.1 Evict `ReaderViewModel.pagesCache`

**Problem:** the KDoc at `ReaderViewModel.kt:123` says *"Held only for the window
(prev/curr/next); others are evicted."* **They are not.** Every write to `pagesCache` is either
the full reset in `load()` (`:194`), the initial seed (`:243`), or the append at `:388`
(`pagesCache.update { it + (chapterId to pages) }`). There is no removal path.

Consequences over a long reading session:
- `pagesCache` accumulates the page list of every chapter visited, until the reader closes;
- `buildItems` emits one `PageItem` per page of **every loaded chapter** as `Content.items`, on
  every input change — every page turn, every chrome toggle. 40 chapters × 20 pages = an
  800-element list reallocated on each of ~800 page turns;
- that list is what `WebtoonReader`'s `LazyColumn` is fed, so its item count grows unbounded too.

**Fix:** evict entries outside `[currentIndex-1, currentIndex+1]`, as the KDoc already promises.

**This is the hardest item in the document.** `buildItems` is explicitly built on the assumption
that loaded chapters form a **contiguous, only-growing** range — its KDoc calls that out as what
"keeps a forward crossing free of any re-indexing". Eviction breaks that invariant, so
`buildItems` and the index arithmetic need reworking together. That's presumably why it was never
done. Budget real time and lean on the reader tests.

**Confirmed in production:** Sentry issue `TANUKI-2` is a `WatchdogTermination` — the OS killed
the app for RAM on a real device. This is the most likely cause.

### 4.2 Bound the two unbounded reader side-tables

- `PageAspectRatios` (`composable/components/PageAspectRatios.kt:30`) — an unbounded
  `mutableMapOf<String, MutableFloatState>`, one entry per page ever seen, documented as living
  "for as long as the reader is open".
- `PagePrefetch.warmed` (`PagePrefetch.kt:51`) — unbounded `mutableSetOf<String>`.

Neither holds bitmaps, so neither is catastrophic, but both are O(pages-read). Bound them to the
same window 4.1 establishes.

**What is already right in the reader — don't touch it:** separate page/cover `ImageLoader`s with
inverted cache economics, explicit decode bounds (`Size(width * 2, 4096)`, `Scale.FIT`,
`Precision.INEXACT`), and — critically — the *same* decode size used by both the render and
prefetch paths so a warmed page and a drawn page hit **one** cache entry. That last detail is the
most important thing in the whole reader and it is correct.

---

## Phase 5 — Network hygiene

### 5.1 Install `HttpTimeout` and `HttpRequestRetry` on the shared client

**Problem:** `installSourcePlugins` (`core/source/.../net/HttpClient.kt`) installs only the
user-agent, Cloudflare and cookie plugins. **No `HttpTimeout` anywhere.** Engine defaults apply
(OkHttp: 10 s connect/read, *no call timeout*), so a server that trickles bytes can hang a
download slot indefinitely — and `DownloadManager` runs one chapter at a time, so one stalled
page stalls the whole queue.

**No retry either**, except the hand-rolled 3-attempt/exponential-backoff loop inside
`DownloadManager` (`MAX_PAGE_ATTEMPTS = 3`, `RETRY_BACKOFF = 1.seconds`). Reader page loads,
cover loads and browse calls get zero retries.

**Fix:** install `HttpTimeout` (a real `requestTimeout` ceiling) and `HttpRequestRetry` with
exponential backoff on idempotent GETs. **Do not retry `DownloadError.NeedsVerification`** —
`DownloadManager:325` deliberately exempts Cloudflare challenges from retry, and a client-level
retry would fight the interceptor.

### 5.2 Pin Coil's `CacheStrategy` for covers and pages

**Problem:** `respectCacheHeaders` / `CacheStrategy` is never configured on either loader
(`shared/.../imaging/TanukiImageLoader.kt`). Coil 3.5 therefore uses `CacheStrategy.DEFAULT`,
which **honors `Cache-Control` / `Expires` / `ETag`**. So a source CDN sending `no-store` or a
short `max-age` causes covers and pages to be revalidated or re-downloaded despite 256 MB + 512 MB
of configured disk cache. The loader's own KDoc asserts "nothing needs revalidation" — that
assertion is not currently enforced by anything.

**Fix:** set an explicit forced/`respectCacheHeaders(false)` cache strategy on both loaders. This
is plausibly the single biggest bandwidth win in the document, and it is a ~3-line change.

Covers really are immutable (their URLs embed a file hash), so this is safe for them. For pages
it's safe for the same reason. If a source is ever found that mutates content behind a stable
URL, that's what 5.3 is for.

### 5.3 Wire or delete `MangaCover.lastModified`

**Problem:** `MangaCover.lastModified` is documented as *"cache-busting version — bump to
invalidate a cached cover"* (`MangaCover.kt:20`), and both `SyncSourceMangaDetails` and
`CacheSourceManga` contain careful reasoning about when **not** to bump it. But it **never
reaches an `ImageRequest`** — `coverImageRequest` (`assets/designSystem/.../CoverImageRequest.kt`)
takes only `(url, headers)`. Coil keys on the URL alone.

So the careful don't-bump logic is a no-op in both directions: bumping invalidates nothing, and a
source that changes cover art behind a stable URL can never be refreshed.

**Fix:** either append `lastModified` to the request's `memoryCacheKey`/`diskCacheKey`, or delete
the field and the reasoning around it. Pick one — the current state is the worst of both.

### 5.4 Make `PagePrefetch` connection-aware

**Problem:** `PagePrefetch` warms a 7-page window (`PAGE_PREFETCH_AHEAD = 5`,
`PAGE_PREFETCH_BEHIND = 2`) across chapter boundaries, and **never consults `ConnectionObserver`.**
Identical behavior on Wi-Fi, on 5G and on a metered hotspot. `ConnectionStatus.LOW_SPEED` exists
and is documented as "bulk work can choose to wait" — the reader never reads it.

Requests are also `imageLoader.enqueue(...)` fire-and-forget, detached from the `LaunchedEffect`,
so they are **not cancelled on scroll away** (the KDoc admits this). A fast scroll through a
chapter enqueues and fully downloads every page.

**Fix:** shrink the window on metered/`LOW_SPEED` connections (e.g. 2 ahead / 0 behind), and hold
the `Disposable` returned by `enqueue` so warms outside the current window can be cancelled.

**Prior art in this repo:** `ObserveDownloadHold` (`core/domain/.../usecase/ObserveDownloadHold.kt`)
already combines `downloadsPaused` + `downloadOnWifiOnly` + `connectionObserver.metered` into a
clean hold signal. Reuse the shape.

### 5.5 Explicit `ContentEncoding`

**Problem:** gzip currently works **by accident** — OkHttp and NSURLSession add
`Accept-Encoding: gzip` transparently. Neither gets brotli, neither is under app control, and any
wasm source that sets its own `Accept-Encoding` via `KtorSourceHost` **defeats OkHttp's
transparent gzip and gets raw bytes back**.

**Fix:** install Ktor's `ContentEncoding` plugin so encoding is explicit and uniform across both
engines.

### 5.6 Cap `WasmSourceLoader.modules` with an LRU

`WasmSourceLoader.modules` (`:87`) holds instantiated WASM modules — the heaviest objects in the
app's long-lived caches. It *is* evicted, but only via `evictModulesOutside(ids)` when the
*installed* source set changes. So every source the user browses in a session stays instantiated
for that whole session. An LRU cap (say 3–5 modules) would bound it.

Minor siblings, all process-lived and monotonic but genuinely small — audit and decide, don't
necessarily fix: `CloudflareCookieStore.byHost`, `CloudflareInterceptor.locks` (one `Mutex` per
host, never removed), and `SourceCookieJar.byHost` (which drops `Max-Age`/`Expires` at parse
time, so **nothing ever expires** — its KDoc acknowledges this).

### 5.7 Reconsider the per-source mutex in `WasmSourceLoader`

`callModule` (`:219-226`) takes a per-source `Mutex`, serializing all calls to one source. Its
KDoc names the detail screen's parallel details+chapters fetch as the motivating case. Once 1.3
reduces how often that happens, it's worth checking whether the serialization is still needed for
correctness (WASM instance reentrancy) or whether a small pool of instances would let browse and
reader calls proceed concurrently. **Investigate before changing** — this guard may be
load-bearing for memory safety in the wasm runtime.

---

## Explicitly *not* problems

Worth recording so they don't get re-audited:

- **FK indices are complete.** Every FK child column is a PK, has a dedicated index, or is the
  leftmost column of a composite. No missing-index warnings expected.
- **`HistoryDao.observeLibraryHistory`** (`:91-109`) pushes filter, one-per-series collapse,
  ordering, cap and joins all into SQL. Home's continue-reading is **not** an N+1. (Its `LIMIT 15`
  applies after grouping the whole table, so it does scale with history size — item 2.3 covers that.)
- **`ObserveLibrary`'s 5-arity combine** is deliberate and correct: knobs are `Flow`s so the Room
  subscription happens once and search/sort/filter are re-derived in memory.
- **No `Activity`/`Context` leaks.** `AndroidBindings.kt:17` binds `Context` = `Application`, and
  every long-lived consumer takes that. Listeners are disposed correctly
  (`SiteSessionWebView.android.kt:94-97`, `ReducedMotion.ios.kt:28`, `SiteSessionHost.kt:129`).
- **Search-as-you-type is debounced** (`SourceBrowseViewModel.kt:165`, 320 ms, inside
  `collectLatest`).
- **Downloads wifi-only enforcement is solid** on both platforms — `ObserveDownloadHold` checks
  before every chapter *and* every page, plus `NetworkType.UNMETERED` on the Android worker.
- **The reader's decode sizing is correct** and is the most important thing in that subsystem —
  see the note under Phase 4.

---

## Verification

Per item, before moving to the next:

```bash
./gradlew :androidApp:assembleDebug                 # Android stays green
./gradlew :core:domain:testAndroidHostTest \
          :core:data:testAndroidHostTest \
          :features:manga:testAndroidHostTest       # unit tests
./gradlew :core:domain:iosSimulatorArm64Test        # iOS target stays green
```

CLAUDE.md warns: never pipe gradle through `tail` or use `-q` when checking results, and check the
embedded `Shared.framework` mtime before believing an iOS-only bug.

**Schema changes (1.1, 3.4):** a migration test is mandatory — Room's `MigrationTestHelper` against
the checked-in `10.json` → `11.json`. Verify an existing install upgrades without data loss.

**Phase 1 behavioral check:** add logging (or a debug counter) around `SourceLoader.fetchChapterList`
and `fetchMangaDetails`, then open the same series 5× in a row. Before: 10 calls. After 1.3: 2.
Then run a library update twice within the interval and confirm the second run skips nearly
everything.

**Phase 2/3 check:** the honest measure is a Room query counter or the Android Studio profiler's
allocation tracker. Open a chapter (writes history) and count how many `manga` queries fire —
before 2.1 it should be one per distinct series in the entire history.

**Phase 4 check:** read 20+ chapters continuously and watch heap growth. `pagesCache.size` should
stay at 3, not climb to 20.

**Phase 5.2 check:** the decisive test is a source whose CDN sends a short `max-age`. Load a
library screen, force-kill, relaunch offline — covers should still render from disk.

---

## See also

- `docs/backlog-observability.md` — logging + Sentry. Item 4.1 here is the likely cause of the
  `WatchdogTermination` recorded there.
