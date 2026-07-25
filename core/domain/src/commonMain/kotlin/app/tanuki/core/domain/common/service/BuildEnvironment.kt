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
}
