package app.tanuki.platform.observability

import app.tanuki.core.domain.common.service.BuildEnvironment
import app.tanuki.core.domain.common.service.CrashReporter
import app.tanuki.core.domain.common.service.LogPriority
import app.tanuki.core.domain.common.service.NoOpCrashReporter
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.sentry.kotlin.multiplatform.Sentry
import io.sentry.kotlin.multiplatform.SentryLevel
import io.sentry.kotlin.multiplatform.protocol.Breadcrumb

/**
 * [CrashReporter] backed by Sentry, replacing [NoOpCrashReporter] wherever this module is on the graph.
 *
 * It lives in `:shared` rather than `:core:domain` on purpose: this is the only file that names a Sentry
 * type. Everything upstream talks to [CrashReporter] in app-owned types, so the backend is swappable and
 * the SDK stays in the module that already owns third-party dependencies.
 *
 * **The SDK is started lazily, by [setEnabled], not at app launch.** That is the cost of opt-in reporting
 * and it is deliberate: Sentry installs process-wide crash handlers on `init`, so starting it before the
 * user's stored preference has been read would collect from a user who never agreed. The trade is a blind
 * window between launch and the preference resolving — a crash in that window is lost. Reporting nothing
 * without consent is the right side of that trade; do not "fix" it by initializing eagerly.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, replaces = [NoOpCrashReporter::class])
class SentryCrashReporter(
    private val buildEnvironment: BuildEnvironment
) : CrashReporter {

    private var started = false

    override fun setEnabled(enabled: Boolean) {
        // No DSN means this build was compiled without one — a fork, or a checkout that never set
        // `sentry.dsn` (see shared/build.gradle.kts). Refusing to start here, rather than handing Sentry
        // an empty DSN, is what makes such a build provably inert: the SDK is never initialized at all,
        // so no crash handler is installed and there is nothing to send. It also keeps a fork from ever
        // reporting into this project simply because a user toggled the setting on.
        if (SENTRY_DSN.isBlank()) return
        if (enabled == started) return
        started = enabled
        if (enabled) Sentry.init(::configure) else Sentry.close()
    }

    override fun recordBreadcrumb(
        priority: LogPriority,
        tag: String?,
        message: String?
    ) {
        if (!started) return
        Sentry.addBreadcrumb(
            Breadcrumb(
                level = priority.toSentryLevel(),
                message = message,
                category = tag
            )
        )
    }

    override fun reportError(
        tag: String?,
        message: String?,
        throwable: Throwable?,
        eventName: String?
    ) {
        if (!started) return
        // A throwable is what makes a report worth reading — it carries the stack trace Sentry groups and
        // symbolicates on. Without one there is nothing to group by, so the message is captured instead.
        if (throwable != null) {
            Sentry.captureException(throwable) { scope ->
                tag?.let { scope.setTag(TAG_SOURCE, it) }
                eventName?.let { scope.setTag(TAG_EVENT, it) }
                message?.let { scope.setTag(TAG_MESSAGE, it) }
            }
        } else {
            Sentry.captureMessage(message ?: eventName ?: "Unknown error") { scope ->
                tag?.let { scope.setTag(TAG_SOURCE, it) }
                eventName?.let { scope.setTag(TAG_EVENT, it) }
            }
        }
    }

    /**
     * The SDK options. Several defaults are overridden, and each one is a decision rather than taste —
     * see the inline notes. The through-line: this reports *crashes*, and nothing about what a user reads.
     */
    private fun configure(options: io.sentry.kotlin.multiplatform.SentryOptions) {
        options.dsn = SENTRY_DSN
        options.environment = if (buildEnvironment.isDebug) "debug" else "production"
        options.debug = buildEnvironment.isDebug

        // Set explicitly rather than left to the SDK's auto-detection, which differs per platform and
        // would let Android and iOS report the same build under two different names — the one thing that
        // must not happen, since Sentry groups crashes by release and that grouping is what answers "did
        // 1.1 fix it?" and "which version regressed?". The `name@version` form is Sentry's convention.
        options.release = "app.tanuki@$APP_VERSION"

        // Must be off on Apple targets. With it on, an unhandled Kotlin exception thrown from a Compose
        // Multiplatform callback surfaces as Kotlin/Native's internal `ExceptionObjHolderImpl` C++ crash
        // instead of reaching the KMP unhandled-exception hook — i.e. a generic C++ frame in place of the
        // Kotlin stack trace. Since readable iOS stack traces are the whole reason this SDK was chosen
        // over Crashlytics, leaving this enabled would defeat the exercise.
        options.enableUnhandledCppExceptionMonitoring = false

        // Automatic HTTP client-error capture defaults to every target (`.*`). In this app the HTTP
        // traffic *is* the user's reading activity: capturing failed requests would ship source URLs —
        // which sites they browse and which chapters they open — to Sentry as a side effect of a network
        // error. Crash reporting must not become a browsing log.
        options.enableCaptureFailedRequests = false

        // Screenshots and view hierarchies would capture the page on screen: someone's library, and
        // copyrighted artwork. Both already default to false; they are pinned here so that a future
        // default change, or someone reaching for them to debug the reader, has to argue with this
        // comment first.
        options.attachScreenshot = false
        options.attachViewHierarchy = false

        // No IP collection. Off by default, pinned for the same reason.
        options.sendDefaultPii = false

        // Sessions stay on: they are what turn raw crash counts into a crash-free rate, which is the
        // metric that makes the rest actionable. A session records that the app was foregrounded and
        // whether it survived — not what was read in it.
        options.enableAutoSessionTracking = true

        // No performance tracing. It is the expensive half of the quota, and the reader's performance
        // work is done against on-device profilers, not production spans.
        options.tracesSampleRate = null
    }
}

/** Maps app log severity onto Sentry's. [LogPriority.DEBUG] never reaches here in a release build. */
private fun LogPriority.toSentryLevel(): SentryLevel =
    when (this) {
        LogPriority.DEBUG -> SentryLevel.DEBUG
        LogPriority.INFO -> SentryLevel.INFO
        LogPriority.WARN -> SentryLevel.WARNING
        LogPriority.ERROR -> SentryLevel.ERROR
    }

/** Scope tag carrying the app-side log tag, so issues can be filtered by originating subsystem. */
private const val TAG_SOURCE = "tanuki.tag"

/** Scope tag carrying [CrashReporter.reportError]'s `eventName` — a label for the *kind* of failure. */
private const val TAG_EVENT = "tanuki.event"

/** Scope tag carrying the logged message when a throwable is present, since the throwable owns the title. */
private const val TAG_MESSAGE = "tanuki.message"