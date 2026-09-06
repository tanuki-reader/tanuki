package app.tanuki.core.domain.download.service

import app.tanuki.core.domain.download.model.DownloadError
import app.tanuki.core.domain.source.model.Page

/**
 * Fetches one page's image bytes — the only thing in the download path that speaks HTTP.
 *
 * The port the domain owns and `:core:source` implements over the app's **one** shared client, so a
 * download inherits the Cloudflare clearance, the cookie jar and the host-owned `User-Agent` that every
 * other source request already goes out with. A downloader with its own client would be a second
 * identity against the same host, which is how a queue earns a challenge the reader never sees.
 *
 * The reader does *not* go through this: it hands the page's URL to the image pipeline, which decodes
 * and caches for display. Downloading wants the opposite — the untouched bytes, and no cache entry —
 * so the two paths are deliberately separate rather than one being expressed in terms of the other.
 */
interface PageFetcher {
    /**
     * Fetches [page]'s image.
     *
     * @param page The page descriptor as its source published it, including any per-request headers.
     * @throws PageFetchException for every failure, already classified. Nothing else is thrown, so the
     *   engine never has to interpret a transport type it cannot see from `:core:domain`.
     */
    suspend fun fetch(page: Page): FetchedPage
}

/**
 * One page's image, in memory, on its way to disk.
 *
 * Not a `data class`: [bytes] is an array, whose generated `equals` compares identity rather than
 * content — an equality that is quietly wrong is worse than none, and nothing here is ever compared.
 *
 * @property bytes The image exactly as the server sent it. Never decoded, re-encoded or resized: a
 *   download is meant to reproduce what the reader would have shown online, and re-encoding would cost
 *   quality and CPU to save bytes the user asked to spend.
 * @property extension File extension for those bytes (`jpg`, `png`, `webp` — no leading dot), derived
 *   from the response's content type. Best-effort: the store sanitizes it and falls back to a generic
 *   one, because a page with an odd content type is still a page.
 */
class FetchedPage(
    val bytes: ByteArray,
    val extension: String,
)

/**
 * A page fetch that failed, carrying the category the queue and the UI act on.
 *
 * Classification happens **here, at the layer that can see the transport** — a timeout, a challenged
 * response, an HTTP status — rather than in the engine, which is in `:core:domain` and can see none of
 * it. The engine's job is to decide what a category means for the chapter, not to guess it.
 *
 * @property error Why it failed. [DownloadError.NeedsVerification] in particular is not a retry: the
 *   queue cannot clear a Cloudflare challenge without a window to solve it in.
 */
class PageFetchException(
    val error: DownloadError,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)