package app.tanuki.core.domain.common.service

/**
 * Severity of a log event, ordered least → most severe.
 *
 * [DEBUG] is special: it carries the **debug-only** contract. A [Logger] must drop [DEBUG] events in
 * non-debuggable (release) builds *before* they reach any [Logger.LogReceiver], so a debug trace can
 * never leak into a production log or crash-reporting sink. Encoding "debug only" as a level rather
 * than a separate boolean is what makes the pattern impossible to forget at a call site — choosing
 * [DEBUG] *is* the opt-in.
 */
enum class LogPriority {
    /** Development-only tracing. Suppressed entirely in release builds (see [LogPriority]). */
    DEBUG,

    /** Normal, expected lifecycle information. */
    INFO,

    /** Something recoverable went wrong; the app carried on (e.g., a best-effort refresh failed). */
    WARN,

    /** A genuine error worth a stack trace and, potentially, crash-reporting/analytics attention. */
    ERROR
}

/**
 * Abstraction for application-wide logging.
 *
 * A logger is a fan-out point: [log] validates the event, enforces the [LogPriority.DEBUG] debug-only
 * contract, then forwards it to every registered [LogReceiver], each of which renders it to a concrete
 * destination (platform console, crash-reporting, analytics, an in-memory test collector).
 *
 * Prefer the [debug]/[info]/[warn]/[error] extension helpers at call sites; they name the severity and
 * fill in the rarely-used parameters.
 */
interface Logger {
    /**
     * Logs an event.
     *
     * @param priority Severity of the event. [LogPriority.DEBUG] is emitted only in debuggable builds.
     * @param tag Optional tag identifying the source of the log.
     * @param message Optional human-readable message.
     * @param throwable Optional throwable associated with the event.
     * @param analyticsEvent Optional event name to forward to an analytics backend.
     * @param addToBreadcrumbs Whether to record this as a crash-reporting breadcrumb.
     */
    fun log(
        priority: LogPriority,
        tag: String?,
        message: String?,
        throwable: Throwable?,
        analyticsEvent: String?,
        addToBreadcrumbs: Boolean
    )

    /**
     * Registers a [LogReceiver] to receive log events. A receiver already registered is not added twice.
     */
    fun addReceiver(receiver: LogReceiver)

    /** Unregisters a previously added [LogReceiver]. */
    fun removeReceiver(receiver: LogReceiver)

    /**
     * A sink for log events emitted by a [Logger].
     *
     * Receivers are registered on a [Logger] and forward events to a specific destination — the platform
     * console, a crash-reporting service, an analytics system, or an in-memory test collector. A receiver
     * only ever sees events the logger decided to emit: [LogPriority.DEBUG] events are already filtered
     * out in release builds before they arrive here.
     */
    interface LogReceiver {
        /**
         * Receives a single log event.
         *
         * @param priority Severity of the event.
         * @param tag Optional tag identifying the source of the log.
         * @param message Optional human-readable message.
         * @param throwable Optional throwable associated with the event.
         */
        fun onLog(
            priority: LogPriority,
            tag: String?,
            message: String?,
            throwable: Throwable?
        )
    }
}

/**
 * Logs a development-only trace. Emitted only in debuggable builds — see [LogPriority.DEBUG].
 */
fun Logger.debug(
    tag: String?,
    message: String
) = log(LogPriority.DEBUG, tag, message, null, null, false)

/** Logs normal, expected lifecycle information. */
fun Logger.info(
    tag: String?,
    message: String
) = log(LogPriority.INFO, tag, message, null, null, false)

/** Logs a recoverable problem the app carried on from (e.g. a best-effort background refresh failed). */
fun Logger.warn(
    tag: String?,
    message: String? = null,
    throwable: Throwable? = null
) = log(LogPriority.WARN, tag, message, throwable, null, false)

/**
 * Logs a genuine error. Defaults to recording a crash-reporting breadcrumb; pass an [analyticsEvent] to
 * also forward it to analytics.
 */
fun Logger.error(
    tag: String?,
    message: String? = null,
    throwable: Throwable? = null,
    analyticsEvent: String? = null,
    addToBreadcrumbs: Boolean = true
) = log(LogPriority.ERROR, tag, message, throwable, analyticsEvent, addToBreadcrumbs)