package app.tanuki.core.domain.common.service

/**
 * [Logger] test double that records every event into [entries] instead of writing anywhere, so tests
 * can assert what was logged (or pass it where a `Logger` is merely a collaborator). Enforces the same
 * [LogPriority.DEBUG] debug-only contract as production is irrelevant here — nothing is suppressed, so
 * tests see exactly what call sites emit.
 */
class RecordingLogger : Logger {
    data class Entry(
        val priority: LogPriority,
        val tag: String?,
        val message: String?,
        val throwable: Throwable?,
    )

    val entries = mutableListOf<Entry>()

    override fun log(
        priority: LogPriority,
        tag: String?,
        message: String?,
        throwable: Throwable?,
        analyticsEvent: String?,
        addToBreadcrumbs: Boolean,
    ) {
        entries += Entry(priority, tag, message, throwable)
    }

    override fun addReceiver(receiver: Logger.LogReceiver) = Unit
    override fun removeReceiver(receiver: Logger.LogReceiver) = Unit
}