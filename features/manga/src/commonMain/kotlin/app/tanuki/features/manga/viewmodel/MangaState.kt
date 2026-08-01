package app.tanuki.features.manga.viewmodel

import app.tanuki.core.domain.category.model.Category
import app.tanuki.core.domain.chapter.model.Chapter
import app.tanuki.core.domain.manga.model.Manga
import app.tanuki.core.domain.source.model.Source
import kotlin.uuid.Uuid

/** UI state for the manga detail screen. */
sealed interface MangaState {
    /** Initial state while the manga and its chapters are first read from the database. */
    data object Loading : MangaState

    /** The requested manga id resolved to no row (deleted, or an invalid deep link). */
    data object NotFound : MangaState

    /**
     * The manga and its chapters to render.
     *
     * @param manga the series metadata shown in the hero and description.
     * @param chapters the chapters to render — already ordered by [chapterSortAscending] and with any
     *   hidden scanlation group removed, so the UI renders the list as given.
     * @param resumeChapter the chapter the "Read" action targets — the last chapter the user read, or the
     *   first chapter when none has been read yet; `null` when the manga has no chapters. Both the button
     *   label and its tap use this, so they can never point at different chapters.
     * @param chapterSortAscending whether [chapters] is ordered by ascending chapter number (1 → N);
     *   `false` is descending (N → 1). Reflected by the chapter list's sort toggle.
     * @param categories every library category paired with whether this manga belongs to it — the hero
     *   shows the members, the category sheet shows all of them with their selection.
     * @param scanlators every group that has published a chapter of this series, with its chapter count
     *   and whether it is currently shown — the chapter filter sheet's chips. Fewer than two entries
     *   means there is nothing to choose between, and the filter affordance stays hidden.
     * @param source the source this manga belongs to, shown in the Info tab; `null` when the source is
     *   not (yet) persisted locally, in which case the Info tab shows an "unknown source" fallback.
     * @param selection which chapters the user has picked for a bulk action, and what that selection can
     *   do. Empty means no selection mode is running and the screen is in its normal reading chrome.
     * @param inLibrary whether the manga is in the user's library (has a `date_added`). Drives the Info
     *   tab's add/remove toggle and whether category edits implicitly add it (see the ViewModel).
     */
    data class Content(
        val manga: Manga,
        val chapters: List<Chapter>,
        val resumeChapter: Chapter?,
        val chapterSortAscending: Boolean,
        val categories: List<MangaCategoryChip>,
        val scanlators: List<ScanlatorChip>,
        val source: Source?,
        val selection: ChapterSelection = ChapterSelection.None
    ) : MangaState {
        val inLibrary: Boolean get() = manga.dateAdded != null

        /** Whether the chapter list is worth offering a group filter for — i.e. more than one group. */
        val canFilterScanlators: Boolean get() = scanlators.size > 1

        /** Whether any group is currently hidden, which the filter affordance marks with a dot. */
        val isChapterFilterActive: Boolean get() = scanlators.any { !it.isShown }
    }

    /**
     * One scanlation group in the chapter filter: its name, how many of this manga's chapters it
     * published, and whether those chapters are currently shown.
     *
     * @param name the group as the source credits it. Content, not copy — never localized.
     * @param chapterCount how many chapters this group published, shown on the chip.
     * @param isShown `false` when the group is in [app.tanuki.core.domain.manga.model.Manga.filteredScanlators].
     */
    data class ScanlatorChip(
        val name: String,
        val chapterCount: Int,
        val isShown: Boolean
    )

    /**
     * The chapter multi-select: which chapters are picked, plus what the bulk-action bar may offer for
     * them. The four `can*` flags are computed once by the ViewModel — each is a scan of the chapter list
     * (which runs to thousands of rows on a long series), so they are state, not something the bar
     * re-derives on every recomposition.
     *
     * @param ids the picked chapters. Only ever ids the list is currently *showing*: a chapter that a
     *   scanlator filter hides or a sync removes drops out of the selection with it, so no bulk action
     *   can touch a row the user can't see.
     * @param canSelectAbove whether any chapter sits above the topmost picked one in the displayed order
     *   — i.e. whether "select above" would add anything.
     * @param canSelectBelow the same, below the bottommost picked chapter.
     * @param canMarkRead whether any picked chapter is not read yet.
     * @param canMarkUnread whether any picked chapter has reading state to clear — read, or merely opened
     *   and left partway through.
     * @param allSelected whether every shown chapter is picked, which flips the select-all control into
     *   its "clear" meaning.
     */
    data class ChapterSelection(
        val ids: Set<Uuid>,
        val canSelectAbove: Boolean,
        val canSelectBelow: Boolean,
        val canMarkRead: Boolean,
        val canMarkUnread: Boolean,
        val allSelected: Boolean
    ) {
        /** Whether a selection is running — the screen swaps to its selection chrome exactly when true. */
        val isActive: Boolean get() = ids.isNotEmpty()

        val count: Int get() = ids.size

        companion object {
            /** No selection: the screen's normal reading chrome. */
            val None =
                ChapterSelection(
                    ids = emptySet(),
                    canSelectAbove = false,
                    canSelectBelow = false,
                    canMarkRead = false,
                    canMarkUnread = false,
                    allSelected = false
                )
        }
    }

    /** A [Category] paired with whether the current manga is a member of it. */
    data class MangaCategoryChip(
        val category: Category,
        val isMember: Boolean
    )
}
