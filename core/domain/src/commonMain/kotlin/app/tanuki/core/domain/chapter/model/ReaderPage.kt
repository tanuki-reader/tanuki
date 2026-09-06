package app.tanuki.core.domain.chapter.model

import app.tanuki.core.domain.common.model.Url

/**
 * A page as the reader is going to render it, once it is known *where* the bytes come from.
 *
 * The distinction [app.tanuki.core.domain.source.model.Page] cannot express: that type is the
 * **source's** description of a page, and its `imageUrl` is a [Url], which by construction requires a
 * scheme *and* a host — so a local file can never be put in one. Rather than loosen `Url` (and lose the
 * validation everything else depends on) or smuggle a path through a string field, the read path gets
 * its own small type and the source model stays exactly what it says it is.
 *
 * It carries only [image] today. It exists as a type anyway because it is the seam the deferred
 * descramble work extends (`docs/design-page-image-abi.md`): a scrambling source returns a per-page
 * transform descriptor that the reader has to apply, and that belongs beside the bytes' location rather
 * than in a parallel list. A page's *position* is deliberately not here — the reader already carries it
 * on `ReaderItem.PageItem`, and a second copy could only ever disagree with the first.
 *
 * @property image Where to get the bytes.
 */
data class ReaderPage(
    val image: PageImage,
)

/**
 * Where a page's bytes come from — the network, or this device.
 *
 * Exhaustive on purpose: the reader must handle both, and a sealed type is what makes the compiler say
 * so when a third case (a cached export, a bundled sample) ever appears.
 */
sealed interface PageImage {
    /**
     * Fetched from the source over the network, exactly as the reader has always done.
     *
     * @property url The image, or the page to resolve it from — whichever the source published.
     * @property headers Per-request headers the source needs (a `Referer` for a hotlink-guarded host).
     *   Empty for most sources.
     */
    data class Remote(
        val url: Url,
        val headers: Map<String, String> = emptyMap(),
    ) : PageImage

    /**
     * Read from a downloaded chapter on disk.
     *
     * [path] is an **opaque absolute path**, produced by
     * [app.tanuki.core.domain.download.service.ChapterDownloadStore.pagePaths] and understood only by
     * the image loader at the other end. It is deliberately a `String` rather than an `okio.Path`: the
     * domain has no filesystem dependency, and turning it into one is the UI layer's job — the same
     * boundary the store itself keeps.
     *
     * @property path Absolute path to the page's file.
     */
    data class Local(
        val path: String,
    ) : PageImage
}
