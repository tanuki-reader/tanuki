package app.tanuki.core.domain.common.service

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Destination for diagnostics that should survive the device — crashes and the trail of events leading to
 * them.
 *
 * Deliberately expressed in **app-owned types only** (`LogPriority`, `String`, `Throwable`). No backend
 * type appears here, so the concrete reporter can be swapped without touching `:core:domain` or anything
 * that logs, and the SDK stays confined to the module that owns third-party dependencies. This is the same
 * boundary rule the rest of the codebase follows for platform libraries; it is what lets a Sentry
 * implementation live in `:shared` while every call site keeps talking to [Logger].
 *
 * Wired to [Logger] rather than called directly: [DefaultLogger] forwards to it, so producing a report is
 * a side effect of ordinary logging and no feature needs to know a reporter exists.
 *
 * **Nothing leaves the device until [setEnabled] is called with `true`.** Reporting is opt-in, so the
 * default implementation state must be "off" and stay off until the user's stored preference says
 * otherwise. Note that [recordBreadcrumb] and [reportError] receive whatever text a call site logged —
 * every `info` and `warn` becomes a breadcrumb — so the rule lives at the call site: log ids, source ids
 * and hosts, never manga titles, full URLs or search text. An implementation that sends text off-device
 * should still treat it as possibly user-identifying and back that rule up server-side.
 * (`LogPriority.DEBUG` is already dropped in release builds before it can reach here; see [LogPriority].)
 */
interface CrashReporter {
    /**
     * Turns reporting on or off, reflecting the user's opt-in choice.
     *
     * Called at startup with the stored preference and again whenever the user changes it. Implementations
     * must treat `false` as "send nothing" — not merely "stop collecting" — so a user who opts out mid
     * session does not have queued reports delivered afterwards.
     *
     * @param enabled `true` once the user has opted in.
     */
    fun setEnabled(enabled: Boolean)

    /**
     * Changes the anonymous install id that reports are attributed to, after the user reset it.
     *
     * While reporting is off this may be ignored: starting the reporter reads the current id anyway.
     *
     * @param id The new install id — random and tied to nothing, like the one it replaces.
     */
    fun setUserId(id: String)

    /**
     * Records context that will accompany a later [reportError] — the trail, not the destination.
     *
     * @param priority Severity of the originating log event.
     * @param tag Optional tag identifying the source of the event.
     * @param message Optional human-readable message.
     */
    fun recordBreadcrumb(
        priority: LogPriority,
        tag: String?,
        message: String?
    )

    /**
     * Reports an error worth investigating.
     *
     * @param tag Optional tag identifying the source of the error.
     * @param message Optional human-readable message.
     * @param throwable Optional throwable, carrying the stack trace.
     * @param eventName Optional label for grouping or searching related errors, taken from the
     *   `analyticsEvent` a call site passed to [Logger.log]. It names *this kind of failure* — it is not
     *   behavioural analytics, and nothing here tracks what a user reads.
     */
    fun reportError(
        tag: String?,
        message: String?,
        throwable: Throwable?,
        eventName: String?
    )
}

/**
 * The [CrashReporter] bound when no reporting backend is installed: every call is discarded.
 *
 * This is the honest default for a build with no crash reporting — [DefaultLogger] can forward
 * unconditionally without null checks or a feature flag at the call site, and the app behaves identically
 * whether or not a backend exists. A real implementation replaces this binding via
 * `@ContributesBinding(AppScope::class, replaces = [NoOpCrashReporter::class])` from the module that owns
 * the SDK, so swapping backends never edits `:core:domain`.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class NoOpCrashReporter : CrashReporter {
    override fun setEnabled(enabled: Boolean) = Unit

    override fun setUserId(id: String) = Unit

    override fun recordBreadcrumb(
        priority: LogPriority,
        tag: String?,
        message: String?
    ) = Unit

    override fun reportError(
        tag: String?,
        message: String?,
        throwable: Throwable?,
        eventName: String?
    ) = Unit
}