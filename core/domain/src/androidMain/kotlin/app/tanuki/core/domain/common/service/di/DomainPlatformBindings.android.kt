package app.tanuki.core.domain.common.service.di

import android.content.Context
import android.content.pm.ApplicationInfo
import app.tanuki.core.domain.common.service.AndroidConnectionObserver
import app.tanuki.core.domain.common.service.AppPlatform
import app.tanuki.core.domain.common.service.BuildEnvironment
import app.tanuki.core.domain.common.service.ConnectionObserver
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/**
 * Android platform bindings for `core:domain`. Both resolve from the application [Context] contributed
 * by the app's platform bindings — the same [Context] source the database and source-bundle stores use.
 *
 * [BuildEnvironment] reads the manifest's `debuggable` flag rather than a library `BuildConfig` (which
 * the KMP library plugin doesn't generate and which wouldn't reflect the app's build type anyway), and
 * its build number from the installed package's `versionCode` for the same reason.
 * [ConnectionObserver] watches the default network; it is a singleton because it registers a system
 * callback that stays for the process's life.
 */
@BindingContainer
@ContributesTo(AppScope::class)
object DomainPlatformBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun provideBuildEnvironment(context: Context): BuildEnvironment =
        AndroidBuildEnvironment(
            isDebug = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
            buildNumber = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toString(),
        )

    @Provides
    @SingleIn(AppScope::class)
    fun provideConnectionObserver(context: Context): ConnectionObserver = AndroidConnectionObserver(context)
}

private class AndroidBuildEnvironment(
    override val isDebug: Boolean,
    override val buildNumber: String
) : BuildEnvironment {
    override val platform: AppPlatform = AppPlatform.Android
}
