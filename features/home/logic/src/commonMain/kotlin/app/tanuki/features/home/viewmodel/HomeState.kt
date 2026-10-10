package app.tanuki.features.home.viewmodel

import app.tanuki.core.presentation.Elapsed
import kotlin.uuid.Uuid

/**
 * State of the Home tab — the app's landing surface, split into two views the screen toggles between:
 * **For You** ([continueReading] and [newChapters], activity over the user's library) and **Sources**
 * ([sourceGroups], the installed sources grouped by language).
 *
 * Both views load together into one [Content], along with which of them is showing ([Content.segment]).
 */
sealed interface HomeState {
    /** Reading history and installed sources are still loading. */
    data object Loading : HomeState

    /**
     * @property continueReading the user's in-progress library titles, most-recently-read first (one
     *   card per series).
     * @property newChapters chapters that arrived in the library and haven't been opened yet, newest
     *   first. Independent of [continueReading] — a series can appear in both, or in neither.
     * @property sourceGroups installed sources bucketed by language, each collapsible; empty renders
     *   the Sources empty state.
     * @property segment which of the two views is showing.
     */
    data class Content(
        val segment: HomeSegment,
        val continueReading: List<ContinueReadingItem>,
        val newChapters: List<NewChapterItem>,
        val sourceGroups: List<SourceLanguageGroup>
    ) : HomeState {
        /** For You has nothing to show at all — both of its sections are empty. */
        val forYouIsEmpty: Boolean get() = continueReading.isEmpty() && newChapters.isEmpty()
    }
}

/** Home's two views, picked with the segmented control under the title. */
enum class HomeSegment { ForYou, Sources }

/**
 * One "Continue Reading" card: a library series and the chapter the user last opened.
 *
 * @property mangaId the series, for navigation and as the cover seed.
 * @property chapterId the last-read chapter to resume, or `null` if it has since been pruned (the card
 *   then opens the series' detail instead).
 * @property title series title (source content, not localizable).
 * @property coverUrl remote cover URL, or `null` for the gradient fallback.
 * @property coverHeaders headers the cover fetch must carry; empty for almost every source.
 * @property chapterNumberLabel the resumable chapter's number formatted for display (e.g. "7", "15.3"),
 *   or `null` when the source didn't give a recognizable number.
 * @property chapterName the chapter's own name, shown when [chapterNumberLabel] is `null`.
 */
data class ContinueReadingItem(
    val mangaId: Uuid,
    val chapterId: Uuid?,
    val title: String,
    val coverUrl: String?,
    val coverHeaders: Map<String, String>,
    val chapterNumberLabel: String?,
    val chapterName: String
)

/**
 * One row in the For You "New Chapters" list: a chapter that arrived in a followed series and hasn't
 * been opened yet.
 *
 * Flat rather than grouped per series — a series that drops several chapters at once contributes one
 * row each, newest first, so a tap always resumes *a specific chapter* rather than dropping the user on
 * a detail screen to pick one.
 *
 * @property chapterId the chapter to open, and the row's stable list key.
 * @property mangaId the series it belongs to, carried for navigation and as the cover seed.
 * @property title series title (source content, not localizable).
 * @property coverUrl remote cover URL, or `null` for the gradient fallback.
 * @property coverHeaders headers the cover fetch must carry; empty for almost every source.
 * @property chapterNumberLabel the chapter's number formatted for display (e.g. "7", "15.3"), or `null`
 *   when the source gave no recognizable number.
 * @property chapterName the chapter's own name, shown when [chapterNumberLabel] is `null`.
 * @property discoveredAgo how long ago the chapter was found, for the row's relative caption.
 */
data class NewChapterItem(
    val chapterId: Uuid,
    val mangaId: Uuid,
    val title: String,
    val coverUrl: String?,
    val coverHeaders: Map<String, String>,
    val chapterNumberLabel: String?,
    val chapterName: String,
    val discoveredAgo: Elapsed
)

/**
 * A collapsible language bucket in the Sources view.
 *
 * @property lang the language tag the sources serve (the group header).
 * @property expanded whether the group's rows are shown.
 * @property sources the sources in this language, ordered for display.
 */
data class SourceLanguageGroup(
    val lang: String,
    val expanded: Boolean,
    val sources: List<HomeSource>
)

/**
 * One source row in a [SourceLanguageGroup].
 *
 * @property id source-native id the browse leaf is opened with.
 * @property name display name.
 * @property iconUrl remote icon URL, or `null` for the lettered avatar fallback.
 */
data class HomeSource(
    val id: String,
    val name: String,
    val iconUrl: String?
)
