package app.tanuki.core.domain.download.usecase

import app.tanuki.core.domain.download.model.ChapterDownload
import app.tanuki.core.domain.download.model.DownloadState
import app.tanuki.core.domain.download.repository.DownloadRepository
import app.tanuki.core.domain.download.service.ChapterDownloadStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

/**
 * Use case: empty the queue — the queue screen's "cancel all", behind a confirmation.
 *
 * **Not** [DeleteAllDownloads]. This clears only what the queue screen shows: chapters waiting, the one
 * being fetched, and the ones that failed. Chapters already on the device are untouched, because a user
 * clearing a queue is saying "stop fetching", not "throw away what I already have" — and those are the
 * two actions that must never be confusable, since only one of them can't be undone.
 *
 * Like [CancelChapterDownloads], the engine is told to abandon its work first, so the rows and the
 * partial files go away from underneath something that has already stopped writing to them.
 */
sealed interface CancelAllDownloads {
    /** @return How many chapters were taken out of the queue. */
    suspend operator fun invoke(): Int

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val downloadRepository: DownloadRepository,
        private val store: ChapterDownloadStore,
        private val downloadManager: DownloadManager,
    ) : CancelAllDownloads {
        override suspend fun invoke(): Int {
            val queued = downloadRepository.getAll().filter { it.state != DownloadState.Downloaded }
            if (queued.isEmpty()) return 0

            val chapterIds = queued.map(ChapterDownload::chapterId)
            downloadManager.abort(chapterIds)
            downloadRepository.delete(chapterIds)
            // Only ever a `.part` directory: a chapter with a committed one is `Downloaded`, and those
            // were filtered out above.
            queued.forEach { store.delete(it.mangaId, it.chapterId) }
            return queued.size
        }
    }
}