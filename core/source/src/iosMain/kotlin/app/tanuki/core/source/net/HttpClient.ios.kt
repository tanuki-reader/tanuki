package app.tanuki.core.source.net

import app.tanuki.core.source.net.cloudflare.CloudflareChallengeSolver
import app.tanuki.core.source.net.cloudflare.CloudflareCookieStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

actual fun platformSourceHttpClient(
    solver: CloudflareChallengeSolver,
    cookieStore: CloudflareCookieStore,
): HttpClient = HttpClient(Darwin) { installCloudflare(solver, cookieStore) }