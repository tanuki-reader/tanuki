package app.tanuki.core.domain.download.usecase

import app.tanuki.core.domain.download.model.ChapterDownload
import app.tanuki.core.domain.download.repository.DownloadRepository
import app.tanuki.core.domain.download.service.ChapterDownloadStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

/**
 * Use case: empty the downloads — Settings' "delete all downloads", behind a confirmation.
 *
 * This also ends any run in flight, and does it the way everything else does: by taking the work away.
 * Aborting the active chapter and deleting every row leaves the engine's next turn with an empty queue,
 * so it stops on its own rather than being told to — which means there is no window where a stopped
 * engine still has rows waiting for it.
 */
sealed interface DeleteAllDownloads {
    /** @return How many chapter records were removed. */
    suspend operator fun invoke(): Int

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val downloadRepository: DownloadRepository,
        private val store: ChapterDownloadStore,
        private val downloadManager: DownloadManager,
    ) : DeleteAllDownloads {
        override suspend fun invoke(): Int {
            val records = downloadRepository.getAll()

            downloadManager.abort(records.map(ChapterDownload::chapterId))
            downloadRepository.deleteAll()
            // Unconditional, like the per-series case: directories can outlive every record, and this is
            // the action the user takes precisely because they want the bytes back.
            store.deleteAll()
            return records.size
        }
    }
}
