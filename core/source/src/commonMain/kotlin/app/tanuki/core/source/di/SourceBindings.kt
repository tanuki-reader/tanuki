package app.tanuki.core.source.di

import app.tanuki.core.source.net.platformHttpClient
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
 * The [SourceBundleStore][app.tanuki.core.domain.source.SourceBundleStore] needs a platform on-disk
 * location, so it is provided in each target's own binding container (`PlatformSourceBindings`).
 *
 * Both singletons: one HTTP client and one engine per process, shared by every loaded source.
 */
@BindingContainer
@ContributesTo(AppScope::class)
object SourceBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun provideHttpClient(): HttpClient = platformHttpClient()

    @Provides
    @SingleIn(AppScope::class)
    fun provideWasmEngineBinding(): WasmEngine = provideWasmEngine()
}