package app.tanuki.core.presentation

import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.common.service.LogPriority
import app.tanuki.core.domain.common.service.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * Shared fakes for this package's tests.
 *
 * `internal` rather than `private` per file: Kotlin/Native rejects two identically-named private
 * top-level declarations in the same package, so each test file redeclaring its own would break the
 * native compilation the JVM one tolerates.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class TestDispatcherProvider(
    private val dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher()
) : DispatcherProvider {
    override val default: CoroutineDispatcher get() = dispatcher
    override val io: CoroutineDispatcher get() = dispatcher
    override val main: CoroutineDispatcher get() = dispatcher
}

internal data class LogEntry(
    val priority: LogPriority,
    val tag: String?,
    val message: String?,
    val throwable: Throwable?,
    val analyticsEvent: String? = null
)

internal class RecordingLogger : Logger {
    val entries = mutableListOf<LogEntry>()

    override fun log(
        priority: LogPriority,
        tag: String?,
        message: String?,
        throwable: Throwable?,
        analyticsEvent: String?,
        addToBreadcrumbs: Boolean
    ) {
        entries += LogEntry(priority, tag, message, throwable, analyticsEvent)
    }

    override fun addReceiver(receiver: Logger.LogReceiver) = Unit

    override fun removeReceiver(receiver: Logger.LogReceiver) = Unit
}
