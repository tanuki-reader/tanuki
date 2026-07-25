package app.tanuki.core.domain.common.service.di

import app.tanuki.core.domain.common.service.BuildEnvironment
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform

/**
 * iOS platform bindings for `core:domain`. Resolves [BuildEnvironment] from the Kotlin/Native runtime's
 * own [Platform.isDebugBinary], so no application object or `Context` is needed — the debug flag is a
 * property of the compiled binary.
 */
@BindingContainer
@ContributesTo(AppScope::class)
object DomainPlatformBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun provideBuildEnvironment(): BuildEnvironment = IosBuildEnvironment
}

@OptIn(ExperimentalNativeApi::class)
private object IosBuildEnvironment : BuildEnvironment {
    override val isDebug: Boolean = Platform.isDebugBinary
}
