package app.tanuki.core.presentation

import app.tanuki.core.domain.settings.model.LibraryGridSize

/**
 * The smallest a cover may get in an adaptive cover grid, in dp (Android) / pt (iOS): the grid fits as
 * many columns as this allows, so [LibraryGridSize] stays a size rather than a column count and still
 * suits phones and tablets. One mapping for every cover grid (library, source catalog) on both platforms.
 */
val LibraryGridSize.minCoverWidth: Int
    get() =
        when (this) {
            LibraryGridSize.Small -> 90
            LibraryGridSize.Medium -> 114
            LibraryGridSize.Large -> 150
        }
