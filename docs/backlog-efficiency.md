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

### 1.1 Add sync bookkeeping columns to `manga` (schema v11) — **DONE**

Shipped as `Migration10To11` — four nullable columns on `manga` (`chapters_fetched_at`,
`details_fetched_at`, `next_update_at`, `fetch_interval`), carried onto `MangaEntity` and the domain
`Manga`, and stamped only through the targeted `MangaDao.markChaptersFetched` / `markDetailsFetched`
queries. Nothing writes `next_update_at` or `fetch_interval` yet; they are here so 1.2 costs no second
migration.

**What this forced, and is the more interesting half of the change:** stamping a column on `manga` is
worthless while anything still writes the *whole* row from a snapshot. `SyncSourceMangaDetails` did
exactly that, and the detail screen runs it concurrently with the chapter sync — so a stamp landing
mid-request would have been reverted about half the time, on the one screen the gate exists for. Both
remaining read-modify-write paths are now targeted column writes: `SyncSourceMangaDetails` goes through
`MangaRepository.setSourceDetails` (the seven columns a details sync owns) and
`CacheSourceManga.refreshCoverHeaders` through `setCoverHeaders`. That also closes a pre-existing bug
the `MangaDao` KDoc had already predicted: a details sync landing while the user flipped reading mode
reverted it.

**No migration test**, in keeping with the other ten: the repo has no instrumented/host test source set
for `:core:data` and no `room-testing` artifact, and standing that up is its own piece of work. What
protects it is the exported schema — Room checks the identity hash on open — plus four `ADD COLUMN`s
being the lowest-risk migration shape there is. Verified by hand across an upgrade.

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

### 1.2 `FetchInterval` use case + per-manga skipping in `UpdateLibrary` — **DONE**

Shipped as `core/domain/.../manga/service/FetchInterval.kt` and a per-series filter in
`UpdateLibrary.Default.run()`. The `Semaphore(MAX_CONCURRENT_SOURCES)` grouping is untouched;
`Trigger.Manual` bypasses the schedule exactly as it bypasses `isDue()`, and a `nextUpdateAt` of `null`
reads as "consider it", which is what makes the first run after the upgrade behave identically to every
run before it. `COMPLETED`/`CANCELLED` start at the 28-day floor.

**Where it lives, and why that is the efficiency of the change:** a **pure function in
`manga/service/`** (alongside `chapter/service/MissingChapters`), taking the chapter list, and applied
by **`SyncSourceChapters`** rather than by the run that called it. The obvious shape — a `FetchInterval`
use case with a `ChapterRepository`, rescheduling from `UpdateLibrary.refresh` — re-reads and
re-materializes every chapter of every series, on every run, to recompute two aggregates the sync had
in hand a call earlier. On a thousand-chapter series that is a thousand `Chapter`/`Text`/`Url` objects
rebuilt for a median and a max. The schedule also rides along on the existing stamp:
`markChaptersFetched` became `recordChapterSync`, writing `chapters_fetched_at`, `next_update_at` and
`fetch_interval` in **one** `UPDATE`. A second statement would not have cost a second write, it would
have cost a second full re-run of the library and detail queries (both with correlated sub-queries over
`chapters`) per series per run — the exact cost 3.3 exists to avoid. Applying it in the sync also keeps
a series the user reads by hand as well-scheduled as one the run touches.

The day arithmetic works on **epoch-day `Int`s in an `IntArray`**, in the style of
`missingChaptersCount`, not on `LocalDate`s in a list: `Instant.epochDayIn(zone)` resolves the offset
and floor-divides, where `toLocalDateTime(zone).date` resolves the same offset and then decomposes it
into a year/month/day and allocates two objects the algorithm never reads. Gaps are subtraction, the
sort is a primitive sort, and the fetch dates are only scanned when the upload dates came up short. The
careful `LocalDate.atStartOfDayIn` conversion is kept for the two-or-three day numbers that become
timestamps again — the first instant of a day is not always midnight, and Brazil, one of the two
shipped locales, used to start DST at midnight.

Skipping is **unconditional** for `Foreground`/`Background`, not behind a preference — Mihon makes it
the opt-in `MANGA_OUTSIDE_RELEASE_PERIOD` restriction, but a setting whose off position is "waste most
of your requests" is not a choice worth surfacing, and the escape hatch already exists in the pull-to-
refresh of 1.4 and in `Trigger.Manual`.

**The one real porting gap:** Mihon anchors `nextUpdate` on `manga.last_update`, *"last time the
chapter list changed at all"*. There is no such column here, and `chapters_fetched_at` is the wrong
thing — it is the last **poll**, so anchoring on it would push the next check forward on every run and
no series could ever decay. `max(chapters.date_fetch)` is the same quantity for free:
`SyncSourceChapters.merge` stamps `dateFetch` on new chapters only and leaves existing ones alone. No
fifth column, no migration.

**Deliberate deviation from the reference:** Mihon's `calculateNextUpdate` divides `timeSinceLatest` by
the *increased* step but then adds multiples of the *base* interval, so a stale series lands a next
check in the past and comes due sooner — the opposite of what the doubling exists for. The increased
step is used on both sides here. The stored `fetch_interval` stays the derived cadence rather than the
doubled step, so a series that revives is back on its real schedule the moment one chapter lands.

Covered by a new `FetchIntervalTest` (23 cases, all against a fixed `TimeZone.UTC` and fixed instants
now that no repository is involved: the median arithmetic against a fixed `TimeZone.UTC` —
distinct *days* not timestamps, the upload→fetch-date fallback, the 3-vs-10 sample window, both clamps
— plus the scheduling rules) and eight new `UpdateLibraryTest` cases (a not-due series is left alone, a
manual run ignores the schedule, a run that skipped everything still stamps the clock, a pinned interval
survives) plus three in `SyncSourceChaptersTest` pinning that the schedule shares the stamp's single
write, that a failed sync writes neither, and that a TTL-suppressed one writes neither.

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

### 1.3 TTL-gate the detail screen's two refreshes — **DONE**

Shipped as a `force: Boolean = false` parameter on both `SyncSourceChapters.invoke` and
`SyncSourceMangaDetails.invoke`, with the guard as the first thing after the manga row is resolved and
ahead of every side effect — so a suppressed chapter sync writes no chapters, records no
`chapter_updates` and queues no auto-downloads. TTLs are `CHAPTERS_TTL = 30.minutes` and
`DETAILS_TTL = 24.hours`, private to each use case. The stamp is written **last and only on success**,
so a failed refresh retries on the next open instead of being suppressed for the whole window; a
`null` stamp reads as "ask now", which is what makes the upgrade a no-op behaviourally.

`UpdateLibrary.refresh` passes `force = true` — it schedules series itself (and `Trigger.Manual` means
*all* of it), so it must not be gated twice. `MangaViewModel` takes the default.

Covered by the new staleness matrix in `SyncSourceChaptersTest`, a new `SyncSourceMangaDetailsTest`
(which also pins the previously untested blank-field and cover-`lastModified` rules), and a
`UpdateLibraryTest` case proving a library run still refreshes a series the detail screen synced
seconds ago.

**Accepted cost:** a stamp is a write to `manga`, so it invalidates `observeManga`/`observeLibrary` and
the 3.3 dedupe cannot absorb it — the row really did change. That is one extra emission per TTL per
series, against the fetches removed, and a sync that finds chapters invalidates those queries anyway.

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

### 1.4 Pull-to-refresh on the detail screen — **DONE**

Shipped as a `PullToRefreshBox` around `MangaContentView` and a `MangaViewModel.onRefresh()` that runs both
syncs with `force = true` — the only caller in the app that passes `force` from a user gesture, which is
what keeps 1.3's TTLs affordable.

**What the collapsing hero forced:** the pull gesture and the hero share one nested-scroll chain. Leftover
downward drag reaches the hero's connection (`onPostScroll`, nearest ancestor first) before it reaches the
refresh box, so an over-scroll re-expands the hero and only then starts a refresh — the right order, since
the hero is what the user is pulling back into view, and it costs nothing to arrange: it falls out of
leaving the existing connection where it is and wrapping *outside* it. The indicator is
`statusBarsPadding()`-ed because the hero runs edge-to-edge under the status bar and an indicator placed
from the top of the box would travel out of sight.

**Only the chapter sync's failure is surfaced.** The details sync runs concurrently (`async`, as on open —
different endpoints, and a slow synopsis must not hold the spinner over an already-updated chapter list),
but a source that doesn't implement `fetchMangaDetails` throws every time, and reporting that would put a
permanent error under every pull on that source. The chapter list is what the gesture is *for*; the details
sync stays the best-effort fill-in it is on open, logged and silent.

`MangaState.Content` gained a `refresh: Refresh(inProgress, failed)` folded in through a fourth `.combine`
— two flags, not a sealed state, because pulling again while the last message is still up is both. The
`inProgress` flag is deliberately **not** set by the two on-open refreshes: those are speculative and
usually suppressed by 1.3's gate, so a spinner for them would spin over a screen that is not fetching
anything. The failure shows as `MangaRefreshError`, a strip under the top bar with a retry, self-clearing
after six seconds — the bottom strip a snackbar would want belongs to the Read action, and the app has no
snackbar host (nor does the design system have a toast component: `components/feedback/` is `Sheet` only).

**No tests:** `:features:*` has no test source set at all (see Verification), so this is covered by the two
compile targets and by hand. New strings in both `values/` and `values-pt-rBR/`.

**Problem:** now that 1.3 has landed, a TTL-suppressed refresh leaves the user with no way to force
one — this is the next item, and the `force = true` path it needs already exists.
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

### 2.2 Fix the `ObserveNewChapters` staleness bug — **DONE**

Shipped as observable twins of the 2.1 batch reads — `MangaRepository.observeMangaByIds` /
`ChapterRepository.observeChaptersByIds`, chunked by a new `observeInBatches` beside `readInBatches` —
plus a second entry point on the resolver: `invoke` still answers once (for `NotifyNewChapters`),
`observe` keeps answering, and both project through one private pure `resolve`, so the list and the
notification still cannot drift. `ObserveNewChapters` is now
`observeUpdates().distinctUntilChangedBy { ids }.flatMapLatest(resolve::observe)`. The doc took the
"make the claim true" branch rather than the "correct the doc" one.

**The subtlety that decided the gate, and the one thing this cost:** the obvious
`.distinctUntilChanged()` on the records is *wrong here*. `ChapterUpdate` carries `notified`, which
`NotifyNewChapters` flips after **every** library run — a genuine change to the record list that the
resolver cannot see, since it never reads that field. Under `flatMapLatest` a plain dedupe would let
each of those writes tear down and rebuild both queries for a byte-identical answer, at the moment Home
is most likely on screen. Keying on the chapter ids is exact rather than approximate: a row is immutable
apart from `notified` (`insertIgnoring` never rewrites one), and the query's `ORDER BY discovered_at
DESC` means the id *sequence* carries any reordering too. With that gate, resubscription happens exactly
when the record set changes — strictly rarer than the unconditional re-resolve the old code did on the
same signal, which is why there is deliberately **no** `ObserveDownloadQueue`-style memoized resolve.

`ChapterDao.observeChaptersByIds` gained an `ORDER BY chapters.id` its suspend twin doesn't need. A live
read is compared against its own previous emission by `distinctUntilChanged`, and an unordered `IN` lets
SQLite return the same rows in a different order — the dedupe would have been silently useless.
(`MangaDao`'s `GROUP BY manga.id` already pins that side.) Both new repository reads dedupe before the
mapper, per 3.3; `ChapterRepositoryImp`'s is the only deduped read in that class, because it is the only
one backing something longer-lived than a screen.

`observeInBatches` short-circuits the empty case to `flowOf(emptyList())` rather than leaving it to
`combine`, which over zero flows never emits — a collector would hang rather than be told there is
nothing. That is load-bearing, not defensive, and `ResolveChapterUpdates.observe` short-circuits for the
same reason one level up.

**Accepted cost:** Home now holds two live Room subscriptions, one of them the six-table
`MangaWithDetails` projection, and `HomeViewModel` is contributed to `AppScope` with a
`launchIn(viewModelScope)` pipeline — so they are always-on **until 3.1 lands**. The repository dedupe
keeps the downstream cost at zero, but the queries still re-run; 3.1 has one more reason to happen.

Covered by a new `ObserveNewChaptersTest` (7 cases, `MutableStateFlow`-backed fakes since a `flowOf`
fake cannot express "and then it changed"): reading a chapter, hiding a group, unfollowing a series and
renaming one each correct the list **with no write to `chapter_updates`**, a new record still resolves,
an emptied list settles on nothing, and `notified` flipping does not resubscribe. Five of the seven were
confirmed to fail against the old implementation before the fix was kept. Plus four `BatchReadsTest`
cases counting *subscriptions* the way the existing ones count reads, one `MangaRepositoryImpTest` case
for the by-ids dedupe, and one in `ResolveChapterUpdatesTest` pinning that `observe` and `invoke` agree.

**Problem:** the KDoc on `ObserveNewChapters` claims it "re-emits whenever the records change
**or** the underlying manga/chapters do." It does not — `ResolveChapterUpdates` takes snapshots
with `.first()` and unsubscribes, so only writes to `chapter_updates` retrigger it. Home's "New
Chapters" list can show a stale title or a chapter that's since been read.

**Fix:** either correct the doc, or (better, and near-free once 2.1 lands) make the resolver
depend on the batch query as a real `Flow` so the claim becomes true.

### 2.3 Bound the history table — **DONE**

Shipped as **one SQL predicate, one pure function and one launch-time sweep**: `PruneHistory`
(`core/domain/.../history/usecase/`) runs from its own `LaunchedEffect` in `App.kt` and calls
`HistoryDao.pruneHistoryBefore`, which deletes rows older than a cutoff **except each series' most
recent read**. No schema change, no migration — the two indices it needs (`index_history_read_at`,
`index_history_manga_id`) already exist. Retention, not `LIMIT`, exactly as this item argued.

**The exemption is the whole design, and it comes from two couplings this item didn't name.** Two
queries read the *whole, date-unfiltered* history to answer "when did this series last get read":
`MangaDao` derives `last_read` as `MAX(history.read_at)` in all four of its projections — including
`observeLibrary`, which backs sort-by-last-read — and `observeLibraryHistory`'s subquery groups the same
unfiltered table to build Home's continue-reading shortlist (its KDoc already called that un-filtering
load-bearing). A plain age cutoff would therefore not have made an old series *less detailed*; it would
have made it look **unread**, and dropped it off Home. Keeping the max row makes both exact rather than
approximately preserved, costs at most one row per series ever read, and as a bonus keeps
`ReadingStats.seriesCount` exact forever. It is phrased as *older than this series' own newest* rather
than by id, which keeps the correlated subquery to a single `MAX` and avoids the bare-column rule
`observeLibraryHistory` depends on.

**The window is derived, not chosen — which is what makes the sweep provably lossless.** Every
period-scoped query binds a lower bound, and the oldest any of them can ask for is
`TimePeriod.YEAR.previousStartInstant()` — Jan 1 of *last* year, the baseline `GetMostRead` compares this
year against. `historyRetentionCutoff` (`history/model/HistoryRetention.kt`) anchors a further calendar
year back, so it sits at or below that bound on every date of every year, leap years and Dec 31 included:
`GetMostRead`, `GetReadingActivity` and `GetHistory` **cannot** lose a row they would have read. In
practice that keeps 24–36 months. A flat twelve months was rejected because it sits *above* the YEAR
baseline for most of the year — that would not have made the Year view coarser, it would have made its
trend arrows wrong. It is computed as a calendar date through `atStartOfDayIn`, not by subtracting a
`Duration`, for the reason 1.2 already recorded about DST.

**Ungated, on every launch, and that is cheaper than a gate.** Room invalidates from per-row triggers, so
a DELETE matching nothing fires none and re-runs no query — until an install is two calendar years old
this costs one indexed scan and invalidates nothing. A `last_history_prune` preference in the shape of
`last_library_update` would have been state to maintain in exchange for that. It is deliberately **not**
hung off `upsertHistory`: `UpdateReadingProgress` already records why for its own sweep — that path runs
on every page turn, and a database walk between pages is exactly what must not happen.

**`GetAllHistory` was deleted rather than bounded.** It was the third `DISTANT_PAST` reader this item
names and it was dead — a `sealed interface` whose `Default` carried no `@Inject` and no
`@ContributesBinding`, with zero references outside its own file.

**Accepted cost, since it is user-visible.** `features/activity/.../values/strings.xml`'s
`activity_all_time` ("All time" / "Todo o período") cues the distribution donuts, and `totalChapters` and
the donut proportions now describe the retained window rather than all time. Deliberately **not**
reworded: `seriesCount` stays exact, `dayStreak` walks back from today and stops at the first gap so only
a streak longer than the window could be truncated, and the honest alternative is a dynamic "since 2024"
label — worse copy, in two languages, for a case no install reaches for two years. `ObserveReadingStats`'
KDoc now divides its four aggregates into the ones retention touches and the ones it doesn't, rather than
claiming all-time for all of them.

**How the load-bearing half was actually verified, since no Kotlin test can reach it.** The
keep-each-series'-latest rule lives in the SQL predicate, and `:core:data` still has no Room test harness
(no `room-testing`, no instrumented source set — the same reason all eleven migrations ship untested). So
the exact generated statement was run against a SQLite fixture covering the cases that matter: an old
non-newest row, an old newest row, a single-row series entirely below the cutoff, a tie for a series' max,
and a row exactly *at* the cutoff. Three of nine rows deleted, all five series still present, and
`MAX(read_at)` per series **unchanged for every one of them** — which is the property `last_read` and
Home's shortlist both depend on.

Covered by 9 new tests: `HistoryRetentionTest` (5 — the cutoff's date and zone, that the calendar year is
the zone's and not UTC's, that two calendar years is the narrowest the window gets, and the load-bearing
sweep across Jan 1 / mid-year / Dec 31 / a leap day in two zones asserting the cutoff never rises above
`YEAR.previousStartInstant()`) and `PruneHistoryTest` (4 — the derived cutoff is what reaches the
repository, the count is returned, it is logged, and a zero result logs *nothing*). 380 tests across
`:core:domain` + `:core:data`, all green; Android builds.

**Not done here:** `observeLibraryHistory` still groups the whole table before its `LIMIT` — retention
shrinks that grouping but does not remove it, and the note under "Explicitly *not* problems" has been
updated to say so rather than keep deferring it here. And `ObserveReadHistory` still materializes every
retained row for the Activity History tab; a `LIMIT` there is the remaining half of this item's original
"either/or", now genuinely optional.

**Problem:** `HistoryDao.observeHistorySince(since)` is called with `DISTANT_PAST` at every call
site — an unbounded `SELECT * FROM history`. Nothing ever prunes `history`; `HistoryDao` has
`clear()` and `deleteForManga` but no retention policy, so this grows forever.

**Fix:** either a retention sweep (drop rows older than N months, or cap at N rows per manga) or
push a `LIMIT` into the callers that only need recent activity. `ObserveReadingStats` genuinely
needs the whole table — that one argues for retention rather than `LIMIT`.

---

## Phase 3 — Memory: stop holding the whole library

### 3.1 Route screen state through `TanukiViewModel.asState` — **DONE**

Shipped as a second tier rather than a per-ViewModel edit: `StateViewModel<S>` (`:core:presentation`)
extends `TanukiViewModel` and owns `val uiState: StateFlow<S> by lazy { state.asState(initialState) }`
over an abstract `protected val state: Flow<S>`. Ten ViewModels now extend it — the four that already
called `asState` are re-parented unchanged, and `LibraryViewModel`, `HomeViewModel`, `ActivityViewModel`
and `MangaViewModel` moved onto it. The three single-value settings ViewModels (`theme`, `categories`,
`crashReportingEnabled`) have no screen state and stay on the base.

**Why a base class rather than ten local edits.** The two saved lines are not the point: `uiState` on
`StateViewModel` has no setter and no backing field a subclass can see, so **`uiState.value = …` stops
compiling**. The pattern this item exists to delete becomes unrepresentable rather than discouraged, and
a ViewModel still declaring its own `MutableStateFlow` is now *saying* something — at the time, that its
state genuinely isn't derived. (That marker was refined on 2026-09-09: it now means *either* that, *or*
that `asState`'s dispatcher/ordering/lifetime contract doesn't fit — see the note at the end of this
item.) `by lazy` is what makes it work at all: `state` is abstract, so it is not
initialized while the base constructor runs and cannot be read from there. Deferring to first access is
also when the pipeline *should* be built.

`TanukiViewModel`'s KDoc predicted this shape ("a second tier — a `StateViewModel<S>` extending this")
and has been rewritten; its argument against hoisting was about a type parameter on *itself*, which
would still be wrong.

**The one real refactor was `LibraryViewModel`**, which had three faults that turned out to be one:
its combine lambda read `uiState.value` to decide which state to emit (a pipeline that is a function of
its own output), `onFilterClick`/`toLibraryState` wrote over that output and `require`d its shape, and
`latestCategories` was a plain `var` written inside the lambda on `dispatcher.default` and read from a
main-thread tap — **item 3.5's data race**. All three are gone: the sheet is now a `sheetOpen` knob
flow, and its chips come straight from the live `ObserveCategories` arm, so there is no snapshot left to
race on and the sheet cannot open on a stale category set. `filter`/`sort`/`searchQuery` became real
combine arms too (bundled into one `Knobs` value, since the typed overloads stop at five) — they were
read as `.value` from inside the lambda, so a library-only emission could pair a fresh list with knobs
sampled at some other instant. `onFilterClick` now clears the search *for real*, which is what its own
KDoc always claimed; `toLibraryState` used to fake an empty query into the closed state and the next
emission silently contradicted it. `LibraryState` is unchanged, so no screen was touched.

`HomeViewModel` lost its `sources` mirror — a second always-on `launchIn` whose only job was to keep
"not enumerated yet" distinguishable from "enumerated, and there are none". **Nothing replaced it:**
`combine` emits only once every arm has, and `GetInstalledSources` is silent until the bundles are read
(a directory listing, then a `SOURCE_META` decode per bundle — not a value that starts empty), so the
combine *is* the gate. The `null` sentinel only ever existed because the mirror was a
`MutableStateFlow<List<SourceMeta>?>(null)`, and a StateFlow always has a value: it emitted immediately,
so something had to mark "not yet". Combining the flow directly removes the need, and the first shape
shipped here carried the sentinel across out of habit before it was dropped. The restart the gate
implies is proved harmless by test rather than by argument: the pipeline re-emits nothing until its arms
answer again, and `stateIn` keeps sharing the last value, so a tab round trip resumes on the sources
already on screen.

`MangaViewModel` kept `init { refreshChapters(); refreshDetails() }`. Those must run because the screen
was *opened*, not because something is collecting it — the one part of that ViewModel that is
deliberately not subscription-scoped.

**What made this verifiable: the first tests in `:features:*`.** Every feature module already declared a
`commonTest` block and not one had a file in it, because the domain's use cases are `sealed interface`s
and a sealed interface admits implementations only in its own module *and* package — nothing in
`:features:library` could fake `ObserveLibrary`. The ten use cases the three tested ViewModels take are
now plain interfaces (one keyword each; `@ContributesBinding Default` and every call site unaffected).
16 new tests: `StateViewModelTest` in `:core:presentation` (the pipeline is not collected before the
first subscriber, it stops one timeout after the last, a subscriber returning inside the window resumes
the same collection, and the last value survives a stop), plus `LibraryViewModelTest` (7),
`HomeViewModelTest` (5) and `ActivityViewModelTest` (4). Two of those count *subscriptions* rather than
values, which is the only way to tell a genuine teardown from a resubscribe inside the sharing window —
and is the assertion this whole item is about. `MangaViewModel` takes ~12 more use cases and ships
migrated but untested. The shared fakes in `:core:presentation` moved to an `internal` `TestDoubles.kt`,
since Kotlin/Native rejects two identically-named private top-level declarations in one package.

**Still imperative, and deliberately left:** `ReaderViewModel` (three out-of-pipeline writes, ordered
side effects that must run *after* the state is published, and a second warm pipeline) and
`SourceBrowseViewModel` (eight imperative writes and no derived pipeline at all — a cursor-driven paging
machine). Both are route-scoped, so neither is part of the always-on cost this item was about; each is a
rewrite rather than a migration and deserves its own diff.

> **Superseded for the reader (2026-09-09).** Its three out-of-pipeline writes are gone — `Bootstrap`
> made `Loading`/`Error` derived, so the pipeline's `onEach` is the only writer of `uiState` (see the
> reader entry under "Explicitly *not* problems"). It still doesn't extend `StateViewModel`, but the
> reason is now `asState`'s contract rather than its state shape: the `flowOn(default)` hop versus the
> re-anchor bookkeeping, commands that must be computed after publication, and `WhileSubscribed`
> reseeding a stateful `scan` on a screen that never leaves the foreground. `SourceBrowseViewModel`'s
> half of this note stands unchanged.

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

**Correction found while implementing:** this item claimed `MangaViewModel` "pushes imperative state
updates". It does not — it had **zero** out-of-pipeline `uiState` writes, all of its imperative state
already living in four side `MutableStateFlow`s. `DownloadsViewModel` had already been migrated too.

**Constraint from CLAUDE.md:** `uiState` uses Kotlin's explicit backing field
(`val uiState: StateFlow<S>` / `field = MutableStateFlow(...)`). *Superseded:* that constraint was
relaxed deliberately — see the `StateViewModel` note above, and CLAUDE.md, which now records the second
tier.

### 3.2 Narrow the library projection — **DONE**

Shipped as **two** new domain row types and **three** narrowed reads, replacing one wide query and
deleting another. `MangaDao` wrote the same `manga.*` + `last_read` + `date_added` + `reading_status` +
two correlated sub-queries projection five times; it now writes it three times, for the three callers
that genuinely want a whole `Manga` (`observeManga`, `getMangaByIds`, `observeMangaByIds`).

| read | now returns | what its callers read |
|---|---|---|
| `observeLibrary()` | `LibraryManga` (9 fields) | the grid, and `ObserveLibrary`'s filter/search/sort |
| `getLibraryUpdateTargets()` *(new)* | `LibraryUpdateTarget` (4 scalars) | `UpdateLibrary.run` |
| `getMangaIdsBySource()` *(replaces `observeMangaBySource`)* | `List<Uuid>` | `GetSourceDownloadUsage` |

**The item's suggested name was taken.** `LibraryEntry` is already
`app.tanuki.core.data.repository.library.LibraryEntryEntity`, the `library` table row — two
`LibraryEntry`s meaning different things one module apart is worse than a rename, so the grid's type is
**`LibraryManga`** (Mihon's name for the same thing).

**Narrowing only the grid would not have removed the wide query, which is why all three moved.**
`UpdateLibrary` and `GetSourceDownloadUsage` were the other two callers, and either one left on
`observeLibrary`/`observeMangaBySource` would have kept `manga.*` alive on the library path. Both turned
out to want dramatically less than the grid does, not more: an update run reads a schedule, a source to
group by and a title to log, and `GetSourceDownloadUsage` — the *only* consumer of
`observeMangaBySource` — built a full domain object per row from three joins and two correlated
sub-queries in order to keep the primary key. Both are now one-shots over flat scans with **no** joins
and **no** sub-queries at all, which is a larger saving than the grid's and was invisible until the grid
forced the reads apart. `observeMangaBySource` is gone entirely rather than narrowed; nothing observed it.

**What the row shape buys, and what it does not.** The bytes were never the point on their own — what
`MangaEntity.toDomain` did per row per emission was: wrap `description` (unbounded prose) and `author` in
`Text`, parse a **second** `Url` for the permalink (`Url`'s `init` parses and `require`s protocol+host),
split `filtered_scanlators`, and box `sourceMangaKey`. On a library query invalidated from six tables —
`ChapterDownloadDao.setProgress` alone fires once per downloaded *page* — that ran per title per page of
every download. `filtered_scanlators` is still *referenced*, inside `unread_count`, and still not
projected: the badge wants the filter's result, not the filter. The cover's `Url` parse stays, because
`cover` stays a whole `MangaCover`: its own KDoc names the library grid as a reason headers travel with
it, `MangaCoverTile`'s call shape is unchanged, and keeping `cover_last_modified` on the row leaves 5.3
open rather than deciding it by omission.

**Two fields became non-null, which is the projection telling the truth.** `observeLibrary` INNER JOINs
`library`, so `date_added` and `reading_status` are always present; `MangaWithDetails` types them nullable
only because the *other* four queries LEFT JOIN. `applySort`'s `compareBy(nullsFirst(), ::dateAdded)`
became a plain `compareBy` — the null ordering was for a case this read cannot produce.

**`MangaWithDetails` is untouched, deliberately.** `HistoryDao.observeLibraryHistory` embeds it with
`prefix = "manga_"`, so narrowing it in place would have silently narrowed a second query. The new rows
are flat classes rather than `@Embedded MangaEntity` + extras for the same reason in reverse: an embed
puts every column back in the projection by construction, so naming the eleven columns is what makes a
future widening show up in a diff. In `MangaRepositoryImp` the two `List<X>.toDomainList()` helpers erase
to one JVM signature, so the library's is `toLibraryMangaList`.

**No new test, and that is the honest answer.** The property this item buys — the grid *cannot reach* a
description — is enforced by the type, and neither `:core:data` nor `:core:domain` has Room
instrumentation that could count a query's columns. What did happen to the suite: `GetLibraryTest` and
`LibraryViewModelTest` build nine-field `LibraryManga`s instead of full manga, `UpdateLibraryTest`'s fake
serves `LibraryUpdateTarget`s (its world still holds whole manga, because the chapter sync underneath it
needs them) and its eight scheduling cases from 1.2 assert exactly what they did, and
`MangaRepositoryImpTest`'s library cases moved onto `LibraryMangaRow`. Its "an identical by-source
re-emission is not passed on" case was **deleted**: the observed read it covered no longer exists, and a
one-shot has nothing to dedupe. 378 tests across the three modules, all green; both platforms build.

**Makes 3.4 the natural next item** — `ORDER BY manga.title` on a narrowed row is a cheaper sort but
still an unindexed one, and `getLibraryUpdateTargets` dropped its `ORDER BY` outright (the caller
regroups by source immediately).

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

### 3.3 `distinctUntilChanged()` after `observeLibrary()` — **DONE**

Shipped on **all three** of `MangaRepositoryImp`'s observed reads — `observeLibrary`,
`observeMangaBySource` (browse) and `observeManga` (the detail screen, which sits under the reader and
so is re-run by every `reading_progress` write) — since they share the same six-table invalidation set.
The operator sits **before** the `.map(...)`, not after, so a duplicate costs only the comparison: the
per-row `Manga`/`Text`/`MangaCover` allocation, `ObserveLibrary`'s three list copies and the
recomposition behind them are all skipped. `MangaWithDetails` and its embedded `MangaEntity` are data
classes of scalars, so `equals` is structural, and the compare runs inside the existing `flowOn(io)`.

`CategoryRepositoryImp` now reads `manga_categories` through **one** `private val mangaCategories`
flow, deduped once and shared by both `observeCategories` and `observeLibraryCategories` — so an
unchanged join table costs neither regrouping.

**Deviation:** that shared flow is deliberately **not** `shareIn`-ed, so the two readers still open two
Room observers when both are collected. Sharing the subscription would mean injecting a
`CoroutineScope` into `:core:data` — the module has none today — to save two observers on a tiny join
table, while the cost the item actually named (re-grouping in Kotlin on every change) is gone either
way.

Covered by `MangaRepositoryImpTest` / `CategoryRepositoryImpTest` in `core/data/src/commonTest`; both
fakes emit from a `MutableSharedFlow` rather than a `MutableStateFlow`, because a state flow conflates
equal values itself and would make the tests pass with the dedupe removed.

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

`observeLibrary()` does `ORDER BY manga.title` with no index — a full sort on every emission.
Minor at 200 titles, real at 2000+. Folds naturally into whichever migration comes next.

*(Updated after 3.2: this used to name `observeMangaBySource()` too. That query is gone — its one
caller wanted ids — and `getLibraryUpdateTargets()` deliberately has no `ORDER BY`, so `observeLibrary`
is now the only unindexed sort. 3.2 made each emission cheaper to sort, not cheaper to order.)

> **Correction, measured while picking up 5.3 (2026-09-11) — do not ship this item as written.** Adding
> `index_manga_title` changes **nothing**. Checked with `EXPLAIN QUERY PLAN` against a fixture built
> from the checked-in `11.json`: with and without the index the plan is byte-identical, still
> `SCAN manga USING INDEX sqlite_autoindex_manga_1` followed by `USE TEMP B-TREE FOR ORDER BY`. The
> cause is `GROUP BY manga.id` — SQLite drives from the primary-key index to satisfy the grouping, and
> one index cannot serve both the grouping and the ordering. The index is only picked up if the
> `LEFT JOIN history` + `GROUP BY` is first replaced by a correlated
> `(SELECT MAX(read_at) FROM history WHERE history.manga_id = manga.id)`, which removes the `GROUP BY`
> outright; then the temp B-tree disappears.
>
> Even then the payoff is close to nil: at 2000 titles with 20 chapters each, all four combinations
> measured **6.8–7.5 ms**, within noise of each other. Sorting 2000 short strings is not the cost of
> this query — the two correlated sub-queries are. So this item is really "remove the `GROUP BY`", its
> value is the plan rather than the clock, and 5.3's v12 migration deliberately did **not** fold in an
> index that nothing would have used.

### 3.5 Fix the `LibraryViewModel.latestCategories` data race — **DONE (with 3.1)**

Closed by the Library refactor in 3.1 rather than fixed on its own: the `var` is deleted, not
synchronized. The sheet's chips are now derived from the live `ObserveCategories` combine arm, so there
is no cached snapshot for a main-thread tap to read while `dispatcher.default` writes it — and as a
bonus the sheet can no longer open on a category set that has since changed.

**Problem:** `LibraryViewModel.kt:72` is a `var` mutated from inside the `combine` block (running on
`dispatcher.default`) and read from `onFilterClick()` on the main thread. Not a leak, but an
unsynchronized cross-thread read. Fold it into the state or make it a `StateFlow`.

## Phase 4 — Reader memory

### 4.1 Evict `ReaderViewModel.pagesCache` — **DONE**

Shipped as `ReaderWindow` (`features/reader/.../viewmodel/ReaderWindow.kt`), a pure value holding
`(index, pages, loading)` that replaces three of the ViewModel's five window flows, plus a third pipeline
in `init` that evicts. Radius 1, so the window really is previous · current · next.

**Why one flow, and it is not the reason you would guess.** The dangerous desync is *cache smaller than
the index implies*: evicting against a stale index can drop the chapter the viewport is standing in, and
`buildItems` returns `emptyList()`, which `ReaderContent` renders as an empty state **mid-read**. The
other direction — an index that has moved but a cache not yet trimmed — is a valid, contiguous, slightly
larger list, which is literally today's steady state and harmless. Holding both in one value makes only
the first unrepresentable, and that is the whole argument for the container.

**The correction that shaped everything else: `currentIndex` moves mid-gesture, not on settle.** A pager's
`currentPage` flips once a drag passes the snap halfway point, and a strip's `firstVisibleItemIndex`
changes throughout a fling — both go straight to `onItemFocused` through a `snapshotFlow`. So `movedTo`
and `evicted` are **separate transitions** and eviction is debounced (`EVICT_QUIET_MS = 750`) on a quiet
index. Evicting on the move would pull a chapter's items out from above a viewport with a finger still on
it, and the `Reanchor` that followed would take the scroll mutex and hard-cancel the settle. Debouncing
also answers the thrash question for free: crossing a boundary back and forth inside the quiet period
never reaches the eviction pipeline, so it never drops what it is about to need again. That is why the
radius is a strict ±1 and **not** an LRU cap — under sequential reading "±1" and "keep the 3 nearest" are
the same set, and a recency cap cannot guarantee the contiguity `buildItems` requires (jump 3 → 40 → 4 and
the disconnected entries are silently ignored, so they are pure waste).

**`buildItems` needed no change.** Its outward `lo`/`hi` walk depends on the held set being *contiguous*,
and intersecting a contiguous set with an interval containing the index is still contiguous. The invariant
that died is the *other* one its KDoc claimed — "the list only grows, which keeps a forward crossing free
of any re-indexing." A forward crossing now removes the chapter behind, so indices shift down.

**Which surfaced a bug that had been there all along: the pagers were never given a `key`.** Compose's
`PagerState` restores its page by key during measure through exactly the mechanism `LazyListState` uses
(`matchPageWithKey` → `findIndexByKey`) — but only when the pager is handed a `key` lambda, and
`PagedReader` handed none, so it held a bare index. Passing `key = { items[it].key }` makes both layouts
re-anchor themselves with no command and no cancelled settle, which is what makes evicting above the
viewport affordable at all. `ReaderCommand.Reanchor` survives as the fallback: Compose builds its
key→index map only over ~100 items around the last known position (`NearestItemsExtraItemCount`), and
outside that window the lookup silently keeps the stale index — so removing a prefix longer than that (a
long webtoon chapter) would still jump. The ViewModel's index is subject to no such bound. That ceiling is
pre-existing; eviction only makes the path routine.

**The one real dead end, found by re-checking every reader of the cache.** With the window `{i-1, i, i+1}`,
`buildItems` puts the leading boundary at `from = siblings[i-2]`. Reaching that slide asks for `i-2`, whose
result `withPages` then drops as out-of-window: the slide never gains pages and paging back dead-ends
**permanently**, since nothing re-triggers that fetch. Normally unreachable — you have to focus `i-1`'s
pages first, which moves the index — but `snapshotFlow` conflates and a fling can skip every intermediate
value. Fixed structurally rather than defensively: `onTransitionFocused` now resolves an `anchor` index
from the boundary's two sides *before* loading (both sides held ⇒ interior, clamp; otherwise the held side
is where we came from), so the load target is always `anchor ± 1`. The mark-read guard fell out of it as
`fromIndex == anchor`, the same predicate with no flow read. `resolvePendingJump` gained a sibling,
`abandonUnreachableJump`: a jump whose target left the window would otherwise sit armed and fire whenever
that chapter was next fetched, scrolling the reader out from under a user who had moved on.

**Correction to this item's premise, which changes what "done" means.** `ReaderPage` is a `Url` plus a
header map (or a path string), so forty chapters of `pagesCache` is well under a megabyte — the bytes were
never there. What costs is everything *derived* from it, all of which this item also named: `buildItems`
allocating one `ReaderItem` per page of every held chapter on **every** window-input change, the `Content`
retaining that list, the two side tables of 4.2 keyed off its item keys, and the `LazyColumn`'s per-item
bookkeeping. So the win is a bounded item list and bounded side tables plus the allocation churn behind
them — **not** reclaimed bitmaps, which are Coil's and already LRU-capped. Sentry `TANUKI-2` is therefore
*not* claimed closed by this; the eviction pipeline logs the retained chapter count so the on-device check
below is observable, and it should be re-measured.

**Covered by the first tests in `:features:reader`** — 21 `ReaderViewModelTest` cases and 9
`ReaderWindowTest` ones. Eight of the 21 were confirmed failing against the old implementation before the
fix was kept; the other thirteen are the pre-eviction contract (resume page, retry, warming, transition
spinners, debounced progress, mark-read at the trailing but not the leading slide, the scrubber) and were
written *first* and stayed green through the refactor — a 599-line ViewModel with five interacting flows
is not something to rewrite blind. Standing that source set up needed the `sealed` keyword dropped from
`GetReaderPages`, `UpdateReadingProgress`, `ObserveChapters` and `ObserveManga`, exactly as 3.1 did for
the library's ten.

**One thing the fakes had to model rather than simplify:** `FakeGetReaderPages` always suspends at least
once. A fake that answered inline let `load()` publish its result *before* the content pipeline had emitted
its first `Loading`, which then overwrote it — something no real fetch can do, since the use case's
`withContext(io)` genuinely dispatches. That is a test artefact, not a ViewModel bug, and pinning it in the
fake's KDoc is cheaper than a defensive guard in production code.

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

### 4.2 Bound the two unbounded reader side-tables — **DONE**

`PageAspectRatios` gained `retain(keys)` and `PagePrefetch` trims `warmed` to the live item list at the top
of its existing effect. Both are now bounded by `Content.items`, which is what 4.1 made bounded — so the
assertion that actually pins them is 4.1's "the item list stays bounded while reading through the whole
series", not anything in the composables. `:features:reader` has no Compose test infrastructure and this
doesn't pretend otherwise.

`of()` keeps inserting on read (`getOrPut`). That read-is-a-write is deliberate — the per-key
`MutableFloatState` is what lets a prefetch warm that lands *after* an item composed re-measure it — and
`retain` is safe against it because the only caller passes the live item keys, and a key can only enter the
map from an item that was in the list. A page that comes back is re-warmed and re-measured, which costs a
cache probe rather than a download.

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

### 5.1 Install `HttpTimeout` and `HttpRequestRetry` on the shared client — **DONE**

Shipped in `installSourcePlugins`, which now installs `HttpTimeout` outermost and `HttpRequestRetry` just
inside it, above the Cloudflare and cookie plugins — retry copies the request builder per attempt, so
sitting outside is what lets each attempt re-derive the clearance and jar cookies instead of replaying a
header built for the attempt that failed. Retries are two, exponential (1 s → 8 s, honouring `Retry-After`),
and restricted to `GET`/`HEAD` on `408`/`429`/`5xx` that are **not** `isCloudflareChallenge()`.

**Deviation from what this item asked for:** there is deliberately **no client-wide `requestTimeoutMillis`**.
Ktor implements it by cancelling the request's *execution context*, which spans the whole `Send` chain —
challenge solving included — and this app's solver is `InteractiveCloudflareSolver`, bounded only by how
long the user takes to answer a Turnstile widget. A client-wide ceiling would cancel a browse or detail
call out from under them. The client sets `connectTimeout` (15 s) and `socketTimeout` (30 s) instead; the
socket timeout is what actually unsticks the stalled-body case the item was about. The hard per-call
ceiling lives where it is safe: `KtorPageFetcher` sets `requestTimeoutMillis = 120 s` per request, the one
path that is never interactive. That same call site sets `retry { maxRetries = 0 }`, because
`DownloadManager` already owns page retry and stacking the two would have silently made it nine attempts.

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

**Correction (2026-09-07): the premise above is wrong for Coil 3, so there is nothing to fix.**
`CacheStrategy.DEFAULT` in Coil 3 is `DefaultCacheStrategy`, whose `read` is literally
`// Always return the disk cache response` — it does **not** honor `Cache-Control`/`Expires`/`ETag`.
Header-driven revalidation is opt-in via the separate `coil-network-cache-control` artifact, which is
not on this classpath, and `respectCacheHeaders` is a Coil **2** API that no longer exists. The loaders
already behave the way this item wanted; the KDoc's "nothing needs revalidation" assertion holds.
Leaving the item recorded so it isn't re-audited.

Covers really are immutable (their URLs embed a file hash), so this is safe for them. For pages
it's safe for the same reason. If a source is ever found that mutates content behind a stable
URL, that's what 5.3 is for.

### 5.3 Wire or delete `MangaCover.lastModified` — **DONE (deleted)**

Deleted: the field, the `manga.cover_last_modified` column (`Migration11To12`, schema v12), the
re-stamp branch, and the reasoning in the seven files that defended it.

**What decided it, and it is not the argument this item made.** The item framed this as a coin-flip
between two coherent options. It is not one, because **the bump is provably redundant**: there is
exactly one write that re-stamps it (`SyncSourceMangaDetails`), and it fires only when the cover *URL*
changed — a change that already gives Coil a new key. `CacheSourceManga.refreshCoverHeaders`
documented that it deliberately does not bump; `CacheSourceManga` otherwise only mints a value for a
brand-new row. So no code path anywhere could bump it while the URL stayed the same, and wiring it into
`memoryCacheKey`/`diskCacheKey` would have invalidated nothing the URL had not invalidated first.

**The second reason is what makes wiring actively wrong.** The browse grid renders covers from
`MangaListing` (`SourceGrid`), the pre-persistence shape — no local row, so no version to key on. A
version-keyed cover would therefore get one key in browse and a different one in the library, and every
cover would be downloaded and stored twice. That is the exact inverse of the one-decode-size /
one-cache-entry rule this document calls the most important thing in the reader.

**What it costs, stated plainly:** the app can no longer refresh art that changes behind a stable URL.
No source is known to do that (cover URLs embed a file hash), and the honest fix if one is ever found is
a key transform on the request in `TanukiImageLoader` — where a note now records this decision so it is
not re-invented — rather than a column nothing consults. It also closes the question 3.2 deliberately
left open on `LibraryManga`: the cover stays a whole `MangaCover` because its *headers* travel with it,
and both remaining fields are ones the grid draws with. One column leaves `observeLibrary`'s per-row
projection and `HistoryDao.observeLibraryHistory`'s embedded one.

**The branch in `SyncSourceMangaDetails` survived the deletion, and had to.** It looked like cache-key
bookkeeping and is really about *headers*, with a deliberate asymmetry: a changed URL is a clean slate
and takes whatever the source says the new art needs, **including nothing**, while an unchanged URL
keeps the headers already stored when the source returns none (the same blank-field rule as the other
fields). Collapsing it to one `copy` would have silently made new art inherit the old cover's `Referer`.
All three cases are now pinned by test, where the two deleted cache-key tests used to sit — the
`SyncSourceMangaDetailsTest` fixture gained headers so they have something to preserve.

**Migration:** a plain `ALTER TABLE manga DROP COLUMN`, not the 12-step rebuild `Migration3To4` needed —
the column is in no index, constraint, view or trigger, which is all SQLite asks, and both platforms run
the bundled `androidx.sqlite` driver so the statement is identical on each. **No migration test**, in
keeping with the other eleven (no `room-testing`, no instrumented source set for `:core:data`). What was
done instead, against a SQLite fixture built from the checked-in `11.json`: the generated `12.json`
differs from `11.json` in exactly one field and in **no** index, foreign key or primary key on any
table; and a v11 database with the migration applied is structurally identical — `table_info`,
`index_list`, `foreign_key_list`, every table — to a fresh v12 install. That is the property Room's
identity check would otherwise only discover at runtime on a real upgrade.

381 tests across `:core:domain` + `:core:data` and 66 across the four feature suites, all green; both targets build. The collapse the branch note warns about was confirmed caught: rewriting it as a single `copy` fails `a changed cover url does not inherit the old headers` and nothing else.

**Problem:** `MangaCover.lastModified` is documented as *"cache-busting version — bump to
invalidate a cached cover"* (`MangaCover.kt:20`), and both `SyncSourceMangaDetails` and
`CacheSourceManga` contain careful reasoning about when **not** to bump it. But it **never
reaches an `ImageRequest`** — `coverImageRequest` (`assets/designSystem/.../CoverImageRequest.kt`)
takes only `(url, headers)`. Coil keys on the URL alone.

So the careful don't-bump logic is a no-op in both directions: bumping invalidates nothing, and a
source that changes cover art behind a stable URL can never be refreshed.

**Fix:** either append `lastModified` to the request's `memoryCacheKey`/`diskCacheKey`, or delete
the field and the reasoning around it. Pick one — the current state is the worst of both.

### 5.4 Make `PagePrefetch` connection-aware — **DONE**

Shipped as three pieces: `PagePrefetchBudget` (`features/reader/.../viewmodel/`) — `Full` at 5 ahead / 2
behind, `Frugal` at 2 / 0 — derived in `ReaderViewModel` and published as a `StateFlow` **beside**
`uiState`; `PageWarmer` (`.../composable/components/`), which owns the bookkeeping `PagePrefetch` used to
keep in a bare `MutableSet`; and a `PagePrefetch` that takes the budget as its window limits, cancels
warms that have left the window, and cancels everything on `onDispose`.

**Why the budget is not a field on `ReaderState.Content`.** `Content`'s *identity* is load-bearing — the
`Frame` machinery from the 2026-09-09 reader rework hands the previous instance forward when a rebuild
comes out equal, precisely so an unchanged item list doesn't cost a recomposition. Putting a network
value in there would make walking into a lift rebuild the page list. It rides beside `uiState` for the
same reason `commands` does. It uses `asState` even though the reader deliberately doesn't extend
`StateViewModel`: every objection in that class's KDoc (the `default` hop racing the re-anchor `var`s,
commands computed after publication, `WhileSubscribed` reseeding `frames`' `scan`) is about the *main*
pipeline, and none of them touch a two-arm flow nothing else reads. It is seeded from the synchronous
`status`/`isMetered` field reads — which is what those exist for — so a reader opened on a hotspot never
warms seven pages for the frame it takes the flow to catch up.

**No use case, deliberately.** `ObserveDownloadHold` is the shape being reused (`combine` →
`distinctUntilChanged`), not the layer: it lives in `:core:domain` because *two* consumers had to read
one answer, and here there is exactly one. `2/0` versus `5/2` is reader-presentation policy, and
`:core:domain` has no business holding it.

**`OFFLINE` folds into `Frugal` rather than earning a third budget.** A downloaded chapter is read from
disk, so warming still pays off with no network at all; a warm of a *source* page is refused by
`KtorPageFetcher` before a socket opens, then forgotten so it retries when the link comes back. Closing
the window offline would have broken the one case where prefetching is free.

**The reversal.** `PagePrefetch`'s KDoc used to argue *for* the detached warm — "scrolling quickly
re-targets the window without cancelling downloads that are already half done." That is exactly the waste
this item names, so the argument is gone rather than worked around. What it costs is real and small: a
cancelled warm forfeits the proportions it would have taught `PageAspectRatios`, which is free, because
the page is out of the window so nothing is measuring it and it is measured on its next decode like any
page seen for the first time.

**The trap in the naive version, and the reason `cancelOutside` takes a set rather than a range.** The
warm window is `focusedIndex ± n` — it does not contain `focusedIndex`. So advancing one page moves the
page the user is *looking at* out of the warm set, and a cancel-what-left-the-window rule would cancel
the download of the page currently on screen, on every single page turn. The retained set is the window
keys **plus the key at `focusedIndex`**. `retain` cancels too, on the weaker ground that an item off the
list cannot be shown without loading it again from scratch.

**`PageWarmer` holds two sets, not one, and that is the whole design.** *Warmed* answers "don't enqueue
this again" (unchanged from before: focus moves one item at a time, so re-running the window would hand
the loader the same requests over and over). *In flight* answers "this can still be stopped", and is
strictly smaller — it empties as warms land. A key leaves both together, so a page that was cancelled or
failed is warmable again while one that succeeded never is. It is free of Coil **and** Compose types —
`WarmHandle` (`fun interface { fun cancel() }`) is the only seam, and `PagePrefetch` adapts Coil's
`Disposable` to it at the call site. That is not tidiness: `:features:reader` has no Compose test
infrastructure, so a cancellation rule living in the effect body would have been untestable.

Covered by a new `PageWarmerTest` (7 cases: enqueue-once, cancel outside the window, a cancelled page
warms again, a settled one does neither, a failed one retries, an item off the list is forgotten *and*
cancelled, `cancelAll`) and 5 new `ReaderViewModelTest` cases (the budget is right **before** anything
collects it, `Full` on an unmetered link, metered and `LOW_SPEED` and `OFFLINE` each narrow it, and
leaving a hotspot widens it again). A third `FakeConnectionObserver` — the ones in `:core:source` and
`:core:domain` are `internal` to their own test source sets, which is the established answer here. 50
tests in `:features:reader`, all green; both targets build.

**Not done here:** the window is a function of the *connection*, not of how fast pages are actually
arriving — a slow source on a fast link still gets `Full`. And `5.6`'s sibling audit of the long-lived
caches is untouched.

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

### 5.6 Cap `WasmSourceLoader.modules` with an LRU — **DONE**

Shipped as a soft LRU cap of **three** modules on the fetch path, an `acquire`/`release` bracket around
every `callModule`, and — the larger half — a `withTransientModule` that takes the metadata path out of
the cache entirely. `modules` is now a `LinkedHashMap` whose iteration order *is* the eviction order
(`acquire` re-inserts on every hit), and `trim()` closes from the old end until the map fits.

**The item's premise understated where the modules come from.** `observeInstalledSources` asks
`SOURCE_META` of **every installed bundle**, and it asked through `callModule` — so the cache didn't hold
"every source the user browses", it held every source *installed*, from the first enumeration onward, for
sources the user may never open. That path now instantiates, calls and closes, which is affordable
precisely because the enumeration is rare: `observeInstalledIds()` is a revision counter behind a
`distinctUntilChanged`, so it runs at launch and on install/remove, not per screen. Peak cost during an
enumeration drops from one live module per bundle to one, and the steady-state cost drops to zero.
`loadSource` uses the same path (its `SOURCE_META` call happens once per source, ever). What is left in
the cache is exactly what the cap is for: the source the user is actually reading or browsing.

**What the cap cost, and why three.** A reading session never leaves its series' source, and the detail
screen's parallel details+chapters refresh is two calls to the *same* one — so the working set is one,
and the two spare slots are for hopping between sources in browse. Past that, coming back re-reads the
bundle and instantiates again; that is the trade, and a test pins that the call still answers normally.

**The cap is soft, and that is the load-bearing part.** `callModule` releases `modulesLock` before it
takes the module's own lock, so a module handed to a caller is reachable by nobody else but is not yet
protecting itself. `LoadedModule.inUse` (guarded by `modulesLock`, never by the module's lock) is what
closes that window: `trim()` **skips** an in-use module rather than waiting for it, so calls to more
distinct sources than the cap can hold exceed it briefly and every `release` trims again. Waiting would
have been the wrong shape — it would make an unrelated source's slow feed block instantiating the one the
user is looking at, which is the exact thing the per-module lock exists to avoid.

**It closed a pre-existing race the cap would otherwise have made routine.** `evictModulesOutside` closed
whatever was no longer installed, including a module with a call in flight. On Android that is inert
(`ChicoryWasmModule.close()` is `Unit` — instances are GC-managed, so eviction there only drops a
reference), but on iOS `WasmiWasmModule.close()` is `tanuki_module_free` + `hostRef.dispose()`, i.e. a
use-after-free while the guest is executing. The sweep now removes from the map unconditionally — so no
later `acquire` can find it — and either closes immediately or sets `closeWhenIdle` and lets the call's
own `release` do it. That asymmetry is also why the whole item is worth more on iOS than the Android
profiler would suggest: it is the only one of the two platforms where this frees anything at all.

`release` runs inside `withContext(NonCancellable)`, because it is called from a `finally` and
`Mutex.lock` is cancellable — a fetch cancelled while holding a module would otherwise skip its
decrement and pin that module for the life of the process, recreating this item's leak in a worse form.

**One Kotlin/Native difference, found by the simulator run and not the host one.** The first `trim()` held
a `MutableEntry` across `modules.remove(...)` and then read `.value`. Kotlin/Native invalidates the entry
on the modification and throws `ConcurrentModificationException`; the JVM's tolerates it, so the host
tests were green while all four cap tests failed on iOS. The key and value are copied out before the
removal now — the same reason the pre-existing `evictModulesOutside` snapshots its keys with `filterNot`.

Covered by 7 new `WasmSourceLoaderTest` cases (the cap closes the LRU; eviction is by recency and not
insertion; an evicted source is instantiated again and still answers; enumeration leaves the cache empty
and closes each transient module; `loadSource` caches nothing; a failed call still releases). Four were
confirmed failing with the cap raised to 99 before the change was kept. `FakeWasmEngine` now records
every instantiation with the module it produced, and `FakeWasmModule.close()` sets a flag instead of
being a no-op — so "which instances is the loader holding" is directly assertable. 26 tests in that
class, 115 in `:core:source`, green on both the JVM host and the iOS simulator; Android builds.

**Not reachable by unit test, and deliberately not worked around:** the in-use guard itself. Between
`acquire` and `release` the only suspension point is the contended module lock, and `WasmModule.call` is
not a suspend function — so with in-memory fakes a call never yields, and no test can hold one in flight
while another coroutine evicts. Faking it would have meant blocking a test thread or adding a seam to
production code for the test's benefit. What *is* pinned is the `finally` (the failed-call case above);
the rest rests on the invariant that `inUse` only changes under `modulesLock`.

**The minor siblings: audited, deliberately left.** All three are keyed by *host*, and a session touches
a handful — they are per-host entries holding a few short strings, not a memory item:
`CloudflareCookieStore.byHost` (one `CfClearance`), `CloudflareInterceptor.locks` (one `Mutex`, and its
KDoc already says why it is never removed) and `SourceCookieJar.byHost`. The last is the only one with a
*correctness* smell rather than a size one — `parseSetCookie` drops `Max-Age`/`Expires`, so nothing ever
expires — and its own KDoc already records the decision: the jar is process-lived and host-scoped, so an
expiry days away changes nothing, and `Max-Age=0` (which means *delete*) is left for the first source
that uses it. That is a cookie-correctness item, not this one.

**Problem:** `WasmSourceLoader.modules` (`:87`) holds instantiated WASM modules — the heaviest objects in
the app's long-lived caches. It *is* evicted, but only via `evictModulesOutside(ids)` when the
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

*(Untouched by 5.6: that item bracketed `callModule` with `acquire`/`release` so the cache can tell a
cached module from a mid-call one, but the per-source mutex and what it serializes are unchanged.)*

---

## Explicitly *not* problems

Worth recording so they don't get re-audited:

- **FK indices are complete.** Every FK child column is a PK, has a dedicated index, or is the
  leftmost column of a composite. No missing-index warnings expected.
- **`HistoryDao.observeLibraryHistory`** (`:91-109`) pushes filter, one-per-series collapse,
  ordering, cap and joins all into SQL. Home's continue-reading is **not** an N+1. (Its `LIMIT 15`
  applies after grouping the whole table, so it does scale with history size. **Item 2.3 bounded that
  table but did not remove the grouping** — and cannot, since the subquery's un-filtering by date is what
  makes each series carry its *all-time* last read. So this is now "grouping a bounded table", which is a
  smaller version of the same shape rather than a closed item.)
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
- **The reader's item list and its spine were reworked (2026-09-09), after 4.1.** Three efficiency
  defects and one reactivity gap, all fixed together because they turned out to be the same shape:
  `buildItems` ran on **every** page turn *and* chrome toggle (one `combine` couldn't tell them apart);
  nothing in the reader was Compose-skippable, because `ReaderState.Content` holds a `List` and so was
  inferred **unstable**; `siblings.indexOfFirst` was an O(total chapters) scan per page turn on an
  uncapped list; and the chapter spine was snapshotted, so a background `UpdateLibrary` landing a chapter
  mid-read was invisible. The keystone was **re-keying `ReaderWindow` from a positional index to the
  current chapter's id** — a position is meaningful only against one spine, and the spine is now live, so
  an id needs no remapping *and* deletes the hot-path scan outright (a focused page carries its own
  `chapterId`). `Frame` pairs the window with the list built from it and hands the *previous instance*
  forward when a rebuild comes out equal, so a chapter crossing doesn't hand the UI a new reference for
  an unchanged list. `@Immutable` on `Content`/`ReaderItem`/`ChapterRef` is what lets Compose act on that;
  `ReaderChrome` now takes scalars rather than the whole `Content`. `Bootstrap` (Resolving | Failed |
  Ready) replaced the nullable session so `Loading`/`Error` are derived and the pipeline is the only
  writer of `uiState`, and the progress debounce became a flow instead of a hand-cancelled `Job`.
  Covered by 38 tests (29 + 9), the load-bearing ones asserting **identity** (`assertSame`) not equality —
  an equal-but-fresh list passes `assertEquals` while still costing the recomposition the work exists to
  avoid. **Two things deliberately left:** an open reader now holds two live Room subscriptions, and the
  manga one is the six-table projection re-running on our own progress writes only to be discarded by the
  spine gate — a narrow "reading mode + scanlator filter" read would fix that. And a progress write is
  still lost if the reader closes inside the 400 ms debounce; flushing from `onCleared` needs a scope
  outliving the ViewModel, which would make the reader the first feature to inject the application scope.
- **The dispatchers are right, including the two that look wrong.** Audited after Phase 4 landed.
  `ReaderViewModel`'s three pipelines are collected on Main with no `flowOn`, and must stay that way:
  `reanchorIfNeeded` / `resolvePendingJump` / `abandonUnreachableJump` mutate plain `var`s that
  `onItemFocused` also writes from the UI thread, so `dispatcher.default` would recreate item 3.5's
  race — and the command contract ("every command references the list already in `uiState`") is an
  ordering guarantee that only holds on one thread. The upstream repository flows are `flowOn(io)`
  already, so only the emission crosses back, and Phase 4 is what made the per-emission `buildItems`
  allocation on Main bounded. `persistNow` likewise calls `updateReadingProgress` without an `io { }`
  and doesn't need one.
- **Every layer below a ViewModel dispatches for itself** — Room through
  `DatabaseBuilder.buildDatabase`'s `setQueryCoroutineContext(dispatcherProvider.io)`, DataStore through
  its own scope, `FileSystemChapterDownloadStore` and `WasmSourceLoader` through explicit `withContext`.
  So `TanukiViewModel.io` is a boundary guarantee, not what keeps the main thread free, and `tryIo`'s
  real value is its logging and its cancellation rule. The rule for the layer below — **wrap only for
  work the repository does itself** (list→domain mapping, batching, encoding), never for a plain DAO
  delegation — is now recorded in `TanukiViewModel`'s KDoc and CLAUDE.md, where the opposite claim used
  to be. `:core:data` follows it in the majority (26 of 61 suspend functions wrap) but not uniformly:
  `SettingsRepositoryImp` wraps all seven setters around an already-IO-safe `dataStore.edit`,
  `DownloadRepositoryImp.setProgress` wraps a one-liner while `nextQueued` maps to domain unwrapped.
  Those are **redundancy, not bugs** — a `withContext` to the context you are already in does not
  dispatch. Normalising all 61 is its own piece of work; deliberately not done here.

---

## Verification

Per item, before moving to the next:

```bash
./gradlew :androidApp:assembleDebug                 # Android stays green
./gradlew :core:domain:iosSimulatorArm64Test \
          :core:data:iosSimulatorArm64Test          # unit tests
```

Note the task names: neither `:core:domain` nor `:core:data` declares `withHostTest {}`, so their
`commonTest` runs on the iOS simulator target only and there is no `testAndroidHostTest` task for
either. The modules that do have one are `:core:source`, `:core:navigation`, `:core:presentation` and
`:shared`. `:features:library`, `:features:home` and `:features:activity` have `commonTest` source sets
as of 3.1, and `:features:reader` as of 4.1 — iOS-simulator only, same as `:core:domain` — and the other
feature modules still have none. Kotlin/Native also rejects a backtick
test name containing a comma, and a `private` top-level fake collides with an identically-named one in
another file of the same package.

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

**Phase 4 check:** read 20+ chapters continuously and watch heap growth. The eviction pipeline logs
`Reader window holds N chapters` — N should sit at 3 indefinitely. The parts no unit test reaches, all
gesture-level: paged, swipe *slowly* across a boundary and hold at ~60% (no snap, and eviction fires only
after you let go and stop — this is the failure the naive move-and-evict design produces); webtoon, *fling*
across a boundary (no jump when the prefix is removed, and the leading slide of the newly-current chapter
still loads its predecessor); either layout, cross a boundary and immediately hit the previous-chapter
button (the jump lands).

**Phase 5.2 check:** the decisive test is a source whose CDN sends a short `max-age`. Load a
library screen, force-kill, relaunch offline — covers should still render from disk.

---

## See also

- `docs/backlog-observability.md` — logging + Sentry. Item 4.1 here is the likely cause of the
  `WatchdogTermination` recorded there.
