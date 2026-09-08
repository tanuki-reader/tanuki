package app.tanuki.core.presentation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins what [StateViewModel] exists to guarantee: the pipeline runs only while the screen is watching.
 *
 * [Dispatchers.setMain] is not incidental — `viewModelScope` is built on `Dispatchers.Main.immediate`,
 * and `runTest` adopts the scheduler of a test dispatcher installed as Main. Without it the sharing
 * coroutine would run on a scope this test cannot advance, and every timing assertion below would be
 * about wall-clock luck rather than about `WhileSubscribed`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StateViewModelTest {
    private val mainDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the pipeline is not collected before uiState has a subscriber`() = runTest {
        val upstream = TrackedFlow(MutableStateFlow(1))
        val viewModel = TestStateViewModel(upstream.flow, mainDispatcher)

        // Touching uiState builds it; only collecting it should start the upstream.
        assertEquals(0, viewModel.uiState.value)
        runCurrent()

        assertEquals(0, upstream.starts)
    }

    @Test
    fun `subscribing starts the pipeline and publishes its value`() = runTest {
        val upstream = TrackedFlow(MutableStateFlow(7))
        val viewModel = TestStateViewModel(upstream.flow, mainDispatcher)

        val job = launch { viewModel.uiState.collect {} }
        runCurrent()

        assertEquals(1, upstream.starts)
        assertEquals(7, viewModel.uiState.value)
        job.cancel()
    }

    @Test
    fun `the pipeline stops once the last subscriber has been gone for the timeout`() = runTest {
        val upstream = TrackedFlow(MutableStateFlow(7))
        val viewModel = TestStateViewModel(upstream.flow, mainDispatcher)

        val job = launch { viewModel.uiState.collect {} }
        runCurrent()
        job.cancel()
        advanceTimeBy(STOP_TIMEOUT + 1)
        runCurrent()

        assertEquals(1, upstream.completions)
    }

    @Test
    fun `a subscriber returning within the timeout resumes the same collection`() = runTest {
        val upstream = TrackedFlow(MutableStateFlow(7))
        val viewModel = TestStateViewModel(upstream.flow, mainDispatcher)

        val first = launch { viewModel.uiState.collect {} }
        runCurrent()
        first.cancel()
        advanceTimeBy(STOP_TIMEOUT - 1)
        val second = launch { viewModel.uiState.collect {} }
        runCurrent()

        assertEquals(0, upstream.completions)
        assertEquals(1, upstream.starts)
        second.cancel()
    }

    @Test
    fun `the last value is retained across a stop rather than falling back to the initial one`() = runTest {
        val source = MutableStateFlow(7)
        val upstream = TrackedFlow(source)
        val viewModel = TestStateViewModel(upstream.flow, mainDispatcher)

        val job = launch { viewModel.uiState.collect {} }
        runCurrent()
        source.value = 42
        runCurrent()
        job.cancel()
        advanceTimeBy(STOP_TIMEOUT + 1)
        runCurrent()

        assertEquals(42, viewModel.uiState.value)
    }

    private companion object {
        /** Mirrors `TanukiViewModel.STOP_TIMEOUT_MS`, which is private to the base. */
        const val STOP_TIMEOUT = 5_000L
    }
}

/** Counts how often a flow is subscribed to and how often that subscription ends. */
private class TrackedFlow<T>(source: Flow<T>) {
    var starts = 0
        private set
    var completions = 0
        private set

    val flow: Flow<T> =
        source
            .onStart { starts++ }
            .onCompletion { completions++ }
}

private class TestStateViewModel(
    pipeline: Flow<Int>,
    dispatcher: TestDispatcher
) : StateViewModel<Int>(TestDispatcherProvider(dispatcher), RecordingLogger(), initialState = 0) {
    override val state: Flow<Int> = pipeline
}
