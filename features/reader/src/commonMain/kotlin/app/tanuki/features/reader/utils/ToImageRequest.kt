package app.tanuki.features.reader.utils

import app.tanuki.features.reader.viewmodel.ReaderItem.PageItem
import app.tanuki.features.reader.composable.components.PagePrefetch
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size
import okio.Path.Companion.toPath
import kotlin.collections.component1
import kotlin.collections.component2

/**
 * Builds the [ImageRequest] for a [PageItem] — from the network or from a downloaded file, which is the
 * only thing that differs between the two. Shared by `ReaderPageImage` and [PagePrefetch] so a prefetched
 * image is byte-for-byte the request the page later renders — same source, same headers *and* same decode
 * size, so both hit the same cache entry.
 *
 * A downloaded page ([PageItem.localPath]) is handed to Coil as an `okio.Path`, which its default `PathMapper` turns into a
 * `file:` URI structurally. That matters more than it looks: the iOS downloads root lives under
 * "Application Support", so a `file://` string assembled by hand would need escaping to survive the space.
 * A local page also never enters Coil's *disk* cache — that tier only backs the network fetcher — so a
 * downloaded chapter costs its bytes once, not twice.
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
fun PageItem.toImageRequest(
    context: coil3.PlatformContext,
    decodeSize: Size
): ImageRequest = pageImageRequest(context, imageUrl, imageHeaders, localPath, decodeSize)

/** The same request for any page reference — a bookmark's thumbnail as well as a reader page. */
fun pageImageRequest(
    context: coil3.PlatformContext,
    imageUrl: String?,
    imageHeaders: Map<String, String>,
    localPath: String?,
    decodeSize: Size
): ImageRequest =
    ImageRequest
        .Builder(context)
        .data(localPath?.toPath() ?: imageUrl)
        .size(decodeSize)
        .scale(Scale.FIT)
        .precision(Precision.INEXACT)
        .httpHeaders(
            // Only a remote page has any; a local one is read straight off the filesystem.
            imageHeaders.takeIf { it.isNotEmpty() }?.let { headers ->
                NetworkHeaders.Builder().apply { headers.forEach { (name, value) -> add(name, value) } }.build()
            } ?: NetworkHeaders.EMPTY
        ).build()
