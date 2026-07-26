package app.tanuki.core.source.net.cloudflare

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory store of solved Cloudflare clearances, keyed by host — so a challenge is solved **once** per
 * host and every later request to it replays the cookie instead of re-opening a WebView.
 *
 * Process-lived only, on purpose (for now): a `cf_clearance` cookie is short-lived (typically ~30 min)
 * and IP-bound, so persisting it across launches buys little and risks replaying a stale/invalid cookie.
 * If cold-start challenge latency proves annoying, persisting per host with the cookie's own expiry is the
 * upgrade — hence the small surface here.
 *
 * Guarded by a [Mutex] because the interceptor reads and writes it from concurrent request coroutines.
 */
class CloudflareCookieStore {
    private val mutex = Mutex()
    private val byHost = mutableMapOf<String, CfClearance>()

    /** The clearance previously solved for [host], or `null` if none (or it was [clear]ed). */
    suspend fun get(host: String): CfClearance? = mutex.withLock { byHost[host] }

    /** Records the clearance solved for [host], replacing any earlier one. */
    suspend fun put(host: String, clearance: CfClearance) = mutex.withLock { byHost[host] = clearance }

    /** Drops [host]'s clearance — call when a replay is itself challenged (the cookie went stale). */
    suspend fun clear(host: String) = mutex.withLock { byHost.remove(host); Unit }
}