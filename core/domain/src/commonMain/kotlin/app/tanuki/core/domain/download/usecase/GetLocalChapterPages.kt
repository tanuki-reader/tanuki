package app.tanuki.core.domain.download.usecase

import app.tanuki.core.domain.chapter.repository.ChapterRepository
import app.tanuki.core.domain.download.service.ChapterDownloadStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlin.uuid.Uuid

/**
 * Use case: the on-disk pages of a downloaded chapter, in reading order, or `null` when it is not fully
 * downloaded.
 *
 * The whole of the offline read path's question, asked of the **disk** rather than of the database. The
 * `chapter_downloads` row says a chapter was downloaded; a committed `manifest.json` says it still is,
 * and only the second survives the user clearing app storage or a restore that dropped the files. Since
 * getting this wrong means the reader opening a chapter it cannot render — offline, which is exactly
 * when there is no falling back to the source — the authority is the one that cannot be stale.
 *
 * Page **order** comes from the manifest, never from sorting file names and never from re-asking the
 * source, so a downloaded chapter reads the same forever even if the source later renumbers or delists
 * it.
 */
sealed interface GetLocalChapterPages {
    /**
     * @param chapterId Local id of the chapter to read from disk.
     * @return Absolute paths to its pages in reading order, or `null` when the chapter is unknown or is
     *   not fully downloaded — including when a partial `.part` directory exists, which is not a
     *   download, and when the manifest lists no pages at all, which is not a chapter.
     */
    suspend operator fun invoke(chapterId: Uuid): List<String>?

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val chapterRepository: ChapterRepository,
        private val store: ChapterDownloadStore,
    ) : GetLocalChapterPages {
        override suspend fun invoke(chapterId: Uuid): List<String>? {
            // The store is addressed by (mangaId, chapterId) so that no read has to scan the downloads
            // root; the chapter row is where the series id comes from.
            val chapter = chapterRepository.getChapter(chapterId) ?: return null
            // An empty result is treated as "not downloaded" rather than as a chapter with no pages: the
            // engine refuses to commit one, so this only happens to a directory something else corrupted,
            // and reading it as a download would show a blank reader instead of falling back to the source.
            return store.pagePaths(chapter.mangaId, chapterId)?.takeIf { it.isNotEmpty() }
        }
    }
}
