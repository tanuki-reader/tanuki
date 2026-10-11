package app.tanuki.platform.imaging

import app.tanuki.appGraph
import coil3.Canvas
import coil3.Image
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create

/*
 * Covers for SwiftUI, through the app's own image pipeline (docs/swiftui-migration.md → "Images").
 *
 * SwiftUI's AsyncImage/URLSession can't do what makes a cover load: fetch over the shared,
 * Cloudflare-aware Ktor client (it holds the solved `cf_clearance`), send the cover's headers, and
 * cache successes only. Coil already does all of that for the Compose screens, so Swift asks Coil too,
 * through the *same* loader instance, and shares its memory cache and its one disk cache over
 * `cover_cache`. A cover cached by a Compose screen is a hit for a SwiftUI one, and back.
 *
 * What Swift gets is the **encoded** image: Coil fetches and caches, a pass-through decoder hands back
 * the bytes, and Swift decodes with ImageIO at the size it draws. Decoding into a Skia bitmap here and
 * converting it would cost a full decode plus a copy, for an image UIKit decodes better itself.
 */

/**
 * The cover at [url], fetched with [headers] (empty for almost every source), as encoded image data, or
 * `null` when it can't be had (404, offline, not an image). Served from Coil's disk cache when it's
 * there. A failure isn't cached, so the next call asks the network again.
 *
 * Cancelling the calling Swift task cancels the fetch.
 */
suspend fun loadCoverData(
    url: String,
    headers: Map<String, String>
): NSData? = loadEncodedImage(coverImageLoader(), url, headers)?.toNSData()

/**
 * A reader page at [url] with [headers] (a source's Referer, say), as encoded image data, or `null` when
 * it can't be had. Through the reader's own page loader — the same one, and the same `page_cache`, as
 * the Compose reader (see [sharedPageImageLoader]) — so a page prefetched by either is a hit for both.
 * A downloaded page is a local file, and Swift reads it itself; this is for source pages only.
 */
suspend fun loadPageData(
    url: String,
    headers: Map<String, String>
): NSData? =
    loadEncodedImage(sharedPageImageLoader(PlatformContext.INSTANCE, appGraph.sourceHttpClient), url, headers)?.toNSData()

/**
 * Coil's singleton loader, built by [newTanukiImageLoader] over the shared source client. It's the same
 * factory the Compose host (`TanukiComposeHost`) registers, so whichever side asks first creates it, and
 * there is only ever one `DiskCache` over `cover_cache`. Two over one directory would corrupt each other.
 */
private fun coverImageLoader(): ImageLoader {
    SingletonImageLoader.setSafe { context -> newTanukiImageLoader(context, appGraph.sourceHttpClient) }
    return SingletonImageLoader.get(PlatformContext.INSTANCE)
}

/** [url]'s encoded bytes through [loader]'s fetch and disk cache, skipping its decode. */
internal suspend fun loadEncodedImage(
    loader: ImageLoader,
    url: String,
    headers: Map<String, String>
): ByteArray? {
    val request =
        ImageRequest
            .Builder(PlatformContext.INSTANCE)
            .data(url)
            .httpHeaders(
                if (headers.isEmpty()) {
                    NetworkHeaders.EMPTY
                } else {
                    NetworkHeaders.Builder().apply { headers.forEach { (name, value) -> add(name, value) } }.build()
                }
            ).decoderFactory(PassThroughDecoder)
            // The memory cache holds decoded bitmaps for Compose. Encoded bytes don't belong in it: they'd
            // count against its budget, and a Compose screen asking for this URL would get bytes back.
            .memoryCachePolicy(CachePolicy.DISABLED)
            .build()
    return when (val result = loader.execute(request)) {
        is SuccessResult -> (result.image as? EncodedImage)?.bytes
        // execute() reports cancellation by throwing, not as an ErrorResult, so this is a real failure.
        is ErrorResult -> null
    }
}

/** Hands Coil's fetched source back as its raw bytes instead of decoding it. */
private val PassThroughDecoder =
    Decoder.Factory { result, _, _ ->
        Decoder { DecodeResult(image = EncodedImage(result.source.source().readByteArray()), isSampled = false) }
    }

/** A "decoded" image that is only its encoded bytes. Never drawn: no target ever receives it. */
private class EncodedImage(
    val bytes: ByteArray
) : Image {
    override val size: Long = bytes.size.toLong()
    override val width: Int = -1
    override val height: Int = -1
    override val shareable: Boolean = true

    override fun draw(canvas: Canvas) = Unit
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData =
    // addressOf(0) has no element to point at in an empty array.
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }
