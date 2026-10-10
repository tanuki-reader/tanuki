package app.tanuki.features.home.viewmodel

import androidx.lifecycle.ViewModel
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.history.model.HistoryEntry
import app.tanuki.core.domain.history.usecase.ObserveLibraryReadingHistory
import app.tanuki.core.domain.source.model.SourceMeta
import app.tanuki.core.domain.source.usecase.GetInstalledSources
import app.tanuki.core.domain.update.model.NewChapter
import app.tanuki.core.domain.update.usecase.ObserveNewChapters
import app.tanuki.core.navigation.MangaDetail
import app.tanuki.core.navigation.Reader
import app.tanuki.core.navigation.SourceBrowse
import app.tanuki.core.presentation.StateViewModel
import app.tanuki.core.presentation.elapsedSince
import app.tanuki.features.home.viewmodel.HomeState.Content
import app.tanuki.features.home.viewmodel.HomeState.Loading
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlin.math.floor

/**
 * Presentation logic for the Home tab. Compose-free (CLAUDE.md → MVVM); Metro-injected into the
 * ViewModel multibinding so `metroViewModel<HomeViewModel>()` resolves it, like `LibraryViewModel`.
 *
 * Feeds both of Home's views from one state:
 *  - **For You** — [ObserveLibraryReadingHistory], which already returns exactly the shortlist: one
 *    "continue reading" entry per library series, most-recently-read first and capped, each joined to
 *    its manga and chapter — plus [ObserveNewChapters] for the chapters that have arrived since and
 *    not been opened.
 *  - **Sources** — the installed bundles' metadata, bucketed by language into collapsible groups.
 *
 * All three are live [Flow]s and are combined into one state. [ObserveNewChapters] is live in the
 * strong sense: it holds the series and chapters it named under observation, so the New Chapters list
 * corrects itself when one of them is read, filtered or unfollowed from another screen — Home does
 * nothing to maintain it. Sources re-emit only when the installed set actually changes — reading each bundle's metadata is comparatively expensive — so a source being
 * installed or uninstalled updates the list in place, without the screen holding a stale snapshot and
 * without re-enumerating on every history or update tick.
 */
@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
class HomeViewModel(
    observeLibraryReadingHistory: ObserveLibraryReadingHistory,
    observeNewChapters: ObserveNewChapters,
    getInstalledSources: GetInstalledSources,
    dispatcher: DispatcherProvider,
    logger: Logger
) : StateViewModel<HomeState>(dispatcher, logger, Loading) {
    /**
     * The one expanded language group, or `null` when none is — which is also how the list starts.
     *
     * One at a time rather than a set: the groups are long, and several open at once turns the whole
     * screen into a scroll with no shape to it. Starting closed makes the *languages* the thing the user
     * reads first, which is the choice the grouping exists to offer; opening every group by default
     * would bury it under the sources of whichever language sorts first.
     */
    private val expandedLang = MutableStateFlow<String?>(null)

    /** The view the segmented control shows. Opens on For You, the landing view. */
    private val segment = MutableStateFlow(HomeSegment.ForYou)

    /**
     * No `flowOn` beyond the one `asState` applies: SourceLoader dispatches its own engine work (and the
     * bundle reads beneath it hop to `io` themselves), so another hop would only bounce before switching.
     *
     * The [Loading] gate is [combine] itself, with nothing standing in for "not enumerated yet": a
     * combine emits only once *every* arm has, and [GetInstalledSources] is silent until the bundles have
     * actually been read — it is a directory listing followed by a `SOURCE_META` decode per bundle, not a
     * value that starts empty. So the Sources view cannot flash its "no sources" empty state at a runtime
     * that simply hasn't answered yet, and an enumeration that genuinely finds none still reaches
     * [Content]. The same holds when the pipeline restarts after the screen has been away: it re-emits
     * nothing until the arms answer again, and [uiState] keeps sharing the last value meanwhile.
     */
    override val state: Flow<HomeState> =
        combine(
            observeLibraryReadingHistory(CONTINUE_READING_LIMIT),
            observeNewChapters(),
            getInstalledSources(),
            expandedLang,
            segment
        ) { history, updates, installed, expanded, segment ->
            Content(
                segment = segment,
                continueReading = history.toContinueReading(),
                newChapters = updates.map(NewChapter::toItem),
                sourceGroups = installed.toLanguageGroups(expanded)
            )
        }

    /** The segmented control switched to [segment]. */
    fun onSelectSegment(segment: HomeSegment) {
        this.segment.value = segment
    }

    /** A language group header was tapped — open it, or close it if it was already the open one. */
    fun onToggleLanguage(lang: String) {
        expandedLang.value = if (lang == expandedLang.value) null else lang
    }

    /** A source row was tapped — open its browse leaf (pushed onto the Home tab's stack). */
    fun onSourceClick(sourceId: String) {
        navigateTo(SourceBrowse(sourceId))
    }

    /**
     * A "Continue Reading" card was tapped — resume its chapter in the reader, or fall back to the
     * series' detail when that chapter has since been pruned from the source.
     */
    fun onContinueReadingClick(item: ContinueReadingItem) {
        val destination =
            item.chapterId?.let { Reader(mangaId = item.mangaId, chapterId = it) }
                ?: MangaDetail(item.mangaId)
        navigateTo(destination)
    }

    /**
     * A "New Chapters" row was tapped — open that chapter in the reader. Nothing is dismissed here: the
     * reader records the chapter as opened, which is what drops it from the list (see
     * [app.tanuki.core.domain.update.usecase.ResolveChapterUpdates]) — and because the list observes
     * that chapter's row, it does so while the user is still in the reader, not at some later read.
     */
    fun onNewChapterClick(item: NewChapterItem) {
        navigateTo(Reader(mangaId = item.mangaId, chapterId = item.chapterId))
    }

    /**
     * Projects the For You carousel's entries onto their cards. Nothing is filtered or truncated here:
     * [ObserveLibraryReadingHistory] already returns library titles only, one per series at its most
     * recent read, capped at [CONTINUE_READING_LIMIT] — so this is one card per entry, in order.
     */
    private fun List<HistoryEntry>.toContinueReading(): List<ContinueReadingItem> =
        map { entry ->
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
        }

    /**
     * Buckets sources by the language they serve, ordering both the groups and the sources inside them
     * alphabetically so the list is stable across reloads. Exactly the group named by [expanded] is open,
     * and none is when it is `null`.
     */
    private fun List<SourceMeta>.toLanguageGroups(expanded: String?): List<SourceLanguageGroup> =
        groupBy { it.lang.value }
            // `entries.sortedBy` rather than `toSortedMap()` — the latter is JVM-only and this is
            // common code that also compiles for iOS.
            .entries
            .sortedBy { it.key }
            .map { (lang, metas) ->
                SourceLanguageGroup(
                    lang = lang,
                    expanded = lang == expanded,
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
        /**
         * How many series the Continue Reading row shows before it stops being a shortlist. Passed down
         * as the query's `LIMIT`, so it bounds what is read rather than trimming what was already loaded.
         */
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
        // ponytail: bucketed when the list emits, not on a timer, so a caption can lag while the screen
        // sits open. Same as the composable it replaced; add a ticking input if that ever shows.
        discoveredAgo = elapsedSince(discoveredAt)
    )

/**
 * Formats a chapter number for display: whole chapters lose the decimal ("7"), subchapters keep it
 * ("15.3"). Not localizable copy — a chapter number is content, like a title.
 */
private fun Double.formatChapterNumber(): String = if (this == floor(this)) toInt().toString() else toString()