package app.tanuki.core.domain.download.usecase

import app.tanuki.core.domain.download.repository.DownloadRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Use case: how many chapters the download queue is holding — the Settings row's subtitle, and the one
 * number anywhere in the app that says the queue exists at all.
 *
 * Separate from [ObserveDownloadQueue] rather than derived from it, because a count needs none of the
 * join: resolving every series and chapter to then take a `size` is exactly the work a settings index
 * (which is rebuilt on every visit to the tab) should not be doing.
 *
 * The queue re-emits per downloaded page, and the count almost never changes between those, so it is
 * de-duplicated here — every subscriber wants that, and none of them should have to remember to ask.
 */
sealed interface ObserveDownloadQueueCount {
    operator fun invoke(): Flow<Int>

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val downloadRepository: DownloadRepository,
    ) : ObserveDownloadQueueCount {
        override fun invoke(): Flow<Int> =
            downloadRepository
                .observeQueue()
                .map { it.size }
                .distinctUntilChanged()
    }
}