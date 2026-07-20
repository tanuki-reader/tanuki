package app.tanuki.core.domain.library.model

import app.tanuki.core.domain.library.model.LibrarySort.Direction.Ascending
import app.tanuki.core.domain.library.model.LibrarySort.Mode.Alphabetical
import app.tanuki.core.domain.manga.model.Manga

/**
 * How the library list is ordered: a [mode] (the key to sort on) plus a [direction].
 *
 * @property mode The field to sort by.
 * @property direction Ascending or descending.
 */
data class LibrarySort(
    val mode: Mode,
    val direction: Direction
) {
    enum class Mode {
        /** By display title, case-insensitive. */
        Alphabetical,

        /** By the date the manga was added to the library. */
        DateAdded,

        /** By the most recent read time ([Manga.lastRead]); never-read titles sort to the start. */
        LastRead
    }

    enum class Direction { Ascending, Descending }

    companion object {
        /** Default ordering: alphabetical, A→Z. */
        val Default = LibrarySort(Alphabetical, Ascending)
    }
}