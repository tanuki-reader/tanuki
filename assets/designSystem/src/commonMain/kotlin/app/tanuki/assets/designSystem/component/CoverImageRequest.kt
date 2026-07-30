package app.tanuki.assets.designSystem.component

import androidx.compose.runtime.Composable
import coil3.compose.LocalPlatformContext
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest

/**
 * Builds the [ImageRequest] for a cover, attaching the source-provided [headers] (empty for almost
 * every source).
 *
 * Covers are drawn in half a dozen places — the library grid, the browse grid, home rows, history, the
 * detail hero — and a source whose image CDN checks the `Referer` 403s in *all* of them if the header
 * is missing from *any*. So the request is built here, once, and every cover-rendering component takes
 * the headers alongside the URL rather than each rebuilding this.
 *
 * The reader's page images have the identical problem and their own equivalent
 * (`Page.toImageRequest`); the two stay separate because pages run on a different `ImageLoader` (see
 * `newTanukiPageImageLoader`).
 */
@Composable
fun coverImageRequest(
    url: String,
    headers: Map<String, String>
): ImageRequest =
    ImageRequest
        .Builder(LocalPlatformContext.current)
        .data(url)
        .httpHeaders(
            if (headers.isEmpty()) {
                NetworkHeaders.EMPTY
            } else {
                NetworkHeaders.Builder().apply { headers.forEach { (name, value) -> add(name, value) } }.build()
            }
        ).build()