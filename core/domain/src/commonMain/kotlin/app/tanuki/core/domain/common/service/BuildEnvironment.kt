package app.tanuki.core.domain.common.service

/**
 * Facts about the running build that shared code can't derive on its own.
 *
 * Provided per platform via Metro (Android reads the app's `FLAG_DEBUGGABLE`; iOS uses the native
 * `Platform.isDebugBinary`). Its first consumer is [DefaultLogger], which uses [isDebug] to enforce the
 * [LogPriority.DEBUG] debug-only contract, but it is deliberately a general seam for any dev-only
 * behaviour that must be compiled into, yet inert in, release builds.
 */
interface BuildEnvironment {
    /** `true` when running a debuggable/development build, `false` in a release build. */
    val isDebug: Boolean

    /** Which platform this build runs on. */
    val platform: AppPlatform

    /**
     * The build number: Android's `versionCode`, iOS's `CFBundleVersion`. It separates two builds of the
     * same marketing version, which is what tells a fixed build from the broken one it replaced (Sentry's
     * `dist`). Read from the installed app at runtime rather than compiled into shared code, so it is
     * whatever the platform build actually stamped.
     */
    val buildNumber: String
}

/**
 * The platform a build runs on. Stated by each platform's binding rather than derived from an OS name at
 * runtime, so anything keyed on it (crash-report tags, dashboards) has exactly one spelling per platform.
 */
enum class AppPlatform {
    Android,
    Ios
}
