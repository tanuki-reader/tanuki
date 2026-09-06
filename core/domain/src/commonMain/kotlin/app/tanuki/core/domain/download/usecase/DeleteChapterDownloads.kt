package app.tanuki.core.domain.download.usecase

import app.tanuki.core.domain.download.model.ChapterDownload
import app.tanuki.core.domain.download.repository.DownloadRepository
import app.tanuki.core.domain.download.service.ChapterDownloadStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlin.uuid.Uuid

/**
 * Use case: remove chapters from the device — the destructive counterpart to
 * [EnqueueChapterDownloads], and what "Delete download" means everywhere it appears.
 *
 * Unlike [CancelChapterDownloads] this applies to *any* state: a finished download, one still queued,
 * and the half-fetched remains of one that failed all go. Both halves are removed and in this order —
 * the engine is told to stop, then the rows, then the files.
 *
 * **The files are the point.** The database cascades a record away when its chapter is deleted, but a
 * foreign key cannot reach the filesystem, so nothing but an explicit call here reclaims the bytes.
 * That asymmetry is the reason downloads leak in readers that forget it (see `docs/design-downloads.md`
 * §9), and the reason this use case exists rather than callers deleting rows.
 */
sealed interface DeleteChapterDownloads {
    /**
     * @param chapterIds Local ids of the chapters to remove. Ids with nothing downloaded or queued are
     *   ignored.
     * @return How many chapters were removed.
     */
    suspend operator fun invoke(chapterIds: Collection<Uuid>): Int

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val downloadRepository: DownloadRepository,
        private val store: ChapterDownloadStore,
        private val downloadManager: DownloadManager,
    ) : DeleteChapterDownloads {
        override suspend fun invoke(chapterIds: Collection<Uuid>): Int {
            if (chapterIds.isEmpty()) return 0

            val records = chapterIds.distinct().mapNotNull { downloadRepository.get(it) }
            if (records.isEmpty()) return 0

            val ids = records.map(ChapterDownload::chapterId)
            downloadManager.abort(ids)
            downloadRepository.delete(ids)
            records.forEach { store.delete(it.mangaId, it.chapterId) }
            return records.size
        }
    }
}