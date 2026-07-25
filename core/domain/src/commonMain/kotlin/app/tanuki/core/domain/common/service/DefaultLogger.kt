package app.tanuki.core.domain.common.service

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.concurrent.Volatile

/**
 * Default [Logger] backed by a set of [Logger.LogReceiver]s.
 *
 * The logger itself owns no platform I/O: it enforces the [LogPriority.DEBUG] debug-only contract, then
 * forwards each surviving event to every registered receiver and lets each sink render it. The one
 * platform-native sink — Logcat on Android, `NSLog` on iOS — is provided by [platformLogReceiver] and
 * registered on construction, so out of the box logs land in the platform console with no extra wiring.
 * Additional sinks (crash-reporting, analytics, in-memory test collectors) are added via [addReceiver].
 *
 * Debug-only enforcement lives here, once, rather than in each sink: a [LogPriority.DEBUG] event is
 * dropped in release builds (per [BuildEnvironment.isDebug]) *before* reaching any receiver, so a debug
 * trace can never leak into a crash-reporting or analytics sink in production.
 *
 * The receiver set is held as an immutable snapshot behind a [Volatile] reference and swapped
 * copy-on-write, so [log] fan-out reads a stable list without locking while [addReceiver]/
 * [removeReceiver] (rare, typically at startup) publish a new list atomically.
 *
 * Bound app-wide as [Logger] via Metro and marked [SingleIn] so the whole app shares one receiver set.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DefaultLogger(
    private val buildEnvironment: BuildEnvironment,
) : Logger {

    @Volatile
    private var receivers: List<Logger.LogReceiver> = listOf(platformLogReceiver())

    override fun log(
        priority: LogPriority,
        tag: String?,
        message: String?,
        throwable: Throwable?,
        analyticsEvent: String?,
        addToBreadcrumbs: Boolean,
    ) {
        // The debug-only contract: development traces never reach a sink in a release build.
        if (priority == LogPriority.DEBUG && !buildEnvironment.isDebug) return

        // analyticsEvent / addToBreadcrumbs are Logger-level concerns with no counterpart on
        // LogReceiver; there is no analytics or crash backend yet, so they are accepted and ignored
        // for now — wire them up here (before the fan-out) once one exists.
        receivers.forEach { it.onLog(priority, tag, message, throwable) }
    }

    override fun addReceiver(receiver: Logger.LogReceiver) {
        if (receiver in receivers) return
        receivers = receivers + receiver
    }

    override fun removeReceiver(receiver: Logger.LogReceiver) {
        receivers = receivers - receiver
    }
}

/**
 * Creates the platform-native default [Logger.LogReceiver] — Logcat on Android, `NSLog` on iOS —
 * registered automatically by [DefaultLogger] so the platform console works with no extra wiring.
 */
internal expect fun platformLogReceiver(): Logger.LogReceiver