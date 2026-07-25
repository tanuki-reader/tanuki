package app.tanuki.core.domain.common.service

import platform.Foundation.NSLog

/** Default tag used when a log event carries none. */
private const val DEFAULT_TAG = "Tanuki"

internal actual fun platformLogReceiver(): Logger.LogReceiver = NsLogReceiver

/**
 * [Logger.LogReceiver] that writes to iOS via [NSLog], so entries appear in the Xcode console and the
 * device's unified logging.
 *
 * `NSLog` has no severity levels, so each line is prefixed with one — `D`/`I`/`W`/`E` from
 * [LogPriority]. A `throwable`'s stack trace is appended on its own lines. The assembled text is passed
 * as a single `%@` argument rather than as the format string itself, so a `%` in a message can never be
 * interpreted as a format specifier.
 *
 * The logger has already dropped [LogPriority.DEBUG] events in release builds before they reach here.
 */
internal object NsLogReceiver : Logger.LogReceiver {

    override fun onLog(
        priority: LogPriority,
        tag: String?,
        message: String?,
        throwable: Throwable?,
    ) {
        val level = when (priority) {
            LogPriority.DEBUG -> "D"
            LogPriority.INFO -> "I"
            LogPriority.WARN -> "W"
            LogPriority.ERROR -> "E"
        }
        val text = listOfNotNull(message, throwable?.stackTraceToString()).joinToString(separator = "\n")
        NSLog("%@", "$level/${tag ?: DEFAULT_TAG}: $text")
    }
}