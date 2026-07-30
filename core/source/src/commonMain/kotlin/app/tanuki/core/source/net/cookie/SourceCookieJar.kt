package app.tanuki.core.source.net.cookie

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory cookie jar for source traffic, keyed by host — the thing that makes a site's own
 * `Set-Cookie` survive from one guest `fetch` to the next.
 *
 * A guest is stateless between exports and has no socket of its own, so it cannot hold a session
 * itself. Several real sources need one anyway: Hipercool answers a `401` by having the guest fetch the
 * site root, which replies with a `Set-Cookie` that the *next* request must carry. Without a jar that
 * cookie is dropped the moment the response is decoded and the retry is challenged all over again.
 * Mihon's extensions get this from OkHttp's `CookieJar`; this is Tanuki's equivalent, and like that one
 * it is shared by every source rather than per-source.
 *
 * **Not** where Cloudflare clearances live — those have their own store
 * ([app.tanuki.core.source.net.cloudflare.CloudflareCookieStore]) because they are solved rather than
 * received, are bound to a user agent, and have to be droppable on their own when a replay is
 * re-challenged. [sourceCookiePlugin] merges the two on the way out.
 *
 * Process-lived, matching the clearance store: sessions here are short and a stale cookie is worse than
 * a missing one. Guarded by a [Mutex] because requests run on concurrent coroutines.
 */
class SourceCookieJar {
    private val mutex = Mutex()
    private val byHost = mutableMapOf<String, MutableMap<String, String>>()

    /** Every cookie stored for [host], as `name` → `value`. Empty when the host is unknown. */
    suspend fun get(host: String): Map<String, String> = mutex.withLock { byHost[host]?.toMap() ?: emptyMap() }

    /** Stores [cookies] for [host], replacing same-named ones and leaving the rest in place. */
    suspend fun put(
        host: String,
        cookies: Map<String, String>
    ) {
        if (cookies.isEmpty()) return
        mutex.withLock { byHost.getOrPut(host) { mutableMapOf() }.putAll(cookies) }
    }

    /** Forgets everything stored for [host]. */
    suspend fun clear(host: String) {
        mutex.withLock { byHost.remove(host) }
    }
}

/**
 * Parses the cookie pairs out of a `Set-Cookie` header value, ignoring its attributes.
 *
 * Attributes (`Path`, `Expires`, `HttpOnly`, `SameSite`…) are deliberately dropped: this jar is
 * host-scoped and process-lived, so a path scope or an expiry days away changes nothing about what it
 * would replay. `Max-Age=0` and a past `Expires` are the one exception worth honouring — they mean
 * *delete* — but no source we serve uses them, so they are left for when one does.
 */
internal fun parseSetCookie(header: String): Pair<String, String>? {
    val pair = header.substringBefore(';')
    val name = pair.substringBefore('=', missingDelimiterValue = "").trim()
    if (name.isEmpty()) return null
    return name to pair.substringAfter('=', missingDelimiterValue = "").trim()
}

/** Renders `name=value; name=value` for a `Cookie` request header. */
internal fun renderCookieHeader(cookies: Map<String, String>): String =
    cookies.entries.joinToString("; ") { (name, value) -> "$name=$value" }

/** Parses a `Cookie` request header back into pairs — used to merge what a guest already set. */
internal fun parseCookieHeader(header: String): Map<String, String> =
    header
        .split(';')
        .mapNotNull { part ->
            val name = part.substringBefore('=', missingDelimiterValue = "").trim()
            if (name.isEmpty()) null else name to part.substringAfter('=', missingDelimiterValue = "").trim()
        }.toMap()