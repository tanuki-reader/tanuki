package app.tanuki.features.reader.viewmodel

import androidx.lifecycle.viewModelScope
import app.tanuki.core.domain.chapter.model.Chapter
import app.tanuki.core.domain.chapter.model.ReaderPage
import app.tanuki.core.domain.chapter.model.isHiddenBy
import app.tanuki.core.domain.chapter.usecase.GetReaderPages
import app.tanuki.core.domain.chapter.usecase.ObserveChapters
import app.tanuki.core.domain.chapter.usecase.UpdateReadingProgress
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.common.service.warn
import app.tanuki.core.domain.manga.model.ReadingMode
import app.tanuki.core.domain.manga.model.ReadingMode.RIGHT_TO_LEFT
import app.tanuki.core.domain.manga.usecase.ObserveManga
import app.tanuki.core.navigation.Reader
import app.tanuki.core.presentation.TanukiViewModel
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
 * When the visible page crosses into a neighbouring chapter, the window slides one step: the new neighbour
 * is preloaded and the viewport is re-anchored to the same page via a [ReaderCommand.Reanchor] (paged
 * layouts) or by item-key anchoring (the webtoon list). The chrome (title, scrubber) always reflects
 * whichever chapter the visible page belongs to.
 *
 * ## Reactive shape (mirrors the other feature ViewModels)
 *
 * The window inputs — [currentIndex], [currentPageInChapter], [pagesCache], [loadingChapters] and
 * [chromeVisible] — are [MutableStateFlow]s that a single `combine` folds into [Content]. Any change
 * re-derives the item list and re-emits with no imperative `emit` call: a page focus that advances the
 * chapter (moves [currentIndex]), a fetched chapter landing in the cache (updates [pagesCache]), a chapter
 * fetch starting/finishing (updates [loadingChapters]) or a chrome toggle all flow through the same
 * pipeline. **Page fetching is itself flow-driven:** every [currentIndex] change drives [warmWindow],
 * which loads the current chapter and warms its successor.
 *
 * The only imperative outputs are the one-shot scroll [ReaderCommand]s — a re-anchor after the list grew
 * above the viewport, or an explicit jump — computed as a side effect of each newly built item list (see
 * the content pipeline's `onEach`), which keeps every command referencing the list that was just emitted.
 *
 * Assisted-injected with the [mangaId] and [initialChapterId] the [Reader] route carried. Metro builds
 * it through [Factory]; the UI resolves it with `assistedMetroViewModel { create(mangaId, chapterId) }`.
 *
 * All flow mutation happens on the ViewModel's main-dispatched scope, so the item-list bookkeeping needs
 * no extra synchronisation; only the page fetch itself hops to [DispatcherProvider.io].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@AssistedInject
class ReaderViewModel(
    @Assisted private val mangaId: Uuid,
    @Assisted private val initialChapterId: Uuid,
    private val getReaderPages: GetReaderPages,
    private val updateReadingProgress: UpdateReadingProgress,
    private val observeChapters: ObserveChapters,
    private val observeManga: ObserveManga,
    dispatcher: DispatcherProvider,
    logger: Logger
) : TanukiViewModel(dispatcher, logger) {
    val uiState: StateFlow<ReaderState>
        field = MutableStateFlow<ReaderState>(Loading)

    /** Imperative scrolls the UI must apply (re-anchor after a window shift, or an explicit jump). */
    private val _commands = Channel<ReaderCommand>(Channel.BUFFERED)
    val commands: Flow<ReaderCommand> = _commands.receiveAsFlow()

    /**
     * The read-mostly facts resolved once when the reader opens (see [load] on why they aren't observed),
     * published as a flow so it can *gate* the reactive pipelines: `null` means "still bootstrapping / a
     * reload is in flight" and the pipelines fall back to [Loading]; a non-null value starts them.
     */
    private val session = MutableStateFlow<ReaderSession?>(null)

    /** Index into [ReaderSession.siblings] of the chapter the visible page belongs to — drives the window. */
    private val currentIndex = MutableStateFlow(0)

    /** Zero-based index of the visible page within the current chapter (drives the scrubber). */
    private val currentPageInChapter = MutableStateFlow(0)

    /** Whether the reader chrome (top/bottom bars) is shown; toggled by tapping the page area. */
    private val chromeVisible = MutableStateFlow(true)

    /** Fetched pages, keyed by chapter id. Held only for the window (prev/curr/next); others are evicted. */
    private val pagesCache = MutableStateFlow<Map<Uuid, List<ReaderPage>>>(emptyMap())

    /** Chapters whose page fetch is in flight — so a transition can show a spinner and we don't double-load. */
    private val loadingChapters = MutableStateFlow<Set<Uuid>>(emptySet())

    /** Key of the item the viewport is on, so a window shift can re-anchor to the same page/slide. */
    private var focusedItemKey: String? = null

    /** Last index we re-anchored the pager to, so we don't re-send a no-op [ReaderCommand.Reanchor]. */
    private var lastAnchoredIndex: Int = -1

    /** A chapter to jump to (prev/next button) once its pages have loaded; resolved by the content pipeline. */
    private var pendingJumpChapterId: Uuid? = null

    /** Debounces progress writes so a fast swipe through pages doesn't hammer the database. */
    private var persistJob: Job? = null

    init {
        // Content pipeline: fold the window inputs into [Content] whenever any of them changes. Gated on
        // [session] via flatMapLatest so a reload (session → null → new) cleanly tears the combine down and
        // shows [Loading] in between, instead of building an item list against a half-reset cache.
        session
            .flatMapLatest { active ->
                if (active == null) {
                    flowOf<ReaderState>(Loading)
                } else {
                    combine(
                        currentIndex,
                        currentPageInChapter,
                        pagesCache,
                        loadingChapters,
                        chromeVisible
                    ) { index, pageInChapter, cache, loading, chrome ->
                        buildContent(active, index, pageInChapter, cache, loading, chrome)
                    }
                }
            }.onEach { state ->
                // State first, then the scroll side effects — so every command references the item list
                // that is now in [uiState] (a Reanchor/Jump index is only valid against the emitted list).
                uiState.value = state
                if (state is Content) {
                    reanchorIfNeeded(state.items)
                    resolvePendingJump(state.items)
                }
            }.launchIn(viewModelScope)

        // Fetch pipeline: every [currentIndex] change warms the window (current + next). Also gated on the
        // session so it can't fetch against stale siblings during a reload.
        session
            .flatMapLatest { active ->
                if (active == null) emptyFlow() else currentIndex.map { index -> active to index }
            }.onEach { (active, index) -> warmWindow(active, index) }
            .launchIn(viewModelScope)

        load()
    }

    /**
     * Resolves the sibling list, the reading mode and the opened chapter's pages, seeds the window inputs,
     * then publishes the [ReaderSession] that starts the reactive pipelines.
     *
     * Sibling list and reading mode are read once here — reading is a read-mostly session, and folding the
     * live chapter flow in would re-fire on our own progress writes and fight the user's paging. A failure
     * to fetch the *first* chapter's pages resolves to [Error] with a retry; adjacent-chapter failures are
     * silent (the transition simply won't gain pages).
     */
    private fun load() {
        uiState.value = Loading
        // Reset every input so a retry starts clean; nulling the session parks both pipelines on Loading /
        // idle (via flatMapLatest) until the new session lands.
        session.value = null
        pagesCache.value = emptyMap()
        loadingChapters.value = emptySet()
        focusedItemKey = null
        lastAnchoredIndex = -1
        pendingJumpChapterId = null

        viewModelScope.launch {
            val manga = runCatching { observeManga(mangaId).first() }.getOrNull()
            val readingMode = manga?.readingMode ?: RIGHT_TO_LEFT

            // Page only through the chapters the detail screen actually shows: hide the groups the user
            // filtered out (see Manga.filteredScanlators), so continuous reading never flows into a
            // duplicate chapter from a hidden scanlator. The opened chapter itself is always kept — the
            // user navigated straight to it (from history, say), even if its group is otherwise hidden.
            val hidden = manga?.filteredScanlators.orEmpty()
            val siblings =
                runCatching { observeChapters(mangaId).first() }
                    .getOrDefault(emptyList())
                    .filter { it.id == initialChapterId || !it.isHiddenBy(hidden) }
                    .sortedWith(compareBy(nullsLast()) { it.chapterNumber })
            val startIndex = siblings.indexOfFirst { it.id == initialChapterId }

            if (startIndex < 0) {
                uiState.value = Error
                return@launch
            }

            val pages =
                tryIo("Failed to load pages for chapter $initialChapterId") {
                    getReaderPages(initialChapterId)
                }.getOrNull()

            if (pages == null) {
                uiState.value = Error
                return@launch
            }

            // Resume where the user left off, clamped in case the page count shrank since last read.
            val resumePage =
                (siblings[startIndex].progress?.lastPageRead?.toInt() ?: 0)
                    .coerceIn(0, (pages.size - 1).coerceAtLeast(0))

            // The pager starts on the resume page, shifted past a leading transition when a previous chapter
            // exists (its pages aren't loaded yet, so the slide is the first item).
            val hasLeadingTransition = startIndex > 0
            val initialItemIndex = (if (hasLeadingTransition) 1 else 0) + resumePage

            // Seed the window inputs and the re-anchor bookkeeping BEFORE publishing the session, so the
            // first [Content] the pipeline builds already parks on the resume page with no stray re-anchor.
            pagesCache.value = mapOf(initialChapterId to pages)
            currentPageInChapter.value = resumePage
            currentIndex.value = startIndex
            focusedItemKey =
                buildItems(siblings, startIndex, pagesCache.value, loadingChapters.value)
                    .getOrNull(initialItemIndex)
                    ?.key
            lastAnchoredIndex = initialItemIndex

            // Record the open itself: a chapter read on its single/first page never fires a page change, so
            // without this it would be missing from history / continue-reading. Stamps the resume page
            // (marking read only when that page is already the last), refreshing the history timestamp.
            val markRead = pages.isNotEmpty() && resumePage >= pages.lastIndex
            persistNow(initialChapterId, resumePage, read = markRead)

            // Publishing the session starts the content + warm pipelines; the warm pipeline preloads the
            // next chapter (so crossing the trailing boundary is seamless). The previous chapter is loaded
            // lazily — when its leading transition is focused — rather than prepended here, which would
            // shove the just-opened page down and force a re-anchor.
            session.value = ReaderSession(siblings, readingMode, initialItemIndex)
        }
    }

    /** The reader failed to load the first chapter and the user tapped retry. */
    fun onRetry() = load()

    /** Tapping the page area toggles the reader chrome (top/bottom bars). */
    fun onToggleChrome() {
        chromeVisible.update { !it }
    }

    /**
     * The viewport settled on item [index] (a page swipe/scroll, or the result of a command). Updates the
     * chrome, persists progress, slides the window if the page belongs to a neighbouring chapter, and kicks
     * off any loading a transition slide implies.
     *
     * The [index] refers to the list the UI is currently rendering — i.e. the last emitted [Content] — so
     * this resolves it against [uiState], not a freshly rebuilt list.
     */
    fun onItemFocused(index: Int) {
        val content = uiState.value as? Content ?: return
        val item = content.items.getOrNull(index) ?: return
        focusedItemKey = item.key
        lastAnchoredIndex = index
        when (item) {
            is PageItem -> onPageFocused(item)

            is ReaderItem.Transition -> onTransitionFocused(item)

            // The terminal slide carries no chapter of its own; the last page already recorded progress.
            ReaderItem.End -> Unit
        }
    }

    private fun onPageFocused(item: PageItem) {
        val siblings = session.value?.siblings ?: return
        val sibIndex = siblings.indexOfFirst { it.id == item.chapterId }
        currentPageInChapter.value = item.pageNumber
        if (sibIndex >= 0 && sibIndex != currentIndex.value) {
            // Crossed into a neighbouring chapter — moving [currentIndex] re-points the chrome (via the
            // content pipeline) and warms the new neighbour (via the fetch pipeline). The item list only
            // ever grows, so a forward crossing doesn't reindex what's on screen.
            currentIndex.value = sibIndex
        }
        persistDebounced(item.chapterId, item.pageNumber, read = item.pageNumber >= item.pageCount - 1)
    }

    private fun onTransitionFocused(item: ReaderItem.Transition) {
        // Load whichever side isn't in the cache yet, so swiping past this slide continues seamlessly.
        val toLoad =
            when {
                !pagesCache.value.containsKey(item.from.id) -> item.from.id
                !pagesCache.value.containsKey(item.to.id) -> item.to.id
                else -> null
            }
        if (toLoad != null) loadChapter(toLoad)

        // If this is the current chapter's trailing boundary, the user just finished it — mark it read.
        // (Guarded by `from == current` so a leading slide, entered by paging *back*, doesn't falsely mark
        // the previous chapter read.)
        val siblings = session.value?.siblings ?: return
        if (item.from.id == siblings.getOrNull(currentIndex.value)?.id) {
            pagesCache.value[item.from.id]?.let { p ->
                persistDebounced(item.from.id, p.lastIndex.coerceAtLeast(0), read = true)
            }
        }
    }

    /** Scrub to page [page] of the current chapter (from the bottom-chrome slider). */
    fun onScrubToPage(page: Int) {
        val content = uiState.value as? Content ?: return
        val index =
            content.items.indexOfFirst {
                it is PageItem && it.chapterId == content.currentChapterId && it.pageNumber == page
            }
        if (index >= 0) _commands.trySend(ReaderCommand.Jump(index))
    }

    /** Jump to the start of the next chapter (bottom-chrome button); loads it first if needed. */
    fun onNextChapter() {
        val siblings = session.value?.siblings ?: return
        siblings.getOrNull(currentIndex.value + 1)?.let { jumpToChapterStart(it.id) }
    }

    /** Jump to the start of the previous chapter (bottom-chrome button); loads it first if needed. */
    fun onPreviousChapter() {
        val siblings = session.value?.siblings ?: return
        siblings.getOrNull(currentIndex.value - 1)?.let { jumpToChapterStart(it.id) }
    }

    /** The back affordance was tapped. */
    fun onBack() {
        navigateBack()
    }

    private fun jumpToChapterStart(chapterId: Uuid) {
        if (pagesCache.value.containsKey(chapterId)) {
            val content = uiState.value as? Content ?: return
            val index =
                content.items.indexOfFirst {
                    it is PageItem && it.chapterId == chapterId && it.pageNumber == 0
                }
            if (index >= 0) _commands.trySend(ReaderCommand.Jump(index))
        } else {
            // Not fetched yet (its transition slide is still page-less): load, and the content pipeline
            // performs the jump once the pages land (see [resolvePendingJump]).
            pendingJumpChapterId = chapterId
            loadChapter(chapterId)
        }
    }

    /**
     * Fetches [chapterId]'s pages into the cache (if not already loading/loaded). The [loadingChapters] and
     * [pagesCache] writes flow into the content pipeline, which rebuilds the item list (spinner while in
     * flight, pages once resolved) on its own — this function only mutates the flows.
     */
    private fun loadChapter(chapterId: Uuid) {
        if (pagesCache.value.containsKey(chapterId) || chapterId in loadingChapters.value) return
        loadingChapters.update { it + chapterId } // reflects the spinner on the transition slide

        viewModelScope.launch {
            val pages =
                tryIo("Failed to load pages for chapter $chapterId") { getReaderPages(chapterId) }
                    .getOrNull()
            loadingChapters.update { it - chapterId }
            if (pages != null) pagesCache.update { it + (chapterId to pages) }
        }
    }

    /** Warms the current chapter and its successor so crossing the trailing boundary is seamless. */
    private fun warmWindow(
        session: ReaderSession,
        index: Int
    ) {
        session.siblings.getOrNull(index)?.let { loadChapter(it.id) }
        session.siblings.getOrNull(index + 1)?.let { loadChapter(it.id) }
    }

    /**
     * Builds the [Content] for one combination of window inputs. Pure: everything it reads is a parameter,
     * so it's safe to run repeatedly from the pipeline as the inputs change.
     */
    private fun buildContent(
        session: ReaderSession,
        index: Int,
        pageInChapter: Int,
        cache: Map<Uuid, List<ReaderPage>>,
        loading: Set<Uuid>,
        chromeVisible: Boolean
    ): Content {
        val siblings = session.siblings
        val curr = siblings[index]
        val pageCount = cache[curr.id]?.size ?: 0
        return Content(
            items = buildItems(siblings, index, cache, loading),
            initialItemIndex = session.initialItemIndex,
            readingMode = session.readingMode,
            chromeVisible = chromeVisible,
            currentChapterId = curr.id,
            currentPage = pageInChapter.coerceIn(0, (pageCount - 1).coerceAtLeast(0)),
            currentPageCount = pageCount,
            chapterNumber = curr.chapterNumber,
            chapterName = curr.name?.value,
            hasPreviousChapter = index > 0,
            hasNextChapter = index < siblings.lastIndex
        )
    }

    /**
     * If the focused item moved within the rebuilt [items] (an adjacent chapter's pages were inserted
     * above the viewport), tell paged layouts to re-anchor to it; the webtoon list re-anchors by key on
     * its own and ignores the command. Runs as a side effect of each new [Content] emission.
     */
    private fun reanchorIfNeeded(items: List<ReaderItem>) {
        val index = focusedItemKey?.let { key -> items.indexOfFirst { it.key == key } } ?: -1
        if (index >= 0 && index != lastAnchoredIndex) {
            lastAnchoredIndex = index
            _commands.trySend(ReaderCommand.Reanchor(index))
        }
    }

    /**
     * Completes a pending prev/next-chapter jump once that chapter's first page appears in [items] (its
     * pages finished loading). Runs as a side effect of each new [Content] emission, so the jump lands
     * the moment the freshly emitted list contains the target.
     */
    private fun resolvePendingJump(items: List<ReaderItem>) {
        val target = pendingJumpChapterId ?: return
        val index =
            items.indexOfFirst { it is PageItem && it.chapterId == target && it.pageNumber == 0 }
        if (index >= 0) {
            pendingJumpChapterId = null
            _commands.trySend(ReaderCommand.Jump(index))
        }
    }

    /**
     * Flattens the contiguous run of loaded chapters around the current one into the reader's item list:
     * each loaded chapter's pages, with a transition slide at every boundary. Boundaries to a not-yet-loaded
     * sibling (at the two ends of the run) also get a slide, standing alone with a spinner while that
     * neighbour's pages are in flight — so the pages simply appear after it once fetched.
     *
     * Because loads only ever expand from the current chapter outward, the loaded chapters form a
     * contiguous range; walking outward from [currentIndex] while pages are cached recovers it. The list
     * only grows, which keeps a forward crossing free of any re-indexing.
     */
    private fun buildItems(
        siblings: List<Chapter>,
        currentIndex: Int,
        cache: Map<Uuid, List<ReaderPage>>,
        loading: Set<Uuid>
    ): List<ReaderItem> {
        if (siblings.isEmpty() || !cache.containsKey(siblings[currentIndex].id)) return emptyList()

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
                result.add(PageItem(chapter.id, page, chapterPages.size, source))
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
        persistJob?.cancel()
        persistJob =
            viewModelScope.launch {
                delay(PROGRESS_PERSIST_DEBOUNCE_MS)
                persistNow(chapterId, page, read)
            }
    }

    private suspend fun persistNow(
        chapterId: Uuid,
        page: Int,
        read: Boolean
    ) {
        runCatching { updateReadingProgress(chapterId, page.toUInt(), read = read) }
            .onFailure { logger.warn(tag, "Failed to persist progress for chapter $chapterId", it) }
    }

    private fun Chapter.ref() = ChapterRef(id, chapterNumber, name?.value)

    /**
     * The read-mostly facts resolved once when the reader opens: the chapter spine the window slides along,
     * the layout to render pages with, and where the pager parks on first composition. Snapshotted (not
     * observed) so our own progress writes don't re-fire the pipeline and fight the user's paging.
     *
     * @property siblings All sibling chapters, ascending by number.
     * @property readingMode How pages are laid out.
     * @property initialItemIndex Where the pager/list parks on first composition (resume page, offset by a
     *   leading transition). Fixed for the session — later window shifts are driven by scroll commands.
     */
    private data class ReaderSession(
        val siblings: List<Chapter>,
        val readingMode: ReadingMode,
        val initialItemIndex: Int
    )

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    fun interface Factory : ManualViewModelAssistedFactory {
        fun create(
            mangaId: Uuid,
            initialChapterId: Uuid
        ): ReaderViewModel
    }

    private companion object {
        /** Wait this long after the last page change before writing, so a fast swipe writes once. */
        const val PROGRESS_PERSIST_DEBOUNCE_MS = 400L
    }
}