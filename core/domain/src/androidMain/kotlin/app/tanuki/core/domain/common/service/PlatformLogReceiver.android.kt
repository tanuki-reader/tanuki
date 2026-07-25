package app.tanuki.core.domain.common.service

import android.util.Log

/** Default tag used when a log event carries none. */
private const val DEFAULT_TAG = "Tanuki"

internal actual fun platformLogReceiver(): Logger.LogReceiver = LogcatLogReceiver

/**
 * [Logger.LogReceiver] that writes to Android's [Logcat][Log], mapping [LogPriority] onto Logcat's
 * levels ([LogPriority.DEBUG] → [Log.d] … [LogPriority.ERROR] → [Log.e]). A `throwable` is appended
 * as a stack trace via Logcat's `(tag, msg, tr)` overloads.
 *
 * The logger has already dropped [LogPriority.DEBUG] events in release builds, so this sink never has
 * to reason about the build type.
 */
internal object LogcatLogReceiver : Logger.LogReceiver {

    override fun onLog(
        priority: LogPriority,
        tag: String?,
        message: String?,
        throwable: Throwable?,
    ) {
        val resolvedTag = tag ?: DEFAULT_TAG
        val text = message ?: throwable?.message.orEmpty()
        when (priority) {
            LogPriority.DEBUG -> Log.d(resolvedTag, text, throwable)
            LogPriority.INFO -> Log.i(resolvedTag, text, throwable)
            LogPriority.WARN -> Log.w(resolvedTag, text, throwable)
            LogPriority.ERROR -> Log.e(resolvedTag, text, throwable)
        }
    }
}