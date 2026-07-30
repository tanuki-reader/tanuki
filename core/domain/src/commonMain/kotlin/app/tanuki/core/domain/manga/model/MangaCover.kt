package app.tanuki.core.domain.manga.model


import app.tanuki.core.domain.common.model.Url
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Lightweight handle to a manga's cover, used as the key the platform image loaders fetch against.
 *
 * Holds only metadata — never image bytes. The bytes live on disk (see
 * [app.tanuki.data.cover.CoverCache]); only this descriptor crosses the shared/UI boundary.
 *
 * @property mangaId owning manga; also the key for a user-supplied [isCustom] cover.
 * @property url remote thumbnail URL; `null` when only a custom cover exists.
 * @property headers headers to send when fetching [url]. Empty for almost every source; non-empty when
 *   the source's image CDN checks the `Referer` and would otherwise 403. They travel with the cover
 *   rather than being looked up per screen, because a cover is rendered from the database in half a
 *   dozen places (library grid, home rows, history) with no source in scope to ask.
 * @property lastModified cache-busting version — bump to invalidate a cached cover.
 */
data class MangaCover(
    val mangaId: Uuid,
    val url: Url?,
    val lastModified: Instant,
    val headers: Map<String, String> = emptyMap()
)