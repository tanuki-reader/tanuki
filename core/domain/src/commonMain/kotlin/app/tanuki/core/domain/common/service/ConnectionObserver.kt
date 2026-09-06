package app.tanuki.core.domain.common.service

import kotlinx.coroutines.flow.Flow

/**
 * Whether the device can currently reach the network, and how well.
 *
 * Provided per platform via Metro (Android watches the default network through `ConnectivityManager`;
 * iOS watches the default path through Network.framework's `nw_path_monitor`), because neither answer
 * can be derived in shared code. Implementations are app-scoped singletons that start watching once and
 * hold the answer, so [status] is a field read rather than a system query — every source request is
 * about to consult it.
 *
 * Its purpose is to make the *absence* of a network a first-class, cheap answer instead of a transport
 * failure discovered a round-trip later. It is deliberately **not** a reachability test of any
 * particular host: a validated network can still fail, so callers keep handling transport errors — this
 * only lets them skip the attempt when it is guaranteed to fail (see [OfflineException]).
 */
interface ConnectionObserver {
    /**
     * The connection status, re-emitted whenever it changes and starting with the current one. Conflated
     * to distinct values, so a network swap that lands on the same status is not an emission.
     */
    val connectivity: Flow<ConnectionStatus>

    /**
     * The status right now, without collecting [connectivity]. What request paths check: the value is
     * already in memory, so the guard costs nothing next to the request it is guarding.
     */
    val status: ConnectionStatus

    /** Convenience for the one question nearly every caller asks. See [ConnectionStatus.isOnline]. */
    val isOnline: Boolean get() = status.isOnline

    enum class ConnectionStatus {
        /** A usable, unconstrained network. */
        ONLINE,

        /** No network at all, or one that cannot reach the internet (a captive portal, a dead Wi-Fi). */
        OFFLINE,

        /**
         * Connected, but over a link the user would rather we were frugal with — a slow cellular link,
         * or one the OS reports as constrained (iOS Low Data Mode, Android Data Saver).
         *
         * Still online: nothing is blocked on it. It exists so bulk work (a library-wide update, a
         * queued download run) can choose to wait, while a screen the user is looking at still loads.
         */
        LOW_SPEED;

        /** `true` for anything but [OFFLINE] — a request is worth attempting. */
        val isOnline: Boolean get() = this != OFFLINE
    }
}

/**
 * Thrown instead of attempting a request the device has no network to make.
 *
 * A distinct type rather than a generic I/O failure so the layers above can tell "you are offline" from
 * "the source is broken" — the first is the user's to fix and is not worth a retry, the second is not.
 * Carries no cause: nothing was attempted.
 */
class OfflineException(
    message: String = "No network connection",
) : Exception(message)
