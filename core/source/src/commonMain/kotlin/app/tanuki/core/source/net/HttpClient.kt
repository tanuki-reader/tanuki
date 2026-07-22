package app.tanuki.core.source.net

import io.ktor.client.HttpClient

/**
 * The shared HTTP client. The public API is Ktor on every platform; only the engine differs
 * (OkHttp on Android, Darwin on iOS), bound via `actual`.
 *
 * TODO(cloudflare): install the CloudflareInterceptor here once it lands. Every source request (and,
 *  later, the cover fetcher) must share this single client so the cf_clearance cookie and challenge
 *  solving apply uniformly. Keep the interceptor independent of the WASM loader.
 */
expect fun platformHttpClient(): HttpClient