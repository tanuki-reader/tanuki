package app.tanuki.core.domain.source.usecase

import app.tanuki.core.domain.source.SourceLoader
import app.tanuki.core.domain.source.model.MangaPage
import app.tanuki.core.domain.source.model.SourceListing
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

/**
 * Use case: fetch one page of a source's catalog, browsing a listing or searching it.
 *
 * Owns the **search-overrides-listing** precedence the ABI documents on
 * [SourceLoader.fetchMangaList]: a source can't combine a free-text query with one of its declared
 * listings, so a non-blank [query] is sent alone and the [listing] is dropped. Callers pass both the
 * selected tab and the current search text and let this decide, instead of each screen re-deriving the
 * rule. Blank/whitespace queries normalize to `null` here too, so "browse" and "search for nothing" are
 * the same request.
 *
 * Throws whatever the source layer throws — unlike enumerating installed sources, a failed catalog
 * fetch is a real, user-visible error the browse screen surfaces with a retry, so it is not swallowed.
 *
 * This is also the intended home for a browse-catalog cache: results are currently re-fetched on every
 * tab switch, which is cheap for API-backed sources and won't be for a scraping one. When that lands, it
 * goes behind this interface and no ViewModel changes.
 *
 * @param sourceId source-native id (e.g. `"mangadex"`).
 * @param listing a [SourceListing] the source advertised, or `null` for its default catalog. Ignored
 *   when [query] is non-blank.
 * @param query free-text search; `null` or blank browses instead of searching.
 * @param page 1-based page index; page forward only while [MangaPage.hasNextPage].
 */
interface BrowseSource {
    suspend operator fun invoke(
        sourceId: String,
        listing: SourceListing? = null,
        query: String? = null,
        page: Int = 1
    ): MangaPage

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val sourceLoader: SourceLoader
    ) : BrowseSource {
        override suspend fun invoke(
            sourceId: String,
            listing: SourceListing?,
            query: String?,
            page: Int
        ): MangaPage {
            val searchQuery = query?.takeUnless { it.isBlank() }
            return sourceLoader.fetchMangaList(
                sourceId = sourceId,
                // A search replaces the listing rather than filtering it — see the precedence note above.
                listing = if (searchQuery == null) listing else null,
                query = searchQuery,
                page = page
            )
        }
    }
}