package app.tanuki.core.domain.common.service

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import app.tanuki.core.domain.common.service.ConnectionObserver.ConnectionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android [ConnectionObserver] over [ConnectivityManager]'s **default network** — the one the app's
 * requests would actually go out on, rather than the set of networks that happen to exist. A device on
 * Wi-Fi with a live cellular radio has two networks and one answer.
 *
 * The callback is registered once, for the app's lifetime (this is an app-scoped singleton), and each
 * change re-reads the capabilities into [state]. Nothing unregisters it: the observer dies with the
 * process, and a callback torn down early would leave the app confidently reporting a stale status.
 *
 * Requires `ACCESS_NETWORK_STATE`. It is an install-time permission the app manifest declares, so it
 * is always held — hence the class-wide suppression rather than a `@RequiresPermission` chain that
 * would have to be threaded through the constructor and every `NetworkCallback` override.
 */
@SuppressLint("MissingPermission")
internal class AndroidConnectionObserver(
    context: Context,
) : ConnectionObserver {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    private val state = MutableStateFlow(manager.readStatus())
    private val meteredState = MutableStateFlow(manager.readMetered())

    override val connectivity: Flow<ConnectionStatus> = state.asStateFlow()

    override val status: ConnectionStatus get() = state.value

    override val metered: Flow<Boolean> = meteredState.asStateFlow()

    override val isMetered: Boolean get() = meteredState.value

    init {
        manager.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = refresh()

                override fun onLost(network: Network) = refresh()

                override fun onUnavailable() = refresh()

                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities
                ) = refresh()
            },
        )
    }

    private fun refresh() {
        state.value = manager.readStatus()
        meteredState.value = manager.readMetered()
    }

    /**
     * Whether the default network charges by the byte.
     *
     * `TEMPORARILY_NOT_METERED` counts as unmetered: it is a metered link the carrier has opened up (a
     * zero-rating window), and holding a download queue back through one would be refusing the very
     * thing the window is for. No network at all reads as metered — the safe direction, since the
     * setting exists to stop the app spending data the user did not offer.
     */
    private fun ConnectivityManager.readMetered(): Boolean {
        val capabilities = activeNetwork?.let(::getNetworkCapabilities) ?: return true
        return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED)
    }

    /**
     * Whether Data Saver is on and this app is not allowlisted out of it — the Android counterpart of
     * iOS's "constrained" path. Read from the manager rather than the capabilities, which describe the
     * *network*, not the user's policy about background data.
     */
    private fun ConnectivityManager.isDataSaverRestricted(): Boolean =
        restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED

    /**
     * Reads the current default network's capabilities into a status.
     *
     * [NetworkCapabilities.NET_CAPABILITY_VALIDATED] rather than mere presence: an associated Wi-Fi
     * behind a captive portal is a network that carries no traffic, and reporting it as online is
     * how an app ends up hanging on a request that was never going to complete.
     */
    private fun ConnectivityManager.readStatus(): ConnectionStatus {
        val capabilities = activeNetwork?.let(::getNetworkCapabilities) ?: return ConnectionStatus.OFFLINE
        val usable =
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        if (!usable) return ConnectionStatus.OFFLINE

        val metered = readMetered()
        // 0 means "unknown", not "no bandwidth" — a link that declines to say is not a slow one.
        val slow = capabilities.linkDownstreamBandwidthKbps in 1 until LOW_SPEED_KBPS
        return if (slow || (metered && isDataSaverRestricted())) {
            ConnectionStatus.LOW_SPEED
        } else {
            ConnectionStatus.ONLINE
        }
    }

    private companion object {
        /**
         * Below this, a link is reported as [ConnectionStatus.LOW_SPEED]. Chosen against what the app
         * actually moves: a manga page is a few hundred KB, so a link under ~1 Mbit turns a chapter
         * into a wait rather than a read.
         */
        const val LOW_SPEED_KBPS = 1_000
    }
}
