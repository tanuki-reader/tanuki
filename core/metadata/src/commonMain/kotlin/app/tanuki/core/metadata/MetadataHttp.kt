package app.tanuki.core.metadata

import io.ktor.client.HttpClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/** The platform's Ktor engine (OkHttp / Darwin) with no source plugins: these are plain APIs. */
internal expect fun metadataHttpClient(): HttpClient

/**
 * Spaces calls at least [interval] apart — under a provider's rate limit (AniList allows 90 a minute).
 * ponytail: one global spacing, not a token bucket; enrichment runs one series at a time, so bursts
 * never happen. Use a bucket if bulk enrichment (e.g. a library-wide refresh) is ever added.
 */
internal class RateLimiter(
    private val interval: Duration
) {
    private val mutex = Mutex()
    private var last = Instant.DISTANT_PAST

    suspend fun <T> run(block: suspend () -> T): T {
        mutex.withLock {
            val wait = interval - (Clock.System.now() - last)
            if (wait.isPositive()) delay(wait)
            last = Clock.System.now()
        }
        return block()
    }
}

private val TAG = Regex("<[^>]+>")
private val BLANK_LINES = Regex("\n{3,}")

/** Provider text arrives with `<br>`s and entities; this is the plain text a page shows. */
internal fun String.stripHtml(): String =
    replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(TAG, "")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'").replace("&lt;", "<").replace("&gt;", ">")
        .replace(BLANK_LINES, "\n\n")
        .trim()
