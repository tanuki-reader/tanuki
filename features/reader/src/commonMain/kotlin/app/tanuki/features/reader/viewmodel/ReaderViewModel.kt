package app.tanuki.features.reader.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tanuki.core.domain.chapter.model.Chapter
import app.tanuki.core.domain.chapter.usecase.GetChapterPages
import app.tanuki.core.domain.chapter.usecase.ObserveChapters
import app.tanuki.core.domain.chapter.usecase.UpdateReadingProgress
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.common.service.warn
import app.tanuki.core.domain.manga.model.ReadingMode
import app.tanuki.core.domain.manga.usecase.ObserveManga
import app.tanuki.core.navigation.NavEvent
import app.tanuki.core.navigation.Reader
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

/**
 * Presentation logic for the reader. Compose-free (per CLAUDE.md → MVVM): it exposes state as a
 * [StateFlow] and imperative scrolls as one-shot [ReaderCommand]s, so the same logic drives the shared
 * Compose UI on Android and iOS and is unit-testable without a Compose runtime.
 *
 * ## Continuous reading
 *
 * The reader reads *across* chapters without ever reloading the screen. It keeps a sliding window of at
 * most three chapters — previous · current · next — whose pages are concatenated into one [items] list
 * with a [ReaderItem.Transition] slide between each chapter. The adjacent chapters are preloaded, so
 * reaching the last page of the current chapter and swiping once more lands on the transition slide, and
 * once more on the next chapter's first page — no navigation, no fresh ViewModel, no spinner.
 *
 * When the visible page crosses into a neighbouring chapter, the window slides one step: the far side is
 * evicted, the new neighbour is preloaded, and the viewport is re-anchored to the same page via a
 * [ReaderCommand.Reanchor] (paged layouts) or by item-key anchoring (the webtoon list). The chrome
 * (title, scrubber) always reflects whichever chapter the visible page belongs to.
 *
 * Assisted-injected with the [mangaId] and [initialChapterId] the [Reader] route carried. Metro builds
 * it through [Factory]; the UI resolves it with `assistedMetroViewModel { create(mangaId, chapterId) }`.
 *
 * All window/state mutation happens on the ViewModel's main dispatcher (UI callbacks and the bodies of
 * the load coroutines), so [pagesCache]/[currentIndex]/[items] need no extra synchronisation; only the
 * page fetch itself hops to [DispatcherProvider.io].
 */
@AssistedInject
class ReaderViewModel(
    @Assisted private val mangaId: Uuid,
    @Assisted private val initialChapterId: Uuid,
    private val getChapterPages: GetChapterPages,
    private val updateReadingProgress: UpdateReadingProgress,
    private val observeChapters: ObserveChapters,
    private val observeManga: ObserveManga,
    private val dispatcher: DispatcherProvider,
    private val logger: Logger
) : ViewModel() {
    val uiState: StateFlow<ReaderState>
        field = MutableStateFlow<ReaderState>(Loading)

    /** Screen-scoped navigation intents (only "go back" now — chapter paging is inline, not navigation). */
    private val _navEvents = Channel<NavEvent>(Channel.BUFFERED)
    val navEvents: Flow<NavEvent> = _navEvents.receiveAsFlow()

    /** Imperative scrolls the UI must apply (re-anchor after a window shift, or an explicit jump). */
    private val _commands = Channel<ReaderCommand>(Channel.BUFFERED)
    val commands: Flow<ReaderCommand> = _commands.receiveAsFlow()

    /** All sibling chapters, ascending by number — the spine the window slides along. Loaded once. */
    private var siblings: List<Chapter> = emptyList()

    /** Index into [siblings] of the chapter the visible page belongs to. */
    private var currentIndex: Int = 0

    /** Zero-based index of the visible page within the current chapter (drives the scrubber). */
    private var currentPageInChapter: Int = 0

    /** Fetched pages, keyed by chapter id. Held only for the window (prev/curr/next); others are evicted. */
    private val pagesCache = mutableMapOf<Uuid, List<app.tanuki.core.domain.source.model.Page>>()

    /** Chapters whose page fetch is in flight — so a transition can show a spinner and we don't double-load. */
    private val loadingChapters = mutableSetOf<Uuid>()

    /** How pages are laid out; read once at open (see the note on why it isn't observed). */
    private var readingMode: ReadingMode = ReadingMode.RIGHT_TO_LEFT

    /** Whether the reader chrome (top/bottom bars) is shown; toggled by tapping the page area. */
    private var chromeVisible: Boolean = true

    /** Where the pager/list parks on first composition; fixed once the first chapter resolves. */
    private var initialItemIndex: Int = 0

    /** The most recently built item list — the source the UI renders and that index math maps against. */
    private var items: List<ReaderItem> = emptyList()

    /** Key of the item the viewport is on, so a window shift can re-anchor to the same page/slide. */
    private var focusedItemKey: String? = null

    /** Last index we re-anchored the pager to, so we don't re-send a no-op [ReaderCommand.Reanchor]. */
    private var lastAnchoredIndex: Int = -1

    /** A chapter to jump to (prev/next button) once its pages have loaded. */
    private var pendingJumpChapterId: Uuid? = null

    /** Debounces progress writes so a fast swipe through pages doesn't hammer the database. */
    private var persistJob: Job? = null

    init {
        load()
    }

    /**
     * Fetches the sibling list, the reading mode, and the opened chapter's pages, then seeds the window.
     * Sibling list and reading mode are read once here — reading is a read-mostly session, and folding
     * the live chapter flow in would re-fire on our own progress writes and fight the user's paging.
     * A failure to fetch the *first* chapter's pages resolves to [Error] with a retry; adjacent-chapter
     * failures are silent (the transition simply won't gain pages).
     */
    private fun load() {
        uiState.value = Loading
        viewModelScope.launch {
            siblings =
                runCatching { observeChapters(mangaId).first() }
                    .getOrDefault(emptyList())
                    .sortedWith(compareBy(nullsLast()) { it.chapterNumber })
            currentIndex = siblings.indexOfFirst { it.id == initialChapterId }
            readingMode =
                runCatching { observeManga(mangaId).first()?.readingMode }.getOrNull()
                    ?: ReadingMode.RIGHT_TO_LEFT

            if (currentIndex < 0) {
                uiState.value = Error
                return@launch
            }

            val pages =
                runCatching { withContext(dispatcher.io) { getChapterPages(initialChapterId) } }
                    .onFailure { logger.warn(TAG, "Failed to load pages for chapter $initialChapterId", it) }
                    .getOrNull()

            if (pages == null) {
                uiState.value = Error
                return@launch
            }
            pagesCache[initialChapterId] = pages

            // Resume where the user left off, clamped in case the page count shrank since last read.
            val current = siblings[currentIndex]
            currentPageInChapter =
                (current.progress?.lastPageRead?.toInt() ?: 0)
                    .coerceIn(0, (pages.size - 1).coerceAtLeast(0))

            // The pager starts on the resume page, shifted past a leading transition when a previous
            // chapter exists (its pages aren't loaded yet, so the slide is the first item).
            val hasLeadingTransition = currentIndex > 0
            initialItemIndex = (if (hasLeadingTransition) 1 else 0) + currentPageInChapter

            items = buildItems()
            focusedItemKey = items.getOrNull(initialItemIndex)?.key
            lastAnchoredIndex = initialItemIndex
            emitContent()

            // Record the open itself: a chapter read on its single/first page never fires a page change,
            // so without this it would be missing from history / continue-reading. Stamps the resume page
            // (marking read only when that page is already the last), refreshing the history timestamp.
            val markRead = pages.isNotEmpty() && currentPageInChapter >= pages.lastIndex
            persistNow(initialChapterId, currentPageInChapter, read = markRead)

            // Warm the next chapter so crossing the trailing boundary is seamless. The previous chapter is
            // loaded lazily (when its boundary is reached) rather than prepended here, which would shove
            // the just-opened page down and force a re-anchor.
            preloadNext()
        }
    }

    /** The reader failed to load the first chapter and the user tapped retry. */
    fun onRetry() = load()

    /** Tapping the page area toggles the reader chrome (top/bottom bars). */
    fun onToggleChrome() {
        chromeVisible = !chromeVisible
        emitContent()
    }

    /**
     * The viewport settled on item [index] (a page swipe/scroll, or the result of a command). Updates the
     * chrome, persists progress, slides the window if the page belongs to a neighbouring chapter, and
     * kicks off any loading a transition slide implies.
     */
    fun onItemFocused(index: Int) {
        val item = items.getOrNull(index) ?: return
        focusedItemKey = item.key
        lastAnchoredIndex = index
        when (item) {
            is ReaderItem.PageItem -> onPageFocused(item)
            is ReaderItem.Transition -> onTransitionFocused(item)
            // The terminal slide carries no chapter of its own; the last page already recorded progress.
            ReaderItem.End -> emitContent()
        }
    }

    private fun onPageFocused(item: ReaderItem.PageItem) {
        val sibIndex = siblings.indexOfFirst { it.id == item.chapterId }
        currentPageInChapter = item.pageNumber
        if (sibIndex >= 0 && sibIndex != currentIndex) {
            // Crossed into a neighbouring chapter — advance the current chapter and warm its new
            // neighbour. The item list only ever grows (we never remove loaded pages), so a forward
            // crossing doesn't reindex what's on screen; the chrome just re-points at the new chapter.
            currentIndex = sibIndex
            rebuildAndReanchor()
            preloadNext()
        } else {
            emitContent()
        }
        persistDebounced(item.chapterId, item.pageNumber, read = item.pageNumber >= item.pageCount - 1)
    }

    private fun onTransitionFocused(item: ReaderItem.Transition) {
        // Load whichever side isn't in the cache yet, so swiping past this slide continues seamlessly.
        val toLoad =
            when {
                pagesCache[item.from.id] == null -> item.from.id
                pagesCache[item.to.id] == null -> item.to.id
                else -> null
            }
        if (toLoad != null) loadChapter(toLoad)

        // If this is the current chapter's trailing boundary, the user just finished it — mark it read.
        // (Guarded by `from == current` so a leading slide, entered by paging *back*, doesn't falsely
        // mark the previous chapter read.)
        if (item.from.id == siblings.getOrNull(currentIndex)?.id) {
            pagesCache[item.from.id]?.let { p ->
                persistDebounced(item.from.id, p.lastIndex.coerceAtLeast(0), read = true)
            }
        }
        emitContent()
    }

    /** Scrub to page [page] of the current chapter (from the bottom-chrome slider). */
    fun onScrubToPage(page: Int) {
        val currentId = siblings.getOrNull(currentIndex)?.id ?: return
        val index = items.indexOfFirst { it is ReaderItem.PageItem && it.chapterId == currentId && it.pageNumber == page }
        if (index >= 0) _commands.trySend(ReaderCommand.Jump(index))
    }

    /** Jump to the start of the next chapter (bottom-chrome button); loads it first if needed. */
    fun onNextChapter() {
        siblings.getOrNull(currentIndex + 1)?.let { jumpToChapterStart(it.id) }
    }

    /** Jump to the start of the previous chapter (bottom-chrome button); loads it first if needed. */
    fun onPreviousChapter() {
        siblings.getOrNull(currentIndex - 1)?.let { jumpToChapterStart(it.id) }
    }

    /** The back affordance was tapped. */
    fun onBack() {
        _navEvents.trySend(NavEvent.NavBack)
    }

    private fun jumpToChapterStart(chapterId: Uuid) {
        if (pagesCache[chapterId] != null) {
            val index = items.indexOfFirst { it is ReaderItem.PageItem && it.chapterId == chapterId && it.pageNumber == 0 }
            if (index >= 0) _commands.trySend(ReaderCommand.Jump(index))
        } else {
            // Not fetched yet (its transition slide is still page-less): load, then jump when it arrives.
            pendingJumpChapterId = chapterId
            loadChapter(chapterId)
        }
    }

    /** Fetches [chapterId]'s pages into the cache (if not already loading/loaded), rebuilding on completion. */
    private fun loadChapter(chapterId: Uuid) {
        if (pagesCache.containsKey(chapterId) || chapterId in loadingChapters) return
        loadingChapters.add(chapterId)
        rebuildAndReanchor() // reflect the spinner on the transition slide

        viewModelScope.launch {
            val pages =
                runCatching { withContext(dispatcher.io) { getChapterPages(chapterId) } }
                    .onFailure { logger.warn(TAG, "Failed to load pages for chapter $chapterId", it) }
                    .getOrNull()
            loadingChapters.remove(chapterId)

            if (pages != null) pagesCache[chapterId] = pages
            rebuildAndReanchor()
            handlePendingJump()
        }
    }

    /** Warms the next sibling's pages so crossing the trailing boundary is seamless (see [load] on why the
     *  previous chapter is not warmed here). */
    private fun preloadNext() {
        siblings.getOrNull(currentIndex + 1)?.let { loadChapter(it.id) }
    }

    private fun handlePendingJump() {
        val target = pendingJumpChapterId ?: return
        if (pagesCache[target] == null) return
        val index = items.indexOfFirst { it is ReaderItem.PageItem && it.chapterId == target && it.pageNumber == 0 }
        if (index >= 0) {
            pendingJumpChapterId = null
            _commands.trySend(ReaderCommand.Jump(index))
        }
    }

    /**
     * Rebuilds [items] and, if the focused item moved, tells paged layouts to re-anchor to it (the
     * webtoon list re-anchors by key on its own). Then re-emits state. Called whenever the item list's
     * shape changes: a load finished, or the window slid.
     */
    private fun rebuildAndReanchor() {
        items = buildItems()
        val index = focusedItemKey?.let { key -> items.indexOfFirst { it.key == key } } ?: -1
        if (index >= 0 && index != lastAnchoredIndex) {
            lastAnchoredIndex = index
            _commands.trySend(ReaderCommand.Reanchor(index))
        }
        emitContent()
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
    private fun buildItems(): List<ReaderItem> {
        if (siblings.isEmpty() || !pagesCache.containsKey(siblings[currentIndex].id)) return emptyList()

        var lo = currentIndex
        while (lo - 1 >= 0 && pagesCache.containsKey(siblings[lo - 1].id)) lo--
        var hi = currentIndex
        while (hi + 1 <= siblings.lastIndex && pagesCache.containsKey(siblings[hi + 1].id)) hi++

        val result = ArrayList<ReaderItem>()

        // Leading boundary into an unloaded previous sibling.
        if (lo - 1 >= 0) {
            result.add(boundary(from = siblings[lo - 1], to = siblings[lo], loadingSide = siblings[lo - 1].id))
        }
        for (index in lo..hi) {
            val chapter = siblings[index]
            val chapterPages = pagesCache.getValue(chapter.id)
            chapterPages.forEachIndexed { page, source ->
                result.add(ReaderItem.PageItem(chapter.id, page, chapterPages.size, source))
            }
            // Boundary to the next loaded chapter (both sides present, so never loading).
            if (index < hi) {
                result.add(boundary(from = chapter, to = siblings[index + 1], loadingSide = chapter.id))
            }
        }
        // Trailing boundary into an unloaded next sibling, or the terminal "no more chapters" slide when
        // the last loaded chapter is the last of the series.
        if (hi + 1 <= siblings.lastIndex) {
            result.add(boundary(from = siblings[hi], to = siblings[hi + 1], loadingSide = siblings[hi + 1].id))
        } else {
            result.add(ReaderItem.End)
        }
        return result
    }

    private fun boundary(
        from: Chapter,
        to: Chapter,
        loadingSide: Uuid
    ) = ReaderItem.Transition(
        from = from.ref(),
        to = to.ref(),
        loading = !pagesCache.containsKey(loadingSide) && loadingSide in loadingChapters
    )

    private fun emitContent() {
        val curr = siblings.getOrNull(currentIndex) ?: return
        val pageCount = pagesCache[curr.id]?.size ?: 0
        uiState.value =
            Content(
                items = items,
                initialItemIndex = initialItemIndex,
                readingMode = readingMode,
                chromeVisible = chromeVisible,
                currentChapterId = curr.id,
                currentPage = currentPageInChapter.coerceIn(0, (pageCount - 1).coerceAtLeast(0)),
                currentPageCount = pageCount,
                chapterNumber = curr.chapterNumber,
                chapterName = curr.name?.value,
                hasPreviousChapter = currentIndex > 0,
                hasNextChapter = currentIndex < siblings.lastIndex
            )
    }

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
            .onFailure { logger.warn(TAG, "Failed to persist progress for chapter $chapterId", it) }
    }

    private fun Chapter.ref() = ChapterRef(id, chapterNumber, name?.value)

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
        const val TAG = "ReaderViewModel"

        /** Wait this long after the last page change before writing, so a fast swipe writes once. */
        const val PROGRESS_PERSIST_DEBOUNCE_MS = 400L
    }
}