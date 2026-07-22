package app.tanuki.features.home.viewmodel

import kotlin.uuid.Uuid

/**
 * State of the Home tab — the app's landing surface, split into two views the screen toggles between:
 * **For You** ([continueReading], activity over the user's library) and **Sources** ([sourceGroups],
 * the installed sources grouped by language).
 *
 * Both views load together into one [Content]; the selected view is UI state the screen owns, since it
 * changes nothing in the data. A "Recent Updates" view is intentionally absent for now — it waits on a
 * real update-tracking feature (see the project notes) and [Content] is shaped to grow a second For You
 * section without a state redesign.
 */
sealed interface HomeState {
    /** Reading history and installed sources are still loading. */
    data object Loading : HomeState

    /**
     * @property continueReading the user's in-progress library titles, most-recently-read first (one
     *   card per series); empty renders the For You empty state.
     * @property sourceGroups installed sources bucketed by language, each collapsible; empty renders
     *   the Sources empty state.
     */
    data class Content(
        val continueReading: List<ContinueReadingItem>,
        val sourceGroups: List<SourceLanguageGroup>
    ) : HomeState
}

/**
 * One "Continue Reading" card: a library series and the chapter the user last opened.
 *
 * @property mangaId the series, for navigation and as the cover seed.
 * @property chapterId the last-read chapter to resume, or `null` if it has since been pruned (the card
 *   then opens the series' detail instead).
 * @property title series title (source content, not localizable).
 * @property coverUrl remote cover URL, or `null` for the gradient fallback.
 * @property chapterNumberLabel the resumable chapter's number formatted for display (e.g. "7", "15.3"),
 *   or `null` when the source didn't give a recognizable number.
 * @property chapterName the chapter's own name, shown when [chapterNumberLabel] is `null`.
 */
data class ContinueReadingItem(
    val mangaId: Uuid,
    val chapterId: Uuid?,
    val title: String,
    val coverUrl: String?,
    val chapterNumberLabel: String?,
    val chapterName: String
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
