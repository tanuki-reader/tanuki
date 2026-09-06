package app.tanuki.core.domain.download.usecase

import app.tanuki.core.domain.common.service.ConnectionObserver
import app.tanuki.core.domain.download.model.DownloadHold
import app.tanuki.core.domain.settings.repository.SettingsRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Use case: whether the download queue may run, and if not, why.
 *
 * The single answer both halves of the feature read. [DownloadManager] stops on anything but
 * [DownloadHold.None]; the queue screen renders the same value as a sentence. Deriving it twice is how
 * a queue ends up stopped for a reason the UI does not know about — which is precisely the bug this
 * replaced, where "only on Wi-Fi" was enforced on one platform and explained on neither.
 *
 * ### Precedence
 *
 * [DownloadHold.Paused] outranks [DownloadHold.WaitingForWifi]. A user who paused the queue is owed
 * that answer even if the network would also have held it — their instruction is the one they will
 * remember giving, and "waiting for Wi-Fi" would read as the app ignoring it.
 *
 * ### Why the network is not treated as an error
 *
 * A metered connection under "only on Wi-Fi" is a *hold*, never a failure. Nothing is marked
 * [app.tanuki.core.domain.download.model.DownloadState.Failed], because nothing went wrong and there is
 * nothing for the user to retry: the queue resumes on its own when the device reaches Wi-Fi.
 */
sealed interface ObserveDownloadHold {
    operator fun invoke(): Flow<DownloadHold>

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val settingsRepository: SettingsRepository,
        private val connectionObserver: ConnectionObserver,
    ) : ObserveDownloadHold {
        override fun invoke(): Flow<DownloadHold> =
            combine(
                settingsRepository.observeDownloadsPaused(),
                settingsRepository.observeDownloadOnWifiOnly(),
                connectionObserver.metered,
            ) { paused, wifiOnly, metered ->
                when {
                    paused -> DownloadHold.Paused
                    wifiOnly && metered -> DownloadHold.WaitingForWifi
                    else -> DownloadHold.None
                }
            }.distinctUntilChanged()
    }
}
