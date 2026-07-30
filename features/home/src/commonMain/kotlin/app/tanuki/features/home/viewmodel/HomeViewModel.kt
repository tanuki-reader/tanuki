package app.tanuki.features.home.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.history.model.HistoryEntry
import app.tanuki.core.domain.history.usecase.ObserveReadHistory
import app.tanuki.core.domain.source.model.SourceMeta
import app.tanuki.core.domain.source.usecase.GetInstalledSources
import app.tanuki.core.domain.update.model.NewChapter
import app.tanuki.core.domain.update.usecase.ObserveNewChapters
import app.tanuki.core.navigation.MangaDetail
import app.tanuki.core.navigation.NavEvent
import app.tanuki.core.navigation.NavEvent.NavTo
import app.tanuki.core.navigation.Reader
import app.tanuki.core.navigation.SourceBrowse
import app.tanuki.features.home.viewmodel.HomeState.Content
import app.tanuki.features.home.viewmodel.HomeState.Loading
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlin.math.floor

/**
 * Presentation logic for the Home tab. Compose-free (CLAUDE.md → MVVM); Metro-injected into the
 * ViewModel multibinding so `metroViewModel<HomeViewModel>()` resolves it, like `LibraryViewModel`.
 *
 * Feeds both of Home's views from one state:
 *  - **For You** — [ObserveReadHistory] (which already joins each history row to its manga and chapter)
 *    reduced to one "continue reading" card per library series, most-recently-read first, plus
 *    [ObserveNewChapters] for the chapters that have arrived since and not been opened.
 *  - **Sources** — the installed bundles' metadata, bucketed by language into collapsible groups.
 *
 * Sources are enumerated **once** on init (reading each installed bundle's metadata is comparatively
 * expensive and the set only changes on install/uninstall), while history and updates are live [Flow]s
 * — so the three are combined rather than reloaded together.
 */
@Inject
@ContributesIntoMap(AppScope::class)
@ViewModelKey
class HomeViewModel(
    observeReadHistory: ObserveReadHistory,
    observeNewChapters: ObserveNewChapters,
    private val getInstalledSources: GetInstalledSources,
    private val dispatcher: DispatcherProvider
) : ViewModel() {
    val uiState: StateFlow<HomeState>
        field = MutableStateFlow<HomeState>(Loading)

    /**
     * Screen-scoped navigation intents. One-shot and buffered so a tap emitted before the host collects
     * isn't lost; the host maps these onto the active back stack.
     */
    private val _navEvents = Channel<NavEvent>(Channel.BUFFERED)
    val navEvents: Flow<NavEvent> = _navEvents.receiveAsFlow()

    /**
     * Installed sources, enumerated once on init. `null` until that first enumeration finishes, which
     * holds the screen on [Loading] — otherwise the Sources view would flash its "no sources" empty
     * state while the bundles are still being read.
     */
    private val sources = MutableStateFlow<List<SourceMeta>?>(null)

    /**
     * Which language groups are expanded, or `null` while the user hasn't touched any — in which case
     * every group renders expanded. Tracking "untouched" separately (rather than seeding the set from
     * the loaded languages) keeps the default correct even before sources finish loading.
     */
    private val expandedLangs = MutableStateFlow<Set<String>?>(null)

    init {
        viewModelScope.launch {
            // No withContext: SourceLoader dispatches its own engine work (and the bundle reads beneath
            // it hop to `io` themselves), so wrapping would only add a hop before it switched again.
            sources.value = getInstalledSources()
        }

        combine(
            observeReadHistory(),
            observeNewChapters(),
            sources,
            expandedLangs
        ) { history, updates, installed, expanded ->
            // Null `installed` = sources not enumerated yet; emit nothing so the screen stays on Loading.
            installed?.let {
                Content(
                    continueReading = history.toContinueReading(),
                    newChapters = updates.map(NewChapter::toItem),
                    sourceGroups = it.toLanguageGroups(expanded)
                )
            }
        }.filterNotNull()
            .flowOn(dispatcher.default)
            .onEach { uiState.value = it }
            .launchIn(viewModelScope)
    }

    /** A language group header was tapped — expand or collapse just that group. */
    fun onToggleLanguage(lang: String) {
        // First toggle starts from "everything expanded", the state the user is looking at.
        val current = expandedLangs.value ?: sources.value.orEmpty().mapTo(mutableSetOf()) { it.lang.value }
        expandedLangs.value = if (lang in current) current - lang else current + lang
    }

    /** A source row was tapped — open its browse leaf (pushed onto the Home tab's stack). */
    fun onSourceClick(sourceId: String) {
        _navEvents.trySend(NavTo(SourceBrowse(sourceId)))
    }

    /**
     * A "Continue Reading" card was tapped — resume its chapter in the reader, or fall back to the
     * series' detail when that chapter has since been pruned from the source.
     */
    fun onContinueReadingClick(item: ContinueReadingItem) {
        val destination =
            item.chapterId?.let { Reader(mangaId = item.mangaId, chapterId = it) }
                ?: MangaDetail(item.mangaId)
        _navEvents.trySend(NavTo(destination))
    }

    /**
     * A "New Chapters" row was tapped — open that chapter in the reader. Nothing is dismissed here: the
     * reader records the chapter as opened, which is what drops it from the list (see
     * [app.tanuki.core.domain.update.usecase.ResolveChapterUpdates]).
     */
    fun onNewChapterClick(item: NewChapterItem) {
        _navEvents.trySend(NavTo(Reader(mangaId = item.mangaId, chapterId = item.chapterId)))
    }

    /**
     * Reduces the raw history to the For You carousel: library titles only (a series read straight from
     * a source without being added isn't "continue reading"), one card per series keeping its most
     * recent read, capped so the row stays a glanceable shortlist rather than a second History screen.
     */
    private fun List<HistoryEntry>.toContinueReading(): List<ContinueReadingItem> =
        asSequence()
            .filter { it.manga.dateAdded != null }
            .distinctBy { it.manga.id }
            .take(CONTINUE_READING_LIMIT)
            .map { entry ->
                ContinueReadingItem(
                    mangaId = entry.manga.id,
                    chapterId = entry.chapter?.id,
                    title = entry.manga.title.value,
                    coverUrl =
                        entry.manga.cover.url
                            ?.value,
                    coverHeaders = entry.manga.cover.headers,
                    chapterNumberLabel =
                        entry.chapter
                            ?.chapterNumber
                            ?.value
                            ?.formatChapterNumber(),
                    chapterName =
                        entry.chapter
                            ?.name
                            ?.value
                            .orEmpty()
                )
            }.toList()

    /**
     * Buckets sources by the language they serve, ordering both the groups and the sources inside them
     * alphabetically so the list is stable across reloads. A group is expanded when [expanded] says so,
     * or always while [expanded] is `null` (the untouched default).
     */
    private fun List<SourceMeta>.toLanguageGroups(expanded: Set<String>?): List<SourceLanguageGroup> =
        groupBy { it.lang.value }
            // `entries.sortedBy` rather than `toSortedMap()` — the latter is JVM-only and this is
            // common code that also compiles for iOS.
            .entries
            .sortedBy { it.key }
            .map { (lang, metas) ->
                SourceLanguageGroup(
                    lang = lang,
                    expanded = expanded == null || lang in expanded,
                    sources =
                        metas
                            .sortedBy { it.name.value.lowercase() }
                            .map { meta ->
                                HomeSource(
                                    id = meta.id,
                                    name = meta.name.value,
                                    iconUrl = meta.iconUrl?.value
                                )
                            }
                )
            }

    private companion object {
        /** How many series the Continue Reading row shows before it stops being a shortlist. */
        const val CONTINUE_READING_LIMIT = 15
    }
}

/**
 * Projects a resolved [NewChapter] onto its row. No filtering happens here — the domain already decided
 * this chapter is still pending, and duplicating that rule in the ViewModel is how the list and the
 * notification would drift apart.
 */
private fun NewChapter.toItem(): NewChapterItem =
    NewChapterItem(
        chapterId = chapter.id,
        mangaId = manga.id,
        title = manga.title.value,
        coverUrl =
            manga.cover.url
                ?.value,
        coverHeaders = manga.cover.headers,
        chapterNumberLabel =
            chapter.chapterNumber
                ?.value
                ?.formatChapterNumber(),
        chapterName =
            chapter.name
                ?.value
                .orEmpty(),
        discoveredAt = discoveredAt
    )

/**
 * Formats a chapter number for display: whole chapters lose the decimal ("7"), subchapters keep it
 * ("15.3"). Not localizable copy — a chapter number is content, like a title.
 */
private fun Double.formatChapterNumber(): String = if (this == floor(this)) toInt().toString() else toString()