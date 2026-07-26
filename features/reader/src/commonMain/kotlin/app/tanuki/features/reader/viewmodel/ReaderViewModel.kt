package app.tanuki.features.reader.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tanuki.core.domain.chapter.model.ChapterNumber
import app.tanuki.core.domain.chapter.usecase.GetChapterPages
import app.tanuki.core.domain.chapter.usecase.ObserveChapters
import app.tanuki.core.domain.chapter.usecase.UpdateReadingProgress
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.common.service.warn
import app.tanuki.core.domain.manga.model.ReadingMode
import app.tanuki.core.domain.manga.usecase.ObserveManga
import app.tanuki.core.domain.source.model.Page
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

/**
 * Presentation logic for the reader. Compose-free (per CLAUDE.md → MVVM): it exposes state as a
 * [StateFlow] and screen-scoped navigation as one-shot [NavEvent]s, so the same logic drives the shared
 * Compose UI on Android and iOS and is unit-testable without a Compose runtime.
 *
 * Assisted-injected with the [mangaId] and [chapterId] the [Reader] route carried — [mangaId] so it can
 * page across sibling chapters, [chapterId] to know which one to open. Metro builds it through [Factory]
 * (registered into the app's manual-assisted-factory multibinding); the UI resolves it with
 * `assistedMetroViewModel { create(mangaId, chapterId) }`, scoped to the destination's back-stack entry.
 *
 * State is assembled the same way as the other feature ViewModels: a [combine] pipeline whose output is
 * pushed to [uiState] via `onEach { … }.launchIn(viewModelScope)`. Its inputs are the one-shot page
 * fetch ([loaded], `null` until it resolves — the [Loading] gate, exactly like `HomeViewModel`'s
 * `sources`) plus the two purely-local UI flows [currentPage] and [chromeVisible].
 *
 * Note the reader deliberately does **not** react to [ObserveChapters]/[ObserveManga] the way the detail
 * screen does: it reads them once in [load]. Reading is a read-mostly session, and folding the live
 * chapter flow into the pipeline would re-fire it on the reader's *own* progress writes and fight the
 * user's paging. The chapter list and reading mode are already cached by the time the reader opens.
 *
 * Cross-chapter paging is expressed as navigation, not in-place mutation: advancing past the last page
 * emits a [NavEvent] to a fresh [Reader] route, so each chapter gets its own back-stack entry (and its
 * own ViewModel) and the system back button steps chapter-by-chapter.
 */
@AssistedInject
class ReaderViewModel(
    @Assisted private val mangaId: Uuid,
    @Assisted private val chapterId: Uuid,
    private val getChapterPages: GetChapterPages,
    private val updateReadingProgress: UpdateReadingProgress,
    private val observeChapters: ObserveChapters,
    private val observeManga: ObserveManga,
    private val dispatcher: DispatcherProvider,
    private val logger: Logger
) : ViewModel() {
    val uiState: StateFlow<ReaderState>
        field = MutableStateFlow<ReaderState>(Loading)

    /** Screen-scoped navigation intents (open an adjacent chapter, or go back). One-shot and buffered. */
    private val _navEvents = Channel<NavEvent>(Channel.BUFFERED)
    val navEvents: Flow<NavEvent> = _navEvents.receiveAsFlow()

    /**
     * The one-shot page fetch and the chapter metadata around it. `null` until [load] resolves, which
     * holds the screen on [Loading] — otherwise the pipeline would emit a page-less [Content] first.
     */
    private val loaded = MutableStateFlow<LoadResult?>(null)

    /** Zero-based index of the visible page — local UI state the pager reports back via [onPageChanged]. */
    private val currentPage = MutableStateFlow(0)

    /** Whether the reader chrome (top/bottom bars) is shown; toggled by tapping the page area. */
    private val chromeVisible = MutableStateFlow(true)

    /** Debounces progress writes so a fast swipe through pages doesn't hammer the database. */
    private var persistJob: Job? = null

    init {
        load()

        combine(loaded, currentPage, chromeVisible) { result, page, chrome ->
            when (result) {
                null -> Loading
                LoadResult.Failed -> Error
                is LoadResult.Loaded ->
                    Content(
                        pages = result.pages,
                        currentPage = page,
                        readingMode = result.readingMode,
                        chromeVisible = chrome,
                        chapterNumber = result.chapterNumber,
                        chapterName = result.chapterName,
                        hasPreviousChapter = result.previousChapterId != null,
                        hasNextChapter = result.nextChapterId != null,
                    )
            }
        }.flowOn(dispatcher.default)
            .onEach { uiState.value = it }
            .launchIn(viewModelScope)
    }

    /**
     * Fetches the chapter's pages and the metadata the reader chrome needs, feeding [loaded]. Sibling
     * chapters and the reading mode are read once here (see the class note on why they aren't observed).
     * Failure to fetch pages resolves [loaded] to [LoadResult.Failed] rather than throwing, so the
     * pipeline can render [Error] with a retry.
     */
    private fun load() {
        loaded.value = null
        viewModelScope.launch {
            val siblings =
                runCatching { observeChapters(mangaId).first() }
                    .getOrDefault(emptyList())
                    .sortedWith(compareBy(nullsLast()) { it.chapterNumber })
            val index = siblings.indexOfFirst { it.id == chapterId }
            val current = siblings.getOrNull(index)
            val readingMode =
                runCatching { observeManga(mangaId).first()?.readingMode }.getOrNull()
                    ?: ReadingMode.RIGHT_TO_LEFT

            val pages =
                runCatching { withContext(dispatcher.io) { getChapterPages(chapterId) } }
                    .onFailure { logger.warn(TAG, "Failed to load pages for chapter $chapterId", it) }
                    .getOrNull()

            if (pages == null) {
                loaded.value = LoadResult.Failed
                return@launch
            }

            // Resume where the user left off, clamped in case the page count shrank since last read.
            currentPage.value =
                (current?.progress?.lastPageRead?.toInt() ?: 0)
                    .coerceIn(0, (pages.size - 1).coerceAtLeast(0))

            loaded.value =
                LoadResult.Loaded(
                    pages = pages,
                    chapterNumber = current?.chapterNumber,
                    chapterName = current?.name?.value,
                    readingMode = readingMode,
                    previousChapterId = if (index > 0) siblings[index - 1].id else null,
                    nextChapterId = if (index in 0 until siblings.lastIndex) siblings[index + 1].id else null,
                )

            // Record the open itself: a chapter read on its single/first page never fires onPageChanged,
            // so without this it would be missing from history / continue-reading. Stamps the resume page
            // (marking read only when that page is already the last), which also refreshes the history
            // timestamp so re-opening bumps the chapter to the top of "continue reading".
            val resumePage = currentPage.value
            val markRead = pages.isNotEmpty() && resumePage >= pages.lastIndex
            runCatching { updateReadingProgress(chapterId, resumePage.toUInt(), read = markRead) }
                .onFailure { logger.warn(TAG, "Failed to record open of chapter $chapterId", it) }
        }
    }

    /** The reader failed to load pages and the user tapped retry. */
    fun onRetry() = load()

    /** Tapping the page area toggles the reader chrome (top/bottom bars). */
    fun onToggleChrome() {
        chromeVisible.update { !it }
    }

    /**
     * The visible page changed (swipe or scroll). Updates state immediately and persists progress on a
     * short debounce, marking the chapter read once its last page is reached.
     */
    fun onPageChanged(page: Int) {
        if (page == currentPage.value) return
        currentPage.value = page

        val isLastPage = page >= ((loaded.value as? LoadResult.Loaded)?.pages?.lastIndex ?: return)
        persistJob?.cancel()
        persistJob =
            viewModelScope.launch {
                delay(PROGRESS_PERSIST_DEBOUNCE_MS)
                runCatching { updateReadingProgress(chapterId, page.toUInt(), read = isLastPage) }
                    .onFailure { logger.warn(TAG, "Failed to persist progress for chapter $chapterId", it) }
            }
    }

    /** Page forward into the next chapter, if one exists. */
    fun onNextChapter() {
        val next = (loaded.value as? LoadResult.Loaded)?.nextChapterId ?: return
        _navEvents.trySend(NavEvent.NavTo(Reader(mangaId, next)))
    }

    /** Page back into the previous chapter, if one exists. */
    fun onPreviousChapter() {
        val previous = (loaded.value as? LoadResult.Loaded)?.previousChapterId ?: return
        _navEvents.trySend(NavEvent.NavTo(Reader(mangaId, previous)))
    }

    /** The back affordance was tapped. */
    fun onBack() {
        _navEvents.trySend(NavEvent.NavBack)
    }

    /**
     * The resolved page fetch plus the chapter metadata the chrome renders. Kept separate from
     * [ReaderState] so the [Loading]/[Error]/[Content] projection is a pure function of ([loaded],
     * [currentPage], [chromeVisible]) — the pipeline owns page/chrome, this owns the fetched data.
     */
    private sealed interface LoadResult {
        /** The page fetch failed; the pipeline renders [Error]. */
        data object Failed : LoadResult

        /** Pages and chapter metadata are ready; the sibling ids drive next/prev availability. */
        data class Loaded(
            val pages: List<Page>,
            val chapterNumber: ChapterNumber?,
            val chapterName: String?,
            val readingMode: ReadingMode,
            val previousChapterId: Uuid?,
            val nextChapterId: Uuid?
        ) : LoadResult
    }

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    fun interface Factory : ManualViewModelAssistedFactory {
        fun create(
            mangaId: Uuid,
            chapterId: Uuid
        ): ReaderViewModel
    }

    private companion object {
        const val TAG = "ReaderViewModel"

        /** Wait this long after the last page change before writing, so a fast swipe writes once. */
        const val PROGRESS_PERSIST_DEBOUNCE_MS = 400L
    }
}