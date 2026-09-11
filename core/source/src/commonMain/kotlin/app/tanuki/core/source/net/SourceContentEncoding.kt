package app.tanuki.core.source.net

import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpHeaders

/**
 * Strips any inbound `Accept-Encoding` so the app's own is the only one that ever goes out.
 *
 * Content encoding is **host-owned**, for the same reason `User-Agent` is (see
 * [sourceUserAgentPlugin]): it is a transport detail the app has to be able to reason about uniformly,
 * and a source picking its own is how the behaviour stops being reproducible. Here it was also a plain
 * bug. Both guest-controlled header paths — the wasm host
 * ([app.tanuki.core.source.runtime.KtorSourceHost], which appends a guest's headers verbatim) and
 * [KtorPageFetcher]'s per-page headers — could set `Accept-Encoding`, and doing so **defeated OkHttp's
 * transparent gzip**: the engine only decodes what it asked for itself, so the source got handed raw
 * compressed bytes and its parse failed. Before this plugin the app never set the header at all, so
 * compression worked by accident on both engines and broke the moment a source touched it.
 *
 * Stripping costs a source nothing it could use: the body reaches the guest decoded either way, so the
 * only thing a guest's `Accept-Encoding` could change is whether that decoding happens.
 *
 * ### Why it has to be installed *immediately before* `ContentEncoding`
 * Ktor's plugin bails out of its own `onRequest` with
 * `if (request.headers.contains(Accept-Encoding)) return` — it defers to a header that is already
 * there. Interceptors registered in one request-pipeline phase run in **install order**, so this has to
 * run first for the app's value to win. Install it after `ContentEncoding` and it strips the header the
 * plugin just added, and every request goes out advertising nothing.
 *
 * Like [sourceUserAgentPlugin] this hooks the request pipeline rather than `Send`, so it is exempt from
 * the `Send` ordering in [installSourcePlugins] and runs once per logical call, not per replay.
 */
fun sourceAcceptEncodingPlugin(): ClientPlugin<Unit> =
    createClientPlugin("SourceAcceptEncoding") {
        onRequest { request, _ -> request.headers.remove(HttpHeaders.AcceptEncoding) }
    }
