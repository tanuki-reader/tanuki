package app.tanuki.features.reader.utils

import app.tanuki.core.domain.source.model.Page
import app.tanuki.features.reader.composable.components.PagePrefetch
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size
import kotlin.collections.component1
import kotlin.collections.component2

/**
 * Builds the [ImageRequest] for a [Page], attaching its source-provided [Page.headers] (empty for most
 * sources). Shared by [ReaderPage] and [PagePrefetch] so a prefetched image is byte-for-byte the request
 * the page later renders — same URL, same headers *and* same decode size, so both hit the same cache entry.
 *
 * [decodeSize] bounds the decoded bitmap (see [rememberPageDecodeSize]); it must be the same value on both
 * paths, which is why neither builds one of its own. The scale and precision that go with it are pinned
 * here rather than left to defaults, because the two paths would otherwise disagree: a request rendered
 * through `AsyncImagePainter` is forced to [Precision.INEXACT], while one merely enqueued for prefetch
 * keeps `ImageRequest.Defaults`' [Precision.EXACT] — the same page, decoded twice, at two sizes.
 *
 *  - [Scale.FIT] takes the *smaller* of the width and height ratios, so [decodeSize]'s height genuinely
 *    caps tall strips. [Scale.FILL] would take the larger and let a 10000px strip through at full height.
 *  - [Precision.INEXACT] lets Coil settle for the nearest cheap sample step and, crucially, never upscales
 *    a page that is already smaller than the target.
 */
fun Page.toImageRequest(
    context: coil3.PlatformContext,
    decodeSize: Size
): ImageRequest =
    ImageRequest
        .Builder(context)
        .data(imageUrl?.value ?: url?.value)
        .size(decodeSize)
        .scale(Scale.FIT)
        .precision(Precision.INEXACT)
        .httpHeaders(
            if (headers.isEmpty()) {
                NetworkHeaders.EMPTY
            } else {
                NetworkHeaders.Builder().apply { headers.forEach { (name, value) -> add(name, value) } }.build()
            }
        ).build()