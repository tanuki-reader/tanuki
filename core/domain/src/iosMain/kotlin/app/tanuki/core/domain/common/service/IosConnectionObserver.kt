package app.tanuki.core.domain.common.service

import app.tanuki.core.domain.common.service.ConnectionObserver.ConnectionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Network.nw_path_get_status
import platform.Network.nw_path_is_constrained
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.Network.nw_path_t
import platform.darwin.dispatch_queue_create

/**
 * iOS [ConnectionObserver] over Network.framework's `nw_path_monitor` — the same mechanism `NWPathMonitor`
 * wraps in Swift, and the Apple-sanctioned replacement for `SCNetworkReachability`. It reports on the
 * **default path**: what a connection made right now would travel over, which is the question every
 * source request is really asking.
 *
 * The monitor is created and started once, for the app's lifetime (this is an app-scoped singleton), and
 * delivers updates on a dedicated serial queue; each one is folded into [state], which [status] reads.
 * Nothing cancels it — the observer dies with the process, and a cancelled monitor would leave the app
 * confidently reporting a stale status.
 *
 * Starts at [ConnectionStatus.ONLINE] rather than `OFFLINE`: the first update lands within milliseconds
 * of construction, and guessing "offline" in that window would refuse the app's very first request on a
 * perfectly good network. Guessing "online" costs at worst one request that fails the ordinary way.
 */
internal class IosConnectionObserver : ConnectionObserver {
    private val state = MutableStateFlow(ConnectionStatus.ONLINE)

    override val connectivity: Flow<ConnectionStatus> = state.asStateFlow()

    override val status: ConnectionStatus get() = state.value

    private val monitor = nw_path_monitor_create()

    init {
        nw_path_monitor_set_queue(monitor, dispatch_queue_create(QUEUE_LABEL, null))
        nw_path_monitor_set_update_handler(monitor) { path -> state.value = path.toStatus() }
        nw_path_monitor_start(monitor)
    }

    /**
     * Folds a path into a status.
     *
     * Only `satisfied` counts as usable: `unsatisfied` is no route, and `requiresConnection` means the
     * path would need a VPN or on-demand connection brought up first — a request over either is one the
     * app should not pretend it can make.
     *
     * Constrained (the user's Low Data Mode) is [ConnectionStatus.LOW_SPEED], not offline — it is a
     * request to be frugal, not a refusal. Expensive (cellular) deliberately is **not**: a 5G link is
     * expensive and fast, and treating it as slow would throttle the app on most phones.
     */
    private fun nw_path_t.toStatus(): ConnectionStatus =
        when {
            nw_path_get_status(this) != nw_path_status_satisfied -> ConnectionStatus.OFFLINE
            nw_path_is_constrained(this) -> ConnectionStatus.LOW_SPEED
            else -> ConnectionStatus.ONLINE
        }

    private companion object {
        /** Named so the queue is identifiable in a thread dump / Instruments trace. */
        const val QUEUE_LABEL = "app.tanuki.connectivity"
    }
}
