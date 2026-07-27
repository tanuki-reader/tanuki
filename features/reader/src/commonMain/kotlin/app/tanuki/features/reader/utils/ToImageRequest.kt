package app.tanuki.features.reader.utils

import app.tanuki.core.domain.source.model.Page
import app.tanuki.features.reader.composable.components.PagePrefetch
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import kotlin.collections.component1
import kotlin.collections.component2

/**
 * Builds the [ImageRequest] for a [Page], attaching its source-provided [Page.headers] (empty for most
 * sources). Shared by [ReaderPage] and [PagePrefetch] so a prefetched image is byte-for-byte the request
 * the page later renders — same URL *and* same headers, so both hit the same cache entry.
 */
fun Page.toImageRequest(context: coil3.PlatformContext): ImageRequest =
    ImageRequest
        .Builder(context)
        .data(imageUrl?.value ?: url?.value)
        .httpHeaders(
            if (headers.isEmpty()) {
                NetworkHeaders.EMPTY
            } else {
                NetworkHeaders.Builder().apply { headers.forEach { (name, value) -> add(name, value) } }.build()
            }
        ).build()