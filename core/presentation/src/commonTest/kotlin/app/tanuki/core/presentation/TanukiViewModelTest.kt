package app.tanuki.core.presentation

import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.common.service.LogPriority
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.navigation.Destination
import app.tanuki.core.navigation.MangaDetail
import app.tanuki.core.navigation.NavEvent
import app.tanuki.core.navigation.SettingsAppearance
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class TanukiViewModelTest {
    @Test
    fun `navigateTo emits a NavTo carrying the destination`() = runTest {
        val viewModel = TestViewModel()
        val destination = MangaDetail(Uuid.random())

        viewModel.goTo(destination)

        assertEquals(NavEvent.NavTo(destination), viewModel.navEvents.first())
    }

    @Test
    fun `navigateBack emits NavBack`() = runTest {
        val viewModel = TestViewModel()

        viewModel.goBack()

        assertEquals(NavEvent.NavBack, viewModel.navEvents.first())
    }

    @Test
    fun `intents emitted before collection are buffered and preserve order`() = runTest {
        val viewModel = TestViewModel()

        // Nothing is collecting yet — a tap during the first composition must not be dropped.
        viewModel.goTo(SettingsAppearance)
        viewModel.goBack()

        assertEquals(
            listOf(NavEvent.NavTo(SettingsAppearance), NavEvent.NavBack),
            viewModel.navEvents.take(2).toList()
        )
    }

    @Test
    fun `an intent is delivered once and never replayed`() = runTest {
        val viewModel = TestViewModel()
        viewModel.goBack()

        assertEquals(NavEvent.NavBack, viewModel.navEvents.first())
        assertNull(withTimeoutOrNull(1_000) { viewModel.navEvents.first() })
    }

    @Test
    fun `tag defaults to the concrete class name`() {
        assertEquals("TestViewModel", TestViewModel().tagForTest)
    }

    @Test
    fun `io runs the block on the io dispatcher and returns its value`() = runTest {
        val viewModel = TestViewModel()

        assertEquals(7, viewModel.runIo { 7 })
    }

    @Test
    fun `tryIo returns the value and logs nothing on success`() = runTest {
        val logger = RecordingLogger()
        val viewModel = TestViewModel(logger)

        val result = viewModel.runTryIo("should not be logged") { "ok" }

        assertEquals("ok", result.getOrNull())
        assertTrue(logger.entries.isEmpty())
    }

    @Test
    fun `tryIo captures a failure and logs it as a warning tagged with the class`() = runTest {
        val logger = RecordingLogger()
        val viewModel = TestViewModel(logger)
        val boom = IllegalStateException("boom")

        val result = viewModel.runTryIo("Load failed") { throw boom }

        assertEquals(boom, result.exceptionOrNull())
        assertEquals(
            listOf(LogEntry(LogPriority.WARN, "TestViewModel", "Load failed", boom)),
            logger.entries
        )
    }

    @Test
    fun `tryIo lets cancellation through instead of reporting it as a failure`() = runTest {
        val logger = RecordingLogger()
        val viewModel = TestViewModel(logger)

        // A load cancelled by a tab switch must unwind silently, not paint an error over the
        // state that superseded it.
        assertFailsWith<CancellationException> {
            viewModel.runTryIo("Load failed") { throw CancellationException("superseded") }
        }
        assertTrue(logger.entries.isEmpty())
    }
}

/**
 * Minimal concrete [TanukiViewModel]. The base's helpers are `protected`, so the test reaches them
 * through these pass-throughs rather than by relaxing their visibility for testing.
 */
private class TestViewModel(
    logger: Logger = RecordingLogger()
) : TanukiViewModel(TestDispatcherProvider(), logger) {
    val tagForTest: String get() = tag

    fun goTo(destination: Destination) = navigateTo(destination)

    fun goBack() = navigateBack()

    suspend fun <T> runIo(block: suspend () -> T): T = io { block() }

    suspend fun <T> runTryIo(
        what: String,
        block: suspend () -> T
    ): Result<T> = tryIo(what) { block() }
}

private class TestDispatcherProvider(
    private val dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher()
) : DispatcherProvider {
    override val default: CoroutineDispatcher get() = dispatcher
    override val io: CoroutineDispatcher get() = dispatcher
    override val main: CoroutineDispatcher get() = dispatcher
}

private data class LogEntry(
    val priority: LogPriority,
    val tag: String?,
    val message: String?,
    val throwable: Throwable?
)

private class RecordingLogger : Logger {
    val entries = mutableListOf<LogEntry>()

    override fun log(
        priority: LogPriority,
        tag: String?,
        message: String?,
        throwable: Throwable?,
        analyticsEvent: String?,
        addToBreadcrumbs: Boolean
    ) {
        entries += LogEntry(priority, tag, message, throwable)
    }

    override fun addReceiver(receiver: Logger.LogReceiver) = Unit

    override fun removeReceiver(receiver: Logger.LogReceiver) = Unit
}
