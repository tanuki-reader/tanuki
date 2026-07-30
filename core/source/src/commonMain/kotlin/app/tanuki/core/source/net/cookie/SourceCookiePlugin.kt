package app.tanuki.core.source.net.cookie

import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpHeaders

/**
 * Ktor plugin that gives source traffic a cookie jar: it attaches [SourceCookieJar]'s cookies to every
 * outgoing request and harvests `Set-Cookie` off every response.
 *
 * **Install this after the Cloudflare plugin.** Send interceptors run in installation order, each
 * wrapping the next, so installing second puts this one *innermost* — right against the engine:
 * ```
 * cloudflare      cf_clearance merged onto whatever the guest set
 *   └─ cookie jar  jar cookies merged UNDER that, then Set-Cookie harvested
 *        └─ engine
 * ```
 * Two things fall out of that position, and both are the point:
 *
 * 1. **Precedence.** The jar goes underneath, so a guest's own `Cookie` header wins (a guest that
 *    computes a cookie — MangaLivre derives a proof-of-work one per request — knows something the jar
 *    doesn't) and so does the `cf_clearance` (a stale one in the jar must not defeat the solve that just
 *    happened).
 * 2. **A challenge replay is a second visit here.** Cloudflare's retry calls `proceed` again, which
 *    re-enters this plugin, so a cookie the *challenge response itself* set is already in the jar and
 *    rides along on the replay. Sitting outside Cloudflare instead would build the header once, before
 *    the challenge, and the replay would go out without the site's session — solved, but logged out.
 *
 * Ktor's own `HttpCookies` is not used for this: it overwrites the `Cookie` header outright rather than
 * merging, which would silently drop exactly the guest-computed cookie this exists to carry.
 */
fun sourceCookiePlugin(jar: SourceCookieJar): ClientPlugin<Unit> =
    createClientPlugin("SourceCookieJar") {
        on(Send) { request ->
            val host = request.url.host

            // The guest's own header wins on a name collision, so `putAll` order matters here.
            val merged = jar.get(host).toMutableMap()
            request.headers[HttpHeaders.Cookie]?.let { merged.putAll(parseCookieHeader(it)) }
            if (merged.isNotEmpty()) {
                request.headers.remove(HttpHeaders.Cookie)
                request.headers.append(HttpHeaders.Cookie, renderCookieHeader(merged))
            }

            val call = proceed(request)

            val received =
                call.response.headers
                    .getAll(HttpHeaders.SetCookie)
                    .orEmpty()
                    .mapNotNull(::parseSetCookie)
                    .toMap()
            jar.put(host, received)

            call
        }
    }