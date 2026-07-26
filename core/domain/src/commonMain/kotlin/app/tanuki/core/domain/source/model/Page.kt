package app.tanuki.core.domain.source.model

import app.tanuki.core.domain.common.model.Url


/**
 * A single page of a chapter, as described by its source.
 *
 * Like [app.tanuki.core.domain.manga.model.MangaCover], this is a *descriptor only* — it never holds
 * image bytes. The bytes are fetched by the reader/loader from [imageUrl]; the domain model
 * carries just enough to locate them, so only this small handle crosses the shared/UI boundary.
 *
 * Some sources return a direct [imageUrl]; others return only a page [url] that must be fetched a
 * second time to resolve the image. Exactly one of the two is expected to be present.
 *
 * Reader-side state (loading / loaded / failed) is intentionally *not* modeled here — that is a
 * runtime concern owned by the reader, not part of the source contract.
 *
 * @property index zero-based position of the page within its chapter.
 * @property imageUrl direct image URL; `null` when it must be resolved from [url].
 * @property url page URL to resolve when [imageUrl] is absent; `null` when [imageUrl] is direct.
 * @property headers per-request headers the reader must send when fetching this page's image — e.g. a
 *   `Referer` for a hotlink-guarded source. Empty (the default) for sources that need none, like MangaDex.
 *   Content headers from the source, not display copy, so plain `String`s (never localized).
 */
data class Page(
    val index: UInt,
    val imageUrl: Url?,
    val url: Url?,
    val headers: Map<String, String> = emptyMap()
)