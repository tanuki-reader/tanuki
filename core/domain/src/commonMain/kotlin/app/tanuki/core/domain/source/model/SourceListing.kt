package app.tanuki.core.domain.source.model

/**
 * The **closed set** of browseable listings the source contract defines — the "Popular"/"Latest" tabs
 * of a Browse screen.
 *
 * The vocabulary is owned by the contract, not by sources. A source declares only *which* of these it
 * serves ([SourceMeta.listings]) and can neither invent a listing nor rename one, so the app always
 * knows what a source can be asked for and only decides what to show for it: both tabs, one, or — when
 * the source declares none — its single default catalog with no tab row at all.
 *
 * Consequences worth keeping in mind when adding a variant:
 *  - [wireName] is the ABI payload value (`app.tanuki.core.source.abi.Wire`, mirrored in `wire.rs`).
 *    It is part of the contract; changing an existing one breaks every built bundle.
 *  - The tab **label is the app's** (localized via `stringResource`), because a source-supplied name
 *    could never be translated. A new variant needs `listing_*` strings in every language.
 *  - Declaration order in the metadata is ignored — the app renders listings in this enum's order, so
 *    every source's Browse screen presents them the same way.
 */
enum class SourceListing(
    /** Value this listing travels as on the ABI (e.g. `"popular"`). Never shown to the user. */
    val wireName: String
) {
    /** The source's most-followed/most-read series. */
    POPULAR("popular"),

    /** The source's most recently updated series. */
    LATEST("latest"),

    /** Series most recently added to the source. Optional; only sources that can sort by creation date declare it. */
    NEW("new");

    companion object {
        /**
         * Resolves an ABI [wireName] to its listing, or `null` if no variant matches — a bundle built
         * against a newer contract can advertise a listing this build has no concept of, and dropping
         * it is better than failing the source's whole metadata decode. Mirrors how an unknown
         * `PublishStatus` name degrades rather than throwing.
         */
        fun fromWireName(wireName: String): SourceListing? =
            entries.firstOrNull { it.wireName == wireName }
    }
}