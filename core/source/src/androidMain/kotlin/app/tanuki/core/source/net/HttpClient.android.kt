package app.tanuki.core.source.net

import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.source.net.cloudflare.CloudflareChallengeSolver
import app.tanuki.core.source.net.cloudflare.CloudflareCookieStore
import app.tanuki.core.source.net.cookie.SourceCookieJar
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

actual fun platformSourceHttpClient(
    solver: CloudflareChallengeSolver,
    cookieStore: CloudflareCookieStore,
    cookieJar: SourceCookieJar,
    userAgent: SourceUserAgentProvider,
    logger: Logger,
): HttpClient = HttpClient(OkHttp) { installSourcePlugins(solver, cookieStore, cookieJar, userAgent, logger) }