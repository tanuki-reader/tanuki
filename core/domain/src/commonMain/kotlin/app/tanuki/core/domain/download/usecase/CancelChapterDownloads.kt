package app.tanuki.core.domain.download.usecase

import app.tanuki.core.domain.download.model.ChapterDownload
import app.tanuki.core.domain.download.repository.DownloadRepository
import app.tanuki.core.domain.download.service.ChapterDownloadStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlin.uuid.Uuid

/**
 * Use case: take chapters out of the download queue — the queue screen's cancel action, and the chapter
 * row's tap-while-downloading.
 *
 * Cancelling is **not** deleting: only work that hasn't finished is touched. A chapter that completed
 * while the user was reaching for the button keeps its files, because the thing they were cancelling has
 * already happened and throwing it away would be the one outcome they cannot undo. Removing a finished
 * download is [DeleteChapterDownloads], a different (and destructive) action.
 *
 * The order below is the order that matters: the engine is told to abandon the work **first**, so the
 * rows and the partial files are removed from underneath something that has already stopped writing to
 * them. A page still in flight when the call lands is dropped rather than written (see
 * [DownloadManager.abort]).
 */
sealed interface CancelChapterDownloads {
    /**
     * @param chapterIds Local ids of the chapters to stop downloading. Ids that aren't queued — never
     *   were, or already finished — are ignored.
     * @return How many queued chapters were actually cancelled.
     */
    suspend operator fun invoke(chapterIds: Collection<Uuid>): Int

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val downloadRepository: DownloadRepository,
        private val store: ChapterDownloadStore,
        private val downloadManager: DownloadManager,
    ) : CancelChapterDownloads {
        override suspend fun invoke(chapterIds: Collection<Uuid>): Int {
            if (chapterIds.isEmpty()) return 0

            val pending =
                chapterIds
                    .distinct()
                    .mapNotNull { downloadRepository.get(it) }
                    .filter(ChapterDownload::isPending)
            if (pending.isEmpty()) return 0

            downloadManager.abort(pending.map(ChapterDownload::chapterId))
            downloadRepository.delete(pending.map(ChapterDownload::chapterId))
            // Deletes the part directory these chapters were being assembled in. Nothing committed can
            // be lost here: a chapter with a committed directory is not pending.
            pending.forEach { store.delete(it.mangaId, it.chapterId) }
            return pending.size
        }
    }
}
