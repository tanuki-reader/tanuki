package app.tanuki.core.domain.download.usecase

import app.tanuki.core.domain.download.model.ChapterDownload
import app.tanuki.core.domain.download.repository.DownloadRepository
import app.tanuki.core.domain.download.service.ChapterDownloadStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlin.uuid.Uuid

/**
 * Use case: remove everything downloaded for one series — the manga screen's "delete all downloaded
 * chapters", and what removing a series from the library has to call on its way out.
 *
 * A whole-series operation rather than a loop over [DeleteChapterDownloads] because the store can drop
 * the series' directory in one move, and because the *rows* have to go even when no file does: a record
 * whose files vanished (the user cleared app storage) is exactly the drift this has to clear rather
 * than skip.
 */
sealed interface DeleteMangaDownloads {
    /**
     * @param mangaId The series to clear.
     * @return How many chapter records were removed. Zero is normal — most series have nothing
     *   downloaded — and does not mean nothing was deleted from disk.
     */
    suspend operator fun invoke(mangaId: Uuid): Int

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val downloadRepository: DownloadRepository,
        private val store: ChapterDownloadStore,
        private val downloadManager: DownloadManager,
    ) : DeleteMangaDownloads {
        override suspend fun invoke(mangaId: Uuid): Int {
            val records = downloadRepository.getAll().filter { it.mangaId == mangaId }

            downloadManager.abort(records.map(ChapterDownload::chapterId))
            downloadRepository.deleteForManga(mangaId)
            // Unconditional: the directory can outlive every record (a sync pruned the chapters, so the
            // foreign key took the rows and left the bytes), which is the case this exists to catch.
            store.deleteManga(mangaId)
            return records.size
        }
    }
}
