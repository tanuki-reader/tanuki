package app.tanuki.core.source.net

import app.tanuki.core.domain.common.service.ConnectionObserver
import app.tanuki.core.domain.download.model.DownloadError
import app.tanuki.core.domain.download.service.FetchedPage
import app.tanuki.core.domain.download.service.PageFetchException
import app.tanuki.core.domain.download.service.PageFetcher
import app.tanuki.core.domain.source.model.Page
import app.tanuki.core.source.net.cloudflare.isCloudflareChallenge
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.ktor.client.HttpClient
import io.ktor.client.plugins.retry
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException

/**
 * [PageFetcher] over the app's **one** shared source client — the same client, and therefore the same
 * Cloudflare clearance, cookie jar and `User-Agent`, that the source's own API calls go out with (see
 * [platformSourceHttpClient]). A downloader with a client of its own would look like a second visitor to
 * the host and would be challenged on its own account, which is the failure this whole layer exists to
 * avoid.
 *
 * Its other job is **classification**: every failure leaves here as a [PageFetchException] carrying a
 * [DownloadError], because the engine that has to act on it lives in `:core:domain` and can see no HTTP
 * type at all.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class KtorPageFetcher(
    private val client: HttpClient,
    private val connectionObserver: ConnectionObserver,
) : PageFetcher {
    override suspend fun fetch(page: Page): FetchedPage {
        // Checked before the URL is even resolved. A download queue offline would otherwise spend a
        // transport timeout per page working out what the device already knows, and
        // DownloadError.Network is exactly the classification that failure deserves — it is the
        // retryable "try again, maybe on another network" case, which is precisely what this is.
        if (!connectionObserver.isOnline) {
            throw PageFetchException(DownloadError.Network, "Cannot fetch page ${page.index} while offline")
        }

        // Sources publish either a direct image URL or a page URL to resolve; the reader picks the same
        // way (`ToImageRequest`), so a download fetches exactly the bytes the reader would have shown.
        val url =
            (page.imageUrl ?: page.url)?.value
                ?: throw PageFetchException(
                    DownloadError.SourceUnavailable,
                    "Page ${page.index} has neither an image URL nor a page URL",
                )

        val response =
            try {
                client.get(url) {
                    page.headers.forEach { (name, value) -> header(name, value) }
                    // A hard ceiling on the whole call, which the client deliberately does not set
                    // globally (an interactive Cloudflare session is bounded only by the user, see
                    // `installSourceTimeouts`). It is safe *here* because this is the one path that is
                    // never interactive: a download runs in the background, where an unsolvable
                    // challenge already leaves as NeedsVerification rather than waiting for anyone. The
                    // socket timeout catches a stalled body; this catches one that trickles forever.
                    timeout { requestTimeoutMillis = PAGE_REQUEST_TIMEOUT_MS }
                    // Client-level retries are turned off for downloads only. DownloadManager already
                    // retries a page three times with its own backoff and reasons about that budget;
                    // letting the client retry underneath would silently multiply it and stretch each
                    // of the manager's attempts by the client's backoff. The reader's page loads go
                    // over the same client and do keep client retries — nothing is retrying for them.
                    retry { maxRetries = 0 }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                // Deliberately coarse. A request that never produced a response failed in transport —
                // no route, a reset, a timeout, TLS — and every one of those is the same decision for
                // the user ("try again, maybe on another network"). The cause is kept for the log.
                throw PageFetchException(DownloadError.Network, "Could not reach $url", error)
            }

        // Checked before the status, because a challenge *is* a 403/503 and the two mean opposite
        // things: one is the host refusing, the other is it asking for a browser we have no window for.
        if (response.isCloudflareChallenge()) {
            throw PageFetchException(
                DownloadError.NeedsVerification,
                "Fetching $url was challenged by Cloudflare and no session could solve it",
            )
        }
        if (!response.status.isSuccess()) {
            throw PageFetchException(response.status.value.toDownloadError(), "$url returned ${response.status}")
        }

        val bytes =
            try {
                response.readRawBytes()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                throw PageFetchException(DownloadError.Network, "The body of $url was cut short", error)
            }
        if (bytes.isEmpty()) {
            throw PageFetchException(DownloadError.Unknown, "$url returned an empty image")
        }

        return FetchedPage(bytes = bytes, extension = response.imageExtension(url))
    }

    private companion object {
        /**
         * Whether an HTTP status is worth another attempt.
         *
         * A rate limit or a server-side fault is temporary and a page queue will hit both; the rest —
         * a `404` for a delisted chapter, a `403` from the origin itself, an expired signed URL — will
         * answer the same way three times over, so they fail the chapter at once instead.
         */
        fun Int.toDownloadError(): DownloadError =
            if (this == 408 || this == 429 || this >= 500) DownloadError.Network else DownloadError.Unknown

        /**
         * The file extension to store the page under, from the response's content type and falling back
         * to the URL's own. Best effort by design: the extension only decides what the file is *called*
         * (the reader decodes by content), so an unrecognized type is stored rather than rejected.
         */
        fun HttpResponse.imageExtension(url: String): String {
            val subtype = contentType()?.contentSubtype?.lowercase()?.substringAfterLast('+')
            return when {
                subtype == null || subtype.isEmpty() -> url.extensionOrNull() ?: FALLBACK_EXTENSION
                subtype == "jpeg" -> "jpg"
                subtype.all { it.isLetterOrDigit() } -> subtype
                else -> url.extensionOrNull() ?: FALLBACK_EXTENSION
            }
        }

        /** The extension of a URL's last path segment, ignoring any query string; `null` if it has none. */
        fun String.extensionOrNull(): String? =
            substringBefore('?')
                .substringBefore('#')
                .substringAfterLast('/')
                .substringAfterLast('.', missingDelimiterValue = "")
                .lowercase()
                .takeIf { it.isNotEmpty() && it.length <= MAX_EXTENSION_LENGTH && it.all(Char::isLetterOrDigit) }

        /**
         * The longest a single page fetch may take, end to end. Long enough for a big page on a poor
         * connection, short enough that a host trickling bytes frees the queue's one slot instead of
         * holding it for the rest of the session.
         */
        const val PAGE_REQUEST_TIMEOUT_MS = 120_000L

        /** What a page with no usable extension is stored as; the store applies the same fallback. */
        const val FALLBACK_EXTENSION = "img"

        /** Longer than any real image extension, so a path that merely contains dots is not one. */
        const val MAX_EXTENSION_LENGTH = 8
    }
}