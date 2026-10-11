package app.tanuki.core.domain.source.model

/**
 * A source's home: the first page of several of its listings, one [SourceHomeSection] each, in
 * [SourceListing] order. Built either from the source's optional one-call home or, when it has none, from
 * one catalog fetch per declared listing.
 */
data class SourceHome(
    val sections: List<SourceHomeSection>
)

/**
 * One rail of a [SourceHome]. [listing] is `null` for the source's default catalog (the "All series"
 * preview).
 */
data class SourceHomeSection(
    val listing: SourceListing?,
    val entries: List<MangaListing>
)
