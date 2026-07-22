package app.tanuki.features.browse.viewmodel

import app.tanuki.core.domain.source.model.SourceMeta

/**
 * State of the Browse tab root — the list of installed sources the user can drill into.
 *
 * Deliberately tiny: enumerating installed bundles is cheap and rarely fails, so there's no error
 * variant. A load failure or a device with no sources both surface as [Content] with an empty list,
 * which the screen renders as its empty state.
 */
sealed interface BrowseCatalogState {
    /** Sources are being enumerated (bundles read + each one's metadata decoded). */
    data object Loading : BrowseCatalogState

    /** The installed sources, ordered for stable display. Empty renders the "no sources" state. */
    data class Content(
        val sources: List<SourceMeta>
    ) : BrowseCatalogState
}
