package app.tanuki.features.reader.viewmodel

import androidx.lifecycle.viewModelScope
import app.tanuki.core.domain.bookmark.model.PageBookmark
import app.tanuki.core.domain.bookmark.usecase.ObservePageBookmarks
import app.tanuki.core.domain.bookmark.usecase.TogglePageBookmark
import app.tanuki.core.domain.common.model.Url
import app.tanuki.core.domain.download.usecase.ObserveChapterDownloads
import app.tanuki.core.domain.manga.usecase.SetReadingMode
import app.tanuki.core.domain.settings.model.ReaderBackground
import app.tanuki.core.domain.settings.usecase.ObserveReaderBackground
import app.tanuki.core.domain.settings.usecase.SetReaderBackground
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import app.tanuki.core.domain.chapter.model.Chapter
import app.tanuki.core.domain.chapter.model.PageImage
import app.tanuki.core.domain.chapter.model.ReaderPage
import app.tanuki.core.domain.chapter.model.isHiddenBy
import app.tanuki.core.domain.chapter.usecase.GetReaderPages
import app.tanuki.core.domain.chapter.usecase.ObserveChapters
import app.tanuki.core.domain.chapter.usecase.UpdateReadingProgress
import app.tanuki.core.domain.common.service.ConnectionObserver
import app.tanuki.core.domain.common.service.ConnectionObserver.ConnectionStatus
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.common.service.debug
import app.tanuki.core.domain.common.service.warn
import app.tanuki.core.domain.manga.model.ReadingMode
import app.tanuki.core.domain.manga.model.ReadingMode.RIGHT_TO_LEFT
import app.tanuki.core.domain.manga.usecase.ObserveManga
import app.tanuki.core.navigation.Reader
import app.tanuki.core.presentation.TanukiViewModel
import app.tanuki.core.presentation.runCatchingCancellable
import app.tanuki.features.reader.viewmodel.ReaderItem.PageItem
import app.tanuki.features.reader.viewmodel.ReaderState.Content
import app.tanuki.features.reader.viewmodel.ReaderState.Error
import app.tanuki.features.reader.viewmodel.ReaderState.Loading
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.uuid.Uuid

/**
 * Presentation logic for the reader. Compose-free (per CLAUDE.md → MVVM): it exposes state as a
 * [StateFlow] and imperative scrolls as one-shot [ReaderCommand]s, so the same logic drives the shared
 * Compose UI on Android and iOS and is unit-testable without a Compose runtime.
 *
 * ## Continuous reading
 *
 * The reader reads *across* chapters without ever reloading the screen. It keeps a sliding window of at
 * most three chapters — previous · current · next — whose pages are concatenated into one [ReaderItem]
 * list with a [ReaderItem.Transition] slide between each chapter. The adjacent chapters are preloaded, so
 * reaching the last page of the current chapter and swiping once more lands on the transition slide, and
 * once more on the next chapter's first page — no navigation, no fresh ViewModel, no spinner.
 *
 * "At most three" is enforced, not merely described: [ReaderWindow] drops what falls outside the window,
 * so a long session holds three chapters however many it has read through. When the visible page crosses
 * into a neighbouring chapter the window slides one step — the new neighbour is preloaded, the one two
 * behind is dropped, and the viewport keeps its page. Both layouts re-anchor themselves by item key
 * during measure; [ReaderCommand.Reanchor] is the fallback for shifts Compose's key lookup can't see
 * (see its KDoc). The chrome (title, scrubber) always reflects whichever chapter the visible page
 * belongs to.
 *
 * ## Reactive shape (mirrors the other feature ViewModels)
 *
 * Every screen state is **derived**. [bootstrap] gates the whole thing — `Resolving` renders [Loading],
 * `Failed` renders [Error], `Ready` folds the window, [currentPageInChapter] and [chromeVisible] into
 * [Content] — so the pipeline's own `onEach` is the only writer of [uiState]. A page focus that advances
 * the chapter, a fetched chapter landing in the cache, a fetch starting or finishing, an eviction, a
 * chrome toggle and a failed load all flow through that one path.
 *
 * The window's three parts are one flow on purpose — see [ReaderWindow] for why the chapter and the cache
 * must never be written apart — and the item list is derived from it alone, so a page turn within a
 * chapter and a chrome toggle rebuild nothing (see [frames]).
 *
 * Four pipelines hang off it. **Fetching is flow-driven** — every chapter change drives [warmWindow],
 * which loads the current chapter and warms its successor. **Eviction** runs once the window has held
 * still for [EVICT_QUIET_MS], because the reader's position moves mid-gesture and dropping items from
 * above a viewport under a live finger is not something to do eagerly — and the previous chapter is warmed
 * on the same quiet beat, since its pages land above the viewport too. **Progress** is a debounced flow,
 * so a fast swipe writes once. And the **spine** is live: the chapter list can change under an open
 * reader, so the session is re-projected onto it rather than snapshotted (see [spines] and [reproject]).
 *
 * ## Why this extends [TanukiViewModel] and not `StateViewModel`
 *
 * Not because the state isn't derived — it is, entirely. Because `asState` doesn't fit, three ways: it
 * maps on [DispatcherProvider.default], and [focusedItemKey]/[lastAnchoredIndex]/[pendingJump]
 * are mutated from both the pipeline and the main thread, so the hop would leave them unsynchronised;
 * the scroll [ReaderCommand]s must be computed *after* the state is published, where a `state` flow sits
 * upstream of `stateIn` and nothing has; and `WhileSubscribed` would stop and reseed [frames]' `scan` on
 * a route-scoped screen that is visible for its whole life, losing the item-list memoization to save
 * nothing. Making it fit would mean a sharing knob on that class, which every one of its eight
 * `AppScope` subclasses relies on *not* having.
 *
 * ## Known gap
 *
 * A progress write dropped by the reader closing inside [PROGRESS_PERSIST_DEBOUNCE_MS] is lost —
 * `viewModelScope` is cancelled with the screen and the debounce rides on it. Fixing it needs a flush
 * from `onCleared` on a scope that outlives the ViewModel, which would make this the first feature to
 * inject the application scope; deliberately left rather than done as a side effect here.
 *
 * The only imperative outputs are the one-shot scroll [ReaderCommand]s — a re-anchor after the list was
 * re-indexed under the viewport, or an explicit jump — computed as a side effect of each newly built item list (see
 * the content pipeline's `onEach`), which keeps every command referencing the list that was just emitted.
 *
 * Assisted-injected with the [mangaId] and [initialChapterId] the [Reader] route carried. Metro builds
 * it through [Factory]; the UI resolves it with `assistedMetroViewModel { create(mangaId, chapterId) }`.
 *
 * All flow mutation happens on the ViewModel's main-dispatched scope, so the item-list bookkeeping needs
 * no extra synchronisation; only the page fetch itself hops to [DispatcherProvider.io].
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@AssistedInject
class ReaderViewModel(
    @Assisted private val mangaId: Uuid,
    @Assisted private val initialChapterId: Uuid,
    @Assisted private val initialPage: Int?,
    private val getReaderPages: GetReaderPages,
    private val updateReadingProgress: UpdateReadingProgress,
    private val observeChapters: ObserveChapters,
    private val observeManga: ObserveManga,
    private val observeChapterDownloads: ObserveChapterDownloads,
    private val observePageBookmarks: ObservePageBookmarks,
    private val togglePageBookmark: TogglePageBookmark,
    private val setReadingMode: SetReadingMode,
    private val observeReaderBackground: ObserveReaderBackground,
    private val setReaderBackground: SetReaderBackground,
    connectionObserver: ConnectionObserver,
    dispatcher: DispatcherProvider,
    logger: Logger
) : TanukiViewModel(dispatcher, logger) {
    val uiState: StateFlow<ReaderState>
        field = MutableStateFlow<ReaderState>(Loading)

    /** Imperative scrolls the UI must apply (re-anchor after a window shift, or an explicit jump). */
    private val _commands = Channel<ReaderCommand>(Channel.BUFFERED)
    val commands: Flow<ReaderCommand> = _commands.receiveAsFlow()

    /**
     * How far ahead and behind the UI may warm page images, as the connection currently allows.
     *
     * Published beside [uiState] rather than folded into [Content], on the same reasoning that keeps
     * [commands] separate: [Content]'s identity is what lets the reader skip recomposing an unchanged item
     * list, and a network change has nothing to say about that list. The two arms are combined here rather
     * than in a use case because there is exactly one consumer and the numbers are the reader's policy —
     * `ObserveDownloadHold` is the shape being reused, not the layer.
     *
     * `metered` and `LOW_SPEED` are separate axes (a 5G link is expensive and fast), so either narrows the
     * window. `OFFLINE` narrows it too rather than closing it: a downloaded chapter is read from disk and
     * still worth warming, and a warm of a source page is refused by the fetcher before a socket opens.
     *
     * Seeded from the synchronous status reads, so the first composition already has the right window
     * instead of a frame of `Full` on a hotspot.
     */
    val prefetchBudget: StateFlow<PagePrefetchBudget> =
        combine(connectionObserver.connectivity, connectionObserver.metered, ::budgetFor)
            .distinctUntilChanged()
            .asState(budgetFor(connectionObserver.status, connectionObserver.isMetered))

    /**
     * How far the reader has got resolving what it needs, and the gate every pipeline hangs off. Each of
     * its three stages *is* one of the three [ReaderState]s, which is what makes the screen fully derived.
     */
    private val bootstrap = MutableStateFlow<Bootstrap>(Bootstrap.Resolving)

    /** The resolved session, or `null` while bootstrapping or failed — what the action handlers read. */
    private val session: ReaderSession?
        get() = (bootstrap.value as? Bootstrap.Ready)?.session

    /**
     * The chapter being read, the pages held for it and its neighbours, and the fetches in flight — one
     * value, so the index and the cache can never be observed apart. See [ReaderWindow].
     */
    private val window = MutableStateFlow(ReaderWindow.EMPTY)

    /** Zero-based index of the visible page within the current chapter (drives the scrubber). */
    private val currentPageInChapter = MutableStateFlow(0)

    /** Whether the reader chrome (top/bottom bars) is shown; toggled by tapping the page area. */
    private val chromeVisible = MutableStateFlow(true)

    /** The sheet open over the reader, if any. */
    private val sheet = MutableStateFlow<ReaderSheet?>(null)

    /** Which list the Chapters sheet shows. */
    private val chaptersTab = MutableStateFlow(ReaderChaptersTab.Chapters)

    /** Whether a finger is on the scrubber, which holds the chrome open. */
    private val scrubbing = MutableStateFlow(false)

    /**
     * This series' page bookmarks. Small, and read on every page turn (is *this* page bookmarked?), so it
     * is held for the reader's life rather than only while the sheet is open.
     */
    private val bookmarks: StateFlow<List<PageBookmark>> = observePageBookmarks(mangaId).asState(emptyList())

    /** Key of the item the viewport is on, so a window shift can re-anchor to the same page/slide. */
    private var focusedItemKey: String? = null

    /** Last index we re-anchored the pager to, so we don't re-send a no-op [ReaderCommand.Reanchor]. */
    private var lastAnchoredIndex: Int = -1

    /** A page to jump to once its chapter's pages have loaded; resolved by the content pipeline. */
    private var pendingJump: PendingJump? = null

    /**
     * Progress writes waiting to be persisted. A flow rather than a hand-cancelled job: the debounce is the
     * operator's, so there is no `Job` to keep and cancel, and `DROP_OLDEST` says the same thing the old
     * `cancel()` did — only the newest page matters, because each write supersedes the last.
     */
    private val pendingProgress =
        MutableSharedFlow<ProgressWrite>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        // Content pipeline: fold the window inputs into [Content] whenever any of them changes. Gated on
        // [session] via flatMapLatest so a reload (session → null → new) cleanly tears the combine down and
        // shows [Loading] in between, instead of building an item list against a half-reset cache.
        bootstrap
            .flatMapLatest { stage ->
                when (stage) {
                    Bootstrap.Resolving -> flowOf<ReaderState>(Loading)

                    Bootstrap.Failed -> flowOf<ReaderState>(Error)

                    is Bootstrap.Ready ->
                        combine(
                            stage.session.frames(),
                            currentPageInChapter,
                            chromeVisible,
                            extras()
                        ) { frame, pageInChapter, chrome, extras ->
                            buildContent(stage.session, frame, pageInChapter, chrome, extras)
                        }
                }
            }.onEach { state ->
                // State first, then the scroll side effects — so every command references the item list
                // that is now in [uiState] (a Reanchor/Jump index is only valid against the emitted list).
                uiState.value = state
                if (state is Content) {
                    reanchorIfNeeded(state.items)
                    abandonUnreachableJump()
                    resolvePendingJump(state.items)
                }
            }.launchIn(viewModelScope)

        // Fetch pipeline: every index change warms the window (current + next). Also gated on the session
        // so it can't fetch against stale siblings during a reload. [distinctUntilChanged] is load-bearing
        // — page inserts and evictions write [window] too, and neither is a reason to re-warm.
        bootstrap
            .flatMapLatest { stage ->
                if (stage !is Bootstrap.Ready) emptyFlow() else window.currentChapters().map { stage.session to it }
            }.onEach { (active, chapterId) -> warmWindow(active, chapterId) }
            .launchIn(viewModelScope)

        // Eviction pipeline: drop what has fallen outside the window, but only once the reader has
        // *settled*. The index moves mid-gesture (a pager's currentPage flips at the halfway point of a
        // drag; a strip's first visible item changes throughout a fling), so evicting the moment it moves
        // would pull items out from above a viewport the user still has a finger on — and the re-anchor
        // that followed would cancel the in-flight settle. Waiting for a quiet index also makes crossing
        // a boundary back and forth free: it never drops what it is about to need again.
        bootstrap
            .flatMapLatest { stage ->
                if (stage !is Bootstrap.Ready) {
                    emptyFlow()
                } else {
                    window.currentChapters().debounce(EVICT_QUIET_MS).map { stage.session }
                }
            }.onEach { active ->
                val evicted = window.updateAndGet { it.evicted(active) }
                logger.debug(tag, "Reader window holds ${evicted.pages.size} chapters")
                // The previous chapter is warmed here, on a parked reader, rather than with the next one
                // at open: its pages go *above* the viewport, and both layouts hold their place across
                // that by key — which is only safe once nothing is mid-gesture.
                active.siblings.getOrNull(active.positionOf(evicted.currentChapterId) - 1)?.let { loadChapter(it.id) }
            }.launchIn(viewModelScope)

        // Progress pipeline: one write lands per quiet period, so a fast swipe through a chapter writes
        // once rather than once per page.
        pendingProgress
            .debounce(PROGRESS_PERSIST_DEBOUNCE_MS)
            .onEach { persistNow(it.chapterId, it.page, it.read) }
            .launchIn(viewModelScope)

        // Spine pipeline: the chapter list can change under an open reader — a background library update
        // lands a new chapter, or prunes one the source delisted — so the session is re-projected onto it
        // rather than read once and then blind for the rest of the session. Gated by [sameSpine], which is
        // what stops the reader's *own* progress writes from re-projecting it (see [spines]).
        spines()
            .onEach(::reproject)
            .launchIn(viewModelScope)

        // Chrome auto-hide: shown chrome fades after [CHROME_HIDE_MS] of nothing, unless a sheet is open
        // or the scrubber is held. `collectLatest` restarts the wait on every change, so any of those
        // (a tap, opening a sheet) resets it.
        viewModelScope.launch {
            combine(chromeVisible, sheet, scrubbing) { visible, open, held -> visible && open == null && !held }
                .collectLatest { hide ->
                    if (hide) {
                        delay(CHROME_HIDE_MS)
                        chromeVisible.value = false
                    }
                }
        }

        load()
    }

    /**
     * Resolves the spine and the opened chapter's pages, seeds the window, then publishes the
     * [ReaderSession] that starts the reactive pipelines.
     *
     * The **one-shot** half of the reader's bootstrap, and only that: it fetches pages, computes the
     * resume page and records the open, none of which may run twice. The spine it starts from is the
     * first emission of [spines], the same definition the live pipeline keeps re-projecting from — so
     * "what chapters exist" is stated once and stays true, rather than being read here and going stale.
     *
     * A failure to fetch the *first* chapter's pages resolves to [Error] with a retry; adjacent-chapter
     * failures are silent (the transition simply won't gain pages).
     */
    private fun load() {
        // Reset every input so a retry starts clean; [Bootstrap.Resolving] parks the pipelines on Loading /
        // idle (via flatMapLatest) until the new session lands. Nothing writes [uiState] directly any more
        // — all three states are derived from this one.
        bootstrap.value = Bootstrap.Resolving
        window.value = ReaderWindow.EMPTY
        focusedItemKey = null
        lastAnchoredIndex = -1
        pendingJump = null

        viewModelScope.launch {
            // Cancellable, not plain `runCatching`: a reader closed while this is resolving must unwind
            // silently. Swallowing the cancellation would fall through to `startIndex < 0` and paint
            // [Error] over a screen that is already going away.
            val spine = runCatchingCancellable { spines().first() }.getOrNull()
            val readingMode = spine?.readingMode ?: RIGHT_TO_LEFT
            val siblings = spine?.let(::siblingsOf).orEmpty()
            val startIndex = siblings.indexOfFirst { it.id == initialChapterId }

            if (startIndex < 0) {
                bootstrap.value = Bootstrap.Failed
                return@launch
            }

            val pages =
                tryIo("Failed to load pages for chapter $initialChapterId", reportAs = PAGES_UNAVAILABLE) {
                    getReaderPages(initialChapterId)
                }.getOrNull()

            if (pages == null) {
                bootstrap.value = Bootstrap.Failed
                return@launch
            }

            // Resume where the user left off — unless the chapter is finished, which reopens at its start
            // (its last page read is the end, and reopening a read chapter means reading it again). A
            // page the route asked for (a bookmark) wins over both. Clamped in case the count shrank.
            val progress = siblings[startIndex].progress
            val resumePage =
                (initialPage ?: if (progress?.alreadyRead == true) 0 else progress?.lastPageRead?.toInt() ?: 0)
                    .coerceIn(0, (pages.size - 1).coerceAtLeast(0))

            // The pager starts on the resume page, shifted past a leading transition when a previous chapter
            // exists (its pages aren't loaded yet, so the slide is the first item).
            val hasLeadingTransition = startIndex > 0
            val initialItemIndex = (if (hasLeadingTransition) 1 else 0) + resumePage

            // Seed the window inputs and the re-anchor bookkeeping BEFORE publishing the session, so the
            // first [Content] the pipeline builds already parks on the resume page with no stray re-anchor.
            val resolved = ReaderSession(siblings, spine?.mangaTitle.orEmpty(), readingMode, initialItemIndex)
            val seeded = ReaderWindow(initialChapterId, mapOf(initialChapterId to pages), emptySet())
            window.value = seeded
            currentPageInChapter.value = resumePage
            focusedItemKey = buildItems(resolved, seeded).getOrNull(initialItemIndex)?.key
            lastAnchoredIndex = initialItemIndex

            // Record the open itself: a chapter read on its single/first page never fires a page change, so
            // without this it would be missing from history / continue-reading. Stamps the resume page
            // (marking read only when that page is already the last), refreshing the history timestamp.
            val markRead = pages.isNotEmpty() && resumePage >= pages.lastIndex
            persistNow(initialChapterId, resumePage, read = markRead)

            // Publishing the session starts the content + warm pipelines; the warm pipeline preloads the
            // next chapter (so crossing the trailing boundary is seamless). The previous chapter is not
            // prepended here, which would shove the just-opened page down mid-layout: it is warmed once the
            // reader settles (the eviction pipeline), or at once if its leading transition is focused.
            bootstrap.value = Bootstrap.Ready(resolved)
        }
    }

    /** The reader failed to load the first chapter and the user tapped retry. */
    fun onRetry() = load()

    /** Tapping the page area toggles the reader chrome (top/bottom bars). */
    fun onToggleChrome() {
        chromeVisible.update { !it }
    }

    /**
     * The viewport settled on the item keyed [key] (a page swipe/scroll, or the result of a command).
     * Updates the chrome, persists progress, slides the window if the page belongs to a neighbouring
     * chapter, and kicks off any loading a transition slide implies.
     *
     * By key, not index: the UI may still be drawing the list before the one in [uiState] (a chapter was
     * just prepended or evicted), and an index from that list resolved against this one names an item N
     * positions away — the end of the chapter, or the next one. A key names the same item in both. A key
     * that is no longer held (its chapter was evicted) is ignored.
     */
    fun onItemFocused(key: String) {
        val content = uiState.value as? Content ?: return
        val index = content.items.indexOfFirst { it.key == key }
        if (index < 0) return
        val item = content.items[index]
        focusedItemKey = key
        lastAnchoredIndex = index
        when (item) {
            is PageItem -> onPageFocused(item)

            is ReaderItem.Transition -> onTransitionFocused(item)

            // The terminal slide carries no chapter of its own; the last page already recorded progress.
            ReaderItem.End -> Unit
        }
    }

    private fun onPageFocused(item: PageItem) {
        currentPageInChapter.value = item.pageNumber
        // The page names its own chapter, so the hot path needs no lookup: moving the window re-points the
        // chrome (via the content pipeline), warms the new neighbour (via the fetch pipeline) and, once
        // the reader settles, drops the chapter now two behind (via the eviction pipeline). Only the last
        // of those re-indexes what is on screen, which is why it waits. A move to the chapter already
        // current is a no-op the flow conflates, so an in-chapter page turn costs nothing here.
        window.update { it.movedTo(item.chapterId) }
        persistDebounced(item.chapterId, item.pageNumber, read = item.pageNumber >= item.pageCount - 1)
    }

    private fun onTransitionFocused(item: ReaderItem.Transition) {
        val active = session ?: return
        val current = window.value
        val fromIndex = active.positionOf(item.from.id)
        val toIndex = active.positionOf(item.to.id)

        // Re-anchor the index on this boundary *before* loading anything. A boundary belongs to whichever
        // of its two chapters the reader is actually in: both sides held means an interior boundary and
        // the index is already right (clamped, because a flung strip can skip the pages that would have
        // moved it); one side held means an end of the window, and the held side is the one we came from.
        //
        // Doing this first is what keeps the load below inside the window. Without it, reaching the
        // leading slide of a window `{i-1, i, i+1}` would ask for `i-2`, whose result [ReaderWindow] then
        // drops as out-of-window — the slide would never gain pages and paging further back would dead-end
        // permanently, since nothing re-triggers that fetch.
        val anchor =
            when {
                current.holds(item.from.id) && current.holds(item.to.id) ->
                    active.positionOf(current.currentChapterId).coerceIn(fromIndex, toIndex)

                current.holds(item.from.id) -> fromIndex

                else -> toIndex
            }
        active.siblings.getOrNull(anchor)?.let { anchored -> window.update { it.movedTo(anchored.id) } }

        // Load whichever side isn't held yet, so swiping past this slide continues seamlessly.
        val toLoad =
            when {
                !current.holds(item.from.id) -> item.from.id
                !current.holds(item.to.id) -> item.to.id
                else -> null
            }
        if (toLoad != null) loadChapter(toLoad)

        // A trailing boundary means the user just finished [from] — mark it read. A leading slide, entered
        // by paging *back*, anchors on [to] instead and so doesn't falsely mark its predecessor read.
        if (fromIndex == anchor) {
            current.pages[item.from.id]?.let { p ->
                persistDebounced(item.from.id, p.lastIndex.coerceAtLeast(0), read = true)
            }
        }
    }

    /** Scrub to page [page] of the current chapter (from the bottom-chrome slider). */
    fun onScrubToPage(page: Int) {
        val content = uiState.value as? Content ?: return
        val key = pageKey(content.currentChapterId, page)
        if (content.items.any { it.key == key }) _commands.trySend(ReaderCommand.Jump(key))
    }

    /** Jump to the start of the next chapter (bottom-chrome button); loads it first if needed. */
    fun onNextChapter() {
        val active = session ?: return
        active.siblings.getOrNull(active.positionOf(window.value.currentChapterId) + 1)
            ?.let { jumpTo(it.id, page = 0) }
    }

    /** Jump to the start of the previous chapter (bottom-chrome button); loads it first if needed. */
    fun onPreviousChapter() {
        val active = session ?: return
        val position = active.positionOf(window.value.currentChapterId)
        if (position > 0) jumpTo(active.siblings[position - 1].id, page = 0)
    }

    /** Open any chapter of the series at its first page (the chapter list); closes the sheet. */
    fun onChapterSelected(chapterId: Uuid) {
        sheet.value = null
        jumpTo(chapterId, page = 0)
    }

    /** Open a bookmarked page (the Bookmarks list); closes the sheet. */
    fun onBookmarkSelected(bookmarkId: Uuid) {
        val bookmark = bookmarks.value.firstOrNull { it.id == bookmarkId } ?: return
        sheet.value = null
        jumpTo(bookmark.chapterId, bookmark.page)
    }

    fun onOpenSheet(sheet: ReaderSheet) {
        this.sheet.value = sheet
    }

    fun onDismissSheet() {
        sheet.value = null
    }

    fun onChaptersTabChange(tab: ReaderChaptersTab) {
        chaptersTab.value = tab
    }

    /** The scrubber was grabbed or let go; the chrome stays up while it is held. */
    fun onScrubbingChange(active: Boolean) {
        scrubbing.value = active
    }

    /**
     * Bookmark the page on screen, or remove its bookmark. The page's image goes with it, for the
     * bookmark's thumbnail.
     */
    fun onToggleBookmark() {
        val content = uiState.value as? Content ?: return
        val chapterId = content.currentChapterId
        val page = content.currentPage
        val item = content.items.firstOrNull { it.key == pageKey(chapterId, page) } as? PageItem
        val image =
            item?.localPath?.let(PageImage::Local)
                ?: item?.imageUrl?.let { PageImage.Remote(Url(it), item.imageHeaders) }
        viewModelScope.launch {
            tryIo("Could not toggle a bookmark") { togglePageBookmark(mangaId, chapterId, page, image) }
        }
    }

    /**
     * Lay the series out another way. Saved on the series as the user's choice, which suggestions never
     * override; the spine pipeline picks it up and the layout switches in place, on the same page (see
     * [reproject]).
     */
    fun onReadingModeChange(mode: ReadingMode) {
        viewModelScope.launch { tryIo("Could not change the reading mode") { setReadingMode(mangaId, mode) } }
    }

    fun onBackgroundChange(background: ReaderBackground) {
        viewModelScope.launch { tryIo("Could not change the reader background") { setReaderBackground(background) } }
    }

    /** The back affordance was tapped. */
    fun onBack() {
        navigateBack()
    }

    /**
     * Lands the reader on [page] of [chapterId], from wherever it is.
     *
     * - **Held:** jump straight there.
     * - **A neighbour:** load it into the window; the jump fires once its pages land (see
     *   [resolvePendingJump]).
     * - **Further away:** the window can't grow to reach it (it holds one chapter either side), so it is
     *   *reseeded* on the target — but only once the target's pages are in hand, in a single write, so
     *   the reader never shows an empty list in between (see [reseed]).
     */
    private fun jumpTo(
        chapterId: Uuid,
        page: Int
    ) {
        val active = session ?: return
        val target = active.positionOf(chapterId)
        if (target < 0) return
        val current = window.value
        current.pages[chapterId]?.let { pages ->
            pendingJump = null
            _commands.trySend(ReaderCommand.Jump(pageKey(chapterId, page.coerceIn(0, pages.lastIndex.coerceAtLeast(0)))))
            return
        }
        pendingJump = PendingJump(chapterId, page)
        if (abs(target - active.positionOf(current.currentChapterId)) <= ReaderWindow.RADIUS) {
            loadChapter(chapterId)
        } else {
            reseed(chapterId)
        }
    }

    /**
     * Fetches a chapter outside the window and, once its pages are in hand, replaces the window with it
     * in one write. The old pages stay on screen meanwhile; a jump made elsewhere before the fetch lands
     * (the user changed their mind) leaves the window alone.
     */
    private fun reseed(chapterId: Uuid) {
        if (chapterId in window.value.loading) return
        window.update { it.loadingStarted(chapterId) }
        viewModelScope.launch {
            val pages =
                tryIo("Failed to load pages for chapter $chapterId", reportAs = PAGES_UNAVAILABLE) {
                    getReaderPages(chapterId)
                }.getOrNull()
            if (pages == null || pendingJump?.chapterId != chapterId) {
                window.update { it.loadingFinished(chapterId) }
                return@launch
            }
            // The page on screen leaves the list with this write; nothing to re-anchor to until the jump
            // lands and the UI reports the new page.
            focusedItemKey = null
            lastAnchoredIndex = -1
            window.value = ReaderWindow(chapterId, mapOf(chapterId to pages), emptySet())
        }
    }

    /**
     * Fetches [chapterId]'s pages into the window (if not already loading/loaded). The writes flow into the
     * content pipeline, which rebuilds the item list (spinner while in flight, pages once resolved) on its
     * own — this function only mutates the flow.
     *
     * The result lands in **one** write, and only if the chapter is still inside the window: a user who
     * out-ran a slow fetch has moved on, and putting it back would grow the window by one every time it
     * happened. The spinner is cleared either way.
     */
    private fun loadChapter(chapterId: Uuid) {
        val current = window.value
        if (current.holds(chapterId) || chapterId in current.loading) return
        window.update { it.loadingStarted(chapterId) } // reflects the spinner on the transition slide

        viewModelScope.launch {
            val pages =
                tryIo("Failed to load pages for chapter $chapterId", reportAs = PAGES_UNAVAILABLE) {
                    getReaderPages(chapterId)
                }.getOrNull()
            val active = session
            window.update { w ->
                w.loadingFinished(chapterId).let { cleared ->
                    if (pages == null || active == null) cleared else cleared.withPages(active, chapterId, pages)
                }
            }
        }
    }

    /**
     * The facts a [ReaderSession] is projected from, live.
     *
     * The gate is the whole design. Both underlying queries are invalidated by the reader's own progress
     * writes — `ChapterDao.observeChapters` joins `reading_progress`, and the manga row carries a
     * `last_read` that moves on every page turn — so an ungated subscription would re-project the session
     * several times a chapter and fight the user's paging. [sameSpine] compares only what a session is
     * actually built from and ignores [Chapter.progress] entirely, so a page turn is not a spine change.
     *
     * **Accepted cost:** an open reader now holds two live Room subscriptions, and the manga one is the
     * six-table projection, re-running on each of our own progress writes only to be discarded here. The
     * gate keeps the *downstream* cost at zero but not the query's. A narrow "reading mode and scanlator
     * filter for this manga" read would fix that and is the obvious follow-up.
     */
    private fun spines(): Flow<Spine> =
        combine(observeManga(mangaId), observeChapters(mangaId)) { manga, chapters ->
            Spine(
                mangaTitle = manga?.title?.value.orEmpty(),
                readingMode = manga?.readingMode ?: RIGHT_TO_LEFT,
                hidden = manga?.filteredScanlators.orEmpty(),
                chapters = chapters
            )
        }.distinctUntilChanged(::sameSpine)

    /**
     * Whether two spines would project the same session. Deliberately field-by-field rather than a data
     * class `equals` or a `distinctUntilChangedBy` key: [Chapter] carries `progress`, which this reader
     * rewrites on every page turn and which no session reads, and building a key list per emission would
     * allocate once per chapter on exactly the writes this exists to ignore.
     */
    private fun sameSpine(
        old: Spine,
        new: Spine
    ): Boolean {
        if (old.readingMode != new.readingMode || old.hidden != new.hidden || old.mangaTitle != new.mangaTitle) return false
        if (old.chapters.size != new.chapters.size) return false
        return old.chapters.indices.all { index ->
            val a = old.chapters[index]
            val b = new.chapters[index]
            a.id == b.id && a.chapterNumber == b.chapterNumber && a.name == b.name && a.scanlator == b.scanlator
        }
    }

    /**
     * Page only through the chapters the detail screen actually shows: hide the groups the user filtered
     * out (see `Manga.filteredScanlators`), so continuous reading never flows into a duplicate chapter
     * from a hidden scanlator. The opened chapter itself is always kept — the user navigated straight to
     * it (from history, say), even if its group is otherwise hidden.
     */
    private fun siblingsOf(spine: Spine): List<Chapter> {
        val shown =
            spine.chapters
                .filter { it.id == initialChapterId || !it.isHiddenBy(spine.hidden) }
                .sortedWith(compareBy(nullsLast()) { it.chapterNumber })
        // One chapter per number. Two groups releasing the same chapter would otherwise sit side by side
        // in the spine, so "previous" from 12 (group B) was 12 (group A) and the real 11 was unreachable,
        // and "next" walked into a copy of the chapter just read. The opened chapter always stays; for
        // other numbers, the opened chapter's group wins, else the first in source order.
        val group = spine.chapters.firstOrNull { it.id == initialChapterId }?.scanlator
        return shown
            .groupBy { it.chapterNumber }
            .flatMap { (number, copies) ->
                if (number == null || copies.size == 1) {
                    copies
                } else {
                    listOf(copies.firstOrNull { it.id == initialChapterId } ?: copies.firstOrNull { it.scanlator == group } ?: copies.first())
                }
            }
    }

    /**
     * Rebuilds the session on a spine that has changed under the reader.
     *
     * One rule, and it is the important one: **a spine change that removes the chapter being read is
     * ignored.** Ripping the page list out from under someone mid-chapter is worse than showing a chapter
     * the source has since delisted, so the session is kept until they leave. Everything else needs no
     * care at all — [ReaderWindow] names its chapter by id, so a list that shifted under it re-resolves
     * through the new session's positions with nothing to remap.
     *
     * A new reading mode means a new layout, which parks on [ReaderSession.initialItemIndex] when it is
     * first composed — so that index moves to the page on screen, and the switch keeps the page.
     */
    private fun reproject(spine: Spine) {
        val current = session ?: return
        val reading = window.value.currentChapterId ?: return
        val siblings = siblingsOf(spine)
        if (siblings.none { it.id == reading }) return
        val initialItemIndex =
            if (spine.readingMode == current.readingMode) {
                current.initialItemIndex
            } else {
                (uiState.value as? Content)
                    ?.items
                    ?.indexOfFirst { it.key == focusedItemKey }
                    ?.takeIf { it >= 0 }
                    ?: current.initialItemIndex
            }
        val next = ReaderSession(siblings, spine.mangaTitle, spine.readingMode, initialItemIndex)
        if (next != current) bootstrap.value = Bootstrap.Ready(next)
    }

    /** Warms the current chapter and its successor so crossing the trailing boundary is seamless. */
    private fun warmWindow(
        session: ReaderSession,
        chapterId: Uuid?
    ) {
        val index = session.positionOf(chapterId)
        if (index < 0) return
        session.siblings.getOrNull(index)?.let { loadChapter(it.id) }
        session.siblings.getOrNull(index + 1)?.let { loadChapter(it.id) }
    }

    /**
     * Builds the [Content] for one combination of window inputs. Pure: everything it reads is a parameter,
     * so it's safe to run repeatedly from the pipeline as the inputs change.
     */
    private fun buildContent(
        session: ReaderSession,
        frame: Frame,
        pageInChapter: Int,
        chromeVisible: Boolean,
        extras: Extras
    ): Content {
        val siblings = session.siblings
        val index = session.positionOf(frame.window.currentChapterId)
        val curr = siblings[index]
        val pageCount = frame.window.pages[curr.id]?.size ?: 0
        val currentPage = pageInChapter.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        val byChapter = extras.bookmarks.groupingBy { it.chapterId }.eachCount()
        return Content(
            mangaTitle = session.mangaTitle,
            chapterUrl = curr.url.value,
            background = extras.background,
            sheet = extras.sheet,
            chaptersTab = extras.chaptersTab,
            isCurrentPageBookmarked = extras.bookmarks.any { it.chapterId == curr.id && it.page == currentPage },
            chapters =
                extras.chapterState?.let { state ->
                    siblings.asReversed().map { chapter ->
                        ReaderChapterItem(
                            id = chapter.id,
                            numberLabel = chapter.chapterNumber?.display,
                            name = chapter.name?.value,
                            scanlator = chapter.scanlator?.value,
                            isRead = chapter.id in state.readIds,
                            isDownloaded = chapter.id in state.downloadedIds,
                            isCurrent = chapter.id == curr.id,
                            bookmarkCount = byChapter[chapter.id] ?: 0
                        )
                    }
                }.orEmpty(),
            bookmarks =
                if (extras.sheet != ReaderSheet.Chapters) {
                    emptyList()
                } else {
                    extras.bookmarks.map { bookmark ->
                        val chapter = siblings.getOrNull(session.positionOf(bookmark.chapterId))
                        ReaderBookmarkItem(
                            id = bookmark.id,
                            chapterId = bookmark.chapterId,
                            chapterNumberLabel = chapter?.chapterNumber?.display,
                            chapterName = chapter?.name?.value,
                            page = bookmark.page,
                            imageUrl = (bookmark.image as? PageImage.Remote)?.url?.value,
                            imageHeaders = (bookmark.image as? PageImage.Remote)?.headers.orEmpty(),
                            localPath = (bookmark.image as? PageImage.Local)?.path
                        )
                    }
                },
            items = frame.items,
            initialItemIndex = session.initialItemIndex,
            readingMode = session.readingMode,
            chromeVisible = chromeVisible,
            currentChapterId = curr.id,
            currentPage = currentPage,
            currentPageCount = pageCount,
            chapterNumberLabel = curr.chapterNumber?.display,
            chapterName = curr.name?.value,
            hasPreviousChapter = index > 0,
            hasNextChapter = index < siblings.lastIndex
        )
    }

    /**
     * If the focused item moved within the rebuilt [items] — an adjacent chapter's pages inserted above the
     * viewport, or the chapter two behind evicted from it — tell paged layouts to re-anchor to it. Runs as
     * a side effect of each new [Content] emission. A key that is *gone* leaves the viewport alone, which
     * could only happen if the chapter on screen were evicted — something [ReaderWindow] forbids.
     */
    private fun reanchorIfNeeded(items: List<ReaderItem>) {
        val key = focusedItemKey ?: return
        val index = items.indexOfFirst { it.key == key }
        if (index >= 0 && index != lastAnchoredIndex) {
            lastAnchoredIndex = index
            _commands.trySend(ReaderCommand.Reanchor(key))
        }
    }

    /**
     * Forgets a pending jump whose target is neither held nor still loading — the window moved past it
     * while its fetch was in flight. Left armed, it would fire whenever that chapter was next re-fetched,
     * scrolling the reader out from under a user who has long since moved on.
     */
    private fun abandonUnreachableJump() {
        val target = pendingJump?.chapterId ?: return
        val current = window.value
        if (!current.holds(target) && target !in current.loading) pendingJump = null
    }

    /**
     * Completes a pending prev/next-chapter jump once that chapter's first page appears in [items] (its
     * pages finished loading). Runs as a side effect of each new [Content] emission, so the jump lands
     * the moment the freshly emitted list contains the target.
     */
    private fun resolvePendingJump(items: List<ReaderItem>) {
        val target = pendingJump ?: return
        val pages = items.filterIsInstance<PageItem>().filter { it.chapterId == target.chapterId }
        if (pages.isEmpty()) return
        pendingJump = null
        _commands.trySend(ReaderCommand.Jump(pageKey(target.chapterId, target.page.coerceIn(0, pages.lastIndex))))
    }

    /**
     * Flattens the contiguous run of loaded chapters around the current one into the reader's item list:
     * each loaded chapter's pages, with a transition slide at every boundary. Boundaries to a not-yet-loaded
     * sibling (at the two ends of the run) also get a slide, standing alone with a spinner while that
     * neighbour's pages are in flight — so the pages simply appear after it once fetched.
     *
     * The held chapters form a **contiguous** range, so walking outward from the window's index recovers
     * it: loads only ever expand the run from the current chapter, and eviction intersects it with an
     * interval containing that index (see [ReaderWindow]). The run does *not* only grow — a forward
     * crossing drops the chapter behind, so indices shift down and the viewport has to be re-anchored.
     * Both layouts do that by item key during measure; [ReaderCommand.Reanchor] covers what that misses.
     */
    private fun buildItems(
        session: ReaderSession,
        window: ReaderWindow
    ): List<ReaderItem> {
        val currentIndex = session.positionOf(window.currentChapterId)
        val siblings = session.siblings
        val cache = window.pages
        val loading = window.loading
        if (currentIndex !in siblings.indices) return emptyList()
        if (!cache.containsKey(siblings[currentIndex].id)) return emptyList()

        var lo = currentIndex
        while (lo - 1 >= 0 && cache.containsKey(siblings[lo - 1].id)) lo--
        var hi = currentIndex
        while (hi + 1 <= siblings.lastIndex && cache.containsKey(siblings[hi + 1].id)) hi++

        val result = ArrayList<ReaderItem>()

        // Leading boundary into an unloaded previous sibling.
        if (lo - 1 >= 0) {
            result.add(
                boundary(
                    from = siblings[lo - 1],
                    to = siblings[lo],
                    loadingSide = siblings[lo - 1].id,
                    cache = cache,
                    loading = loading
                )
            )
        }
        for (index in lo..hi) {
            val chapter = siblings[index]
            val chapterPages = cache.getValue(chapter.id)
            chapterPages.forEachIndexed { page, source ->
                result.add(source.toPageItem(chapter.id, page, chapterPages.size))
            }
            // Boundary to the next loaded chapter (both sides present, so never loading).
            if (index < hi) {
                result.add(
                    boundary(
                        from = chapter,
                        to = siblings[index + 1],
                        loadingSide = chapter.id,
                        cache = cache,
                        loading = loading
                    )
                )
            }
        }
        // Trailing boundary into an unloaded next sibling, or the terminal "no more chapters" slide when the
        // last loaded chapter is the last of the series.
        if (hi + 1 <= siblings.lastIndex) {
            result.add(
                boundary(
                    from = siblings[hi],
                    to = siblings[hi + 1],
                    loadingSide = siblings[hi + 1].id,
                    cache = cache,
                    loading = loading
                )
            )
        } else {
            result.add(ReaderItem.End)
        }
        return result
    }

    private fun boundary(
        from: Chapter,
        to: Chapter,
        loadingSide: Uuid,
        cache: Map<Uuid, List<ReaderPage>>,
        loading: Set<Uuid>
    ) = ReaderItem.Transition(
        from = from.ref(),
        to = to.ref(),
        loading = !cache.containsKey(loadingSide) && loadingSide in loading
    )

    private fun persistDebounced(
        chapterId: Uuid,
        page: Int,
        read: Boolean
    ) {
        pendingProgress.tryEmit(ProgressWrite(chapterId, page, read))
    }

    private suspend fun persistNow(
        chapterId: Uuid,
        page: Int,
        read: Boolean
    ) {
        // Cancellable so a write abandoned with the reader isn't logged as a failure — it didn't fail,
        // it was dropped, and the next open resumes from whatever did land.
        runCatchingCancellable { updateReadingProgress(chapterId, page.toUInt(), read = read) }
            .onFailure { logger.warn(tag, "Failed to persist progress for chapter $chapterId", it) }
    }

    private fun Chapter.ref() = ChapterRef(id, chapterNumber?.display, name?.value)

    private fun ReaderPage.toPageItem(
        chapterId: Uuid,
        page: Int,
        pageCount: Int
    ): PageItem =
        when (val source = image) {
            is PageImage.Remote -> PageItem(chapterId, page, pageCount, source.url.value, source.headers, localPath = null)
            is PageImage.Local -> PageItem(chapterId, page, pageCount, imageUrl = null, emptyMap(), source.path)
        }

    /**
     * Everything [Content] shows besides the pages: the sheet and its lists, the bookmarks, the background.
     *
     * The chapter list's read and downloaded marks come from live queries — the session's chapters are a
     * snapshot that ignores progress (see [sameSpine]) — and those queries run only while the Chapters
     * sheet is open, so a page turn never wakes them.
     */
    private fun extras(): Flow<Extras> {
        val chapterState =
            sheet.flatMapLatest { open ->
                if (open != ReaderSheet.Chapters) {
                    flowOf(null)
                } else {
                    combine(observeChapters(mangaId), observeChapterDownloads(mangaId)) { chapters, downloads ->
                        ChapterState(
                            readIds = chapters.filter { it.progress?.alreadyRead == true }.mapTo(HashSet()) { it.id },
                            downloadedIds = downloads.filterValues { it.isComplete }.keys
                        )
                    }
                }
            }
        return combine(sheet, chaptersTab, observeReaderBackground(), bookmarks, chapterState) { open, tab, background, marks, state ->
            Extras(open, tab, background, marks, state)
        }
    }

    /**
     * The window paired with the item list built from it — one value, so the two can never be combined out
     * of step, and so [buildItems] runs only when [window] actually changes.
     *
     * That is most of the point. A page turn *inside* a chapter never writes [window] at all (`movedTo`
     * returns the same value and the flow conflates it), and a chrome toggle doesn't reach this flow — so
     * neither rebuilds the list any more. What remains is a chapter crossing, which does write [window]
     * but produces a structurally identical list; the `scan` hands the **previous instance** forward in
     * that case rather than the freshly built one, because Compose skips on equality of a stable type and
     * `Content.items` is what the whole page tree hangs off. Building a list to throw it away is cheap;
     * handing the UI a new reference for an unchanged list is what costs a recomposition.
     */
    private fun ReaderSession.frames(): Flow<Frame> =
        window
            .scan(Frame.EMPTY) { previous, held ->
                val rebuilt = buildItems(this, held)
                Frame(held, if (rebuilt == previous.items) previous.items else rebuilt)
            }.drop(1)

    /** The chapter being read, changing only when the reader actually moves between chapters. */
    private fun StateFlow<ReaderWindow>.currentChapters(): Flow<Uuid?> =
        map { it.currentChapterId }.distinctUntilChanged()

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    fun interface Factory : ManualViewModelAssistedFactory {
        fun create(
            mangaId: Uuid,
            initialChapterId: Uuid,
            initialPage: Int?
        ): ReaderViewModel
    }

    /**
     * How far the reader has got resolving what it needs to render — the gate all three pipelines hang
     * off, and the single source of [ReaderState].
     *
     * A sealed stage rather than a nullable session so [Loading] and [Error] are *derived* like everything
     * else: with a nullable gate the failure case had nowhere to live and was written straight onto
     * `uiState`, which meant the pipeline was not the only writer of the screen's state.
     */
    private sealed interface Bootstrap {
        /** Opening, or reloading after a retry. */
        data object Resolving : Bootstrap

        /** The chapter or its pages could not be resolved; the UI offers a retry. */
        data object Failed : Bootstrap

        /** Ready to read. */
        data class Ready(val session: ReaderSession) : Bootstrap
    }

    /** A jump waiting for its chapter's pages: [page] is clamped to the chapter once they land. */
    private data class PendingJump(
        val chapterId: Uuid,
        val page: Int
    )

    /** See [extras]. [chapterState] is `null` while the Chapters sheet is closed. */
    private data class Extras(
        val sheet: ReaderSheet?,
        val chaptersTab: ReaderChaptersTab,
        val background: ReaderBackground,
        val bookmarks: List<PageBookmark>,
        val chapterState: ChapterState?
    )

    private data class ChapterState(
        val readIds: Set<Uuid>,
        val downloadedIds: Set<Uuid>
    )

    /** One pending progress write, queued through [pendingProgress]. */
    private data class ProgressWrite(
        val chapterId: Uuid,
        val page: Int,
        val read: Boolean
    )

    /**
     * What a [ReaderSession] is projected from: the layout, the scanlator filter, and the raw chapter list
     * as the database holds it (unsorted and unfiltered — [siblingsOf] does that).
     */
    private data class Spine(
        val mangaTitle: String,
        val readingMode: ReadingMode,
        val hidden: Set<String>,
        val chapters: List<Chapter>
    )

    /**
     * One window state and the item list derived from it. Private because it is pipeline plumbing, not
     * screen state: the UI only ever sees the [Content] built from it.
     */
    private data class Frame(
        val window: ReaderWindow,
        val items: List<ReaderItem>
    ) {
        companion object {
            /** The `scan` seed; dropped before anything downstream sees it. */
            val EMPTY = Frame(ReaderWindow.EMPTY, emptyList())
        }
    }

    internal companion object {
        /** Crash-reporting label for a chapter whose page list could not be resolved. */
        const val PAGES_UNAVAILABLE = "reader.pages_unavailable"

        /** Wait this long after the last page change before writing, so a fast swipe writes once. */
        const val PROGRESS_PERSIST_DEBOUNCE_MS = 400L

        /**
         * How long the window index must hold still before the chapters outside it are evicted.
         *
         * The index moves *mid-gesture*, not on settle — a pager's `currentPage` flips once a drag passes
         * the snap halfway point, and a strip's `firstVisibleItemIndex` changes continuously through a
         * fling — so evicting the moment it changes would pull items out from above a viewport the user
         * still has a finger on. Waiting for a quiet index means eviction only ever runs on a parked
         * reader, and as a bonus makes oscillating across a boundary free: it never drops what it is
         * about to need again.
         */
        const val EVICT_QUIET_MS = 750L

        /** How long the chrome stays up untouched before it fades. */
        const val CHROME_HIDE_MS = 3_000L
    }
}
/**
 * The warm window a link of this shape earns. Top-level and pure so the same rule seeds
 * [ReaderViewModel.prefetchBudget] and drives it, rather than being written twice.
 */
private fun budgetFor(
    status: ConnectionStatus,
    metered: Boolean
): PagePrefetchBudget =
    if (metered || status != ConnectionStatus.ONLINE) PagePrefetchBudget.Frugal else PagePrefetchBudget.Full
