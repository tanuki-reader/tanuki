package app.tanuki.core.domain.common.service.di

import android.content.Context
import android.content.pm.ApplicationInfo
import app.tanuki.core.domain.common.service.BuildEnvironment
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/**
 * Android platform bindings for `core:domain`. Resolves [BuildEnvironment] from the application
 * [Context] contributed by the app's platform bindings — the same [Context] source the database and
 * source-bundle stores use — reading the manifest's `debuggable` flag rather than a library
 * `BuildConfig` (which the KMP library plugin doesn't generate and which wouldn't reflect the app's
 * build type anyway).
 */
@BindingContainer
@ContributesTo(AppScope::class)
object DomainPlatformBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun provideBuildEnvironment(context: Context): BuildEnvironment =
        AndroidBuildEnvironment(
            isDebug = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
        )
}

private class AndroidBuildEnvironment(override val isDebug: Boolean) : BuildEnvironment
