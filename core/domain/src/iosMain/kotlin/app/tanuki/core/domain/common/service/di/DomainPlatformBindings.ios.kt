package app.tanuki.core.domain.common.service.di

import app.tanuki.core.domain.common.service.AppPlatform
import app.tanuki.core.domain.common.service.BuildEnvironment
import app.tanuki.core.domain.common.service.ConnectionObserver
import app.tanuki.core.domain.common.service.IosConnectionObserver
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform
import platform.Foundation.NSBundle

/**
 * iOS platform bindings for `core:domain`. Neither needs an application object or a `Context`:
 * [BuildEnvironment] reads the Kotlin/Native runtime's own [Platform.isDebugBinary] (the debug flag is a
 * property of the compiled binary), and [ConnectionObserver] asks Network.framework directly. The latter
 * is a singleton because it starts a path monitor that stays for the process's life.
 */
@BindingContainer
@ContributesTo(AppScope::class)
object DomainPlatformBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun provideBuildEnvironment(): BuildEnvironment = IosBuildEnvironment

    @Provides
    @SingleIn(AppScope::class)
    fun provideConnectionObserver(): ConnectionObserver = IosConnectionObserver()
}

@OptIn(ExperimentalNativeApi::class)
private object IosBuildEnvironment : BuildEnvironment {
    override val isDebug: Boolean = Platform.isDebugBinary
    override val platform: AppPlatform = AppPlatform.Ios

    // `CURRENT_PROJECT_VERSION` in iosApp/Configuration/Config.xcconfig, as stamped into Info.plist.
    override val buildNumber: String =
        NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleVersion") as? String ?: "0"
}
