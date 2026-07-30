package app.tanuki.core.source.di

import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.source.net.DefaultSourceUserAgentProvider
import app.tanuki.core.source.net.SourceUserAgentProvider
import app.tanuki.core.source.net.cloudflare.CloudflareChallengeSolver
import app.tanuki.core.source.net.cloudflare.CloudflareCookieStore
import app.tanuki.core.source.net.cloudflare.InteractiveCloudflareSolver
import app.tanuki.core.source.net.cookie.SourceCookieJar
import app.tanuki.core.source.net.platformSourceHttpClient
import app.tanuki.core.source.net.session.SiteSessionCoordinator
import app.tanuki.core.source.runtime.WasmEngine
import app.tanuki.core.source.runtime.provideWasmEngine
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.ktor.client.HttpClient

/**
 * Platform-agnostic source-runtime bindings contributed into [AppScope]. Only the two seams that can't
 * be constructor-injected live here — the Ktor [HttpClient] and the [WasmEngine], both behind
 * `expect`/`actual` factories. Everything else self-binds by constructor injection:
 * [app.tanuki.core.source.WasmSourceLoader] (→ `SourceLoader`) and
 * [app.tanuki.core.source.runtime.KtorSourceHost] (→ `SourceHost`) via `@ContributesBinding`.
 *
 * The [SourceBundleStore][app.tanuki.core.domain.source.SourceBundleStore] and the
 * [CloudflareChallengeSolver] both need a platform (on-disk location / browser engine), so they are
 * provided in each target's own binding container (`PlatformSourceBindings`).
 *
 * All singletons: one HTTP client, one engine, and one clearance store per process, shared by every
 * loaded source — so a Cloudflare challenge is solved once per host and replayed everywhere.
 */
@BindingContainer
@ContributesTo(AppScope::class)
object SourceBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun provideCloudflareCookieStore(): CloudflareCookieStore = CloudflareCookieStore()

    @Provides
    @SingleIn(AppScope::class)
    fun provideSourceCookieJar(): SourceCookieJar = SourceCookieJar()

    @Provides
    @SingleIn(AppScope::class)
    fun provideSiteSessionCoordinator(): SiteSessionCoordinator = SiteSessionCoordinator()

    /**
     * The Cloudflare solver, now **common** rather than per-platform: a managed challenge only clears in a
     * browser the user can see, so the solver hands the challenge to the UI through the coordinator and the
     * platform-specific part is just the web view composable in `:shared`.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun provideCloudflareSolver(
        coordinator: SiteSessionCoordinator,
        logger: Logger,
    ): CloudflareChallengeSolver = InteractiveCloudflareSolver(coordinator, logger)

    /**
     * The `User-Agent` all source traffic uses. Bound to the built-in constant today; when the setting
     * lands, replace this with a provider that reads it (falling back to
     * [app.tanuki.core.source.net.DEFAULT_SOURCE_USER_AGENT] when unset) — nothing else has to change,
     * since the client consults the provider per request.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun provideSourceUserAgentProvider(): SourceUserAgentProvider = DefaultSourceUserAgentProvider

    @Provides
    @SingleIn(AppScope::class)
    fun provideHttpClient(
        solver: CloudflareChallengeSolver,
        cookieStore: CloudflareCookieStore,
        cookieJar: SourceCookieJar,
        userAgent: SourceUserAgentProvider,
        logger: Logger,
    ): HttpClient = platformSourceHttpClient(solver, cookieStore, cookieJar, userAgent, logger)

    @Provides
    @SingleIn(AppScope::class)
    fun provideWasmEngineBinding(): WasmEngine = provideWasmEngine()
}