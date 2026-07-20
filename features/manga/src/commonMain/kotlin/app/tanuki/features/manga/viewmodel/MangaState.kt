package app.tanuki.features.manga.viewmodel

import app.tanuki.core.domain.category.model.Category
import app.tanuki.core.domain.chapter.model.Chapter
import app.tanuki.core.domain.manga.model.Manga
import app.tanuki.core.domain.source.model.Source

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
     * @param chapters the manga's chapters, already ordered by [chapterSortAscending].
     * @param resumeChapter the chapter the "Read" action targets — the last chapter the user read, or the
     *   first chapter when none has been read yet; `null` when the manga has no chapters. Both the button
     *   label and its tap use this, so they can never point at different chapters.
     * @param chapterSortAscending whether [chapters] is ordered by ascending chapter number (1 → N);
     *   `false` is descending (N → 1). Reflected by the chapter list's sort toggle.
     * @param categories every library category paired with whether this manga belongs to it — the hero
     *   shows the members, the category sheet shows all of them with their selection.
     * @param source the source this manga belongs to, shown in the Info tab; `null` when the source is
     *   not (yet) persisted locally, in which case the Info tab shows an "unknown source" fallback.
     * @param inLibrary whether the manga is in the user's library (has a `date_added`). Drives the Info
     *   tab's add/remove toggle and whether category edits implicitly add it (see the ViewModel).
     */
    data class Content(
        val manga: Manga,
        val chapters: List<Chapter>,
        val resumeChapter: Chapter?,
        val chapterSortAscending: Boolean,
        val categories: List<MangaCategoryChip>,
        val source: Source?
    ) : MangaState {
        val inLibrary: Boolean get() = manga.dateAdded != null
    }

    /** A [Category] paired with whether the current manga is a member of it. */
    data class MangaCategoryChip(
        val category: Category,
        val isMember: Boolean
    )
}
