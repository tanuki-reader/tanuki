package app.tanuki

import androidx.lifecycle.ViewModel
import dev.zacsweers.metrox.viewmodel.MetroViewModelFactory
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ScreenScopeTest {
    private class RecordingViewModel : ViewModel() {
        var cleared = false

        override fun onCleared() {
            cleared = true
        }
    }

    private class UnregisteredViewModel : ViewModel()

    private val factory =
        object : MetroViewModelFactory() {
            override val viewModelProviders: Map<KClass<out ViewModel>, () -> ViewModel> =
                mapOf(RecordingViewModel::class to { RecordingViewModel() })
        }

    @Test
    fun sameClassReturnsSameInstance() {
        val scope = ScreenScope(factory)
        assertSame(scope.viewModel(RecordingViewModel::class), scope.viewModel(RecordingViewModel::class))
    }

    @Test
    fun closeClearsTheViewModel() {
        val scope = ScreenScope(factory)
        val viewModel = scope.viewModel(RecordingViewModel::class)
        assertFalse(viewModel.cleared)
        scope.close()
        assertTrue(viewModel.cleared)
    }

    @Test
    fun unregisteredViewModelFailsLoudly() {
        assertFailsWith<Throwable> { ScreenScope(factory).viewModel(UnregisteredViewModel::class) }
    }
}
