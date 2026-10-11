package app.tanuki.core.domain.manga.model


import app.tanuki.core.domain.common.model.Url
import kotlin.uuid.Uuid

/**
 * Lightweight handle to a manga's cover — the URL to fetch and whatever that fetch has to carry.
 *
 * Holds only metadata, never image bytes: the bytes belong to the image loader's own memory and disk
 * caches, and only this descriptor crosses the data/UI boundary.
 *
 * There is deliberately **no cache-busting version field** here. The loader keys a cover on its URL
 * alone, and a source that changes its art publishes a new URL for it; a per-cover version column
 * existed until schema v12 and was only ever re-stamped when the URL had already changed, so it could
 * never invalidate anything the URL did not. See `TanukiImageLoader` for the caching side of that.
 *
 * @property mangaId the manga this cover belongs to.
 * @property url remote thumbnail URL; `null` when the source published none.
 * @property headers headers to send when fetching [url]. Empty for almost every source; non-empty when
 *   the source's image CDN checks the `Referer` and would otherwise 403. They travel with the cover
 *   rather than being looked up per screen, because a cover is rendered from the database in half a
 *   dozen places (library grid, home rows, history) with no source in scope to ask.
 * @property sourceUrl the source's own cover. Differs from [url] when the user picked a cover or the
 *   series' metadata supplies one (see `ApplyMangaMetadata`); every screen shows [url], and only the
 *   source syncs read [sourceUrl] / [sourceHeaders].
 * @property sourceHeaders the headers [sourceUrl] needs; [headers] is empty for an overriding cover.
 */
data class MangaCover(
    val mangaId: Uuid,
    val url: Url?,
    val headers: Map<String, String> = emptyMap(),
    val sourceUrl: Url? = url,
    val sourceHeaders: Map<String, String> = headers
) {
    /** The source's own cover, whatever is shown instead. */
    val ofSource: MangaCover get() = MangaCover(mangaId, sourceUrl, sourceHeaders)
}
