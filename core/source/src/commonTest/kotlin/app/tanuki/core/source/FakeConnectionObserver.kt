package app.tanuki.core.source

import app.tanuki.core.domain.common.service.ConnectionObserver
import app.tanuki.core.domain.common.service.ConnectionObserver.ConnectionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A [ConnectionObserver] whose status the test sets. Defaults to [ConnectionStatus.ONLINE] so a test
 * that isn't about connectivity doesn't have to say so.
 */
internal class FakeConnectionObserver(
    initial: ConnectionStatus = ConnectionStatus.ONLINE,
) : ConnectionObserver {
    private val state = MutableStateFlow(initial)

    override val connectivity: Flow<ConnectionStatus> = state.asStateFlow()

    override val status: ConnectionStatus get() = state.value

    /** Moves the device onto (or off) a network mid-test. */
    fun set(status: ConnectionStatus) {
        state.value = status
    }
}
