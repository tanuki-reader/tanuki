package app.tanuki.core.domain.download.usecase

import app.tanuki.core.domain.bookmark.repository.PageBookmarkRepository
import app.tanuki.core.domain.chapter.model.Chapter
import app.tanuki.core.domain.chapter.repository.ChapterRepository
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.common.service.debug
import app.tanuki.core.domain.download.model.ChapterDownload
import app.tanuki.core.domain.download.repository.DownloadRepository
import app.tanuki.core.domain.settings.model.DeleteAfterReading
import app.tanuki.core.domain.settings.repository.SettingsRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.first
import kotlin.uuid.Uuid

/**
 * Use case: reclaim the space a chapter took, once the user is done with it.
 *
 * Called wherever a chapter *becomes* read — the reader reaching the last page, and the chapter list's
 * "mark read" — so the setting means the same thing however the chapter got there.
 *
 * ### It works on the series, not on the chapters it was handed
 *
 * "Keep the last N read chapters" is a statement about a series, and it can only be answered by looking
 * at all of them: marking chapter 40 read is what makes chapter 35 the one to delete. So the ids it
 * receives are a *trigger*, not the target — the target is whatever the rule leaves over afterwards.
 * [DeleteAfterReading.Immediately] is the same rule with `N = 0` rather than a special case.
 *
 * ### What it will not touch
 *
 * Only chapters that are **downloaded** are considered, so a queued or failed one is never quietly
 * cancelled by reading a different chapter — and only *read* ones, so nothing the user has not seen is
 * ever deleted. Bookmarked chapters are kept regardless: a bookmark is the user saying "come back to
 * this", which is exactly the opposite of what this setting is for.
 */
sealed interface DeleteReadChapterDownloads {
    /**
     * @param mangaId The series a chapter of which was just read.
     * @return How many downloads were deleted.
     */
    suspend operator fun invoke(mangaId: Uuid): Int

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val settingsRepository: SettingsRepository,
        private val chapterRepository: ChapterRepository,
        private val downloadRepository: DownloadRepository,
        private val bookmarkRepository: PageBookmarkRepository,
        private val deleteChapterDownloads: DeleteChapterDownloads,
        private val logger: Logger,
    ) : DeleteReadChapterDownloads {
        override suspend fun invoke(mangaId: Uuid): Int {
            val keep = settingsRepository.observeDeleteAfterReading().first().keepReadChapters ?: return 0

            // Only the *finished* ones. A queued or failed chapter must never be quietly cancelled by
            // the user reading a different chapter of the same series.
            val downloaded =
                downloadRepository
                    .observeForManga(mangaId)
                    .first()
                    .filterValues(ChapterDownload::isComplete)
                    .keys
            if (downloaded.isEmpty()) return 0

            val bookmarked = bookmarkRepository.bookmarkedChapterIds(mangaId)
            val expendable =
                chapterRepository
                    .getChapters(mangaId)
                    .filter { it.isExpendable(downloaded, bookmarked) }
                    // Highest chapter number last, so dropping from the end keeps the *latest* read
                    // chapters — the ones a reader flicks back to. Chapters the source left unnumbered
                    // sort first and are therefore deleted first, which is the right guess: an
                    // unnumbered extra is not what anyone flicks back to.
                    .sortedWith(compareBy(nullsFirst()) { it.chapterNumber?.value })
                    .dropLast(keep)
            if (expendable.isEmpty()) return 0

            deleteChapterDownloads(expendable.map(Chapter::id))
            logger.debug(TAG, "Deleted ${expendable.size} read chapter download(s) of $mangaId")
            return expendable.size
        }

        /**
         * Read, downloaded, and not bookmarked (no page of it is) — all three, or it stays.
         *
         * `downloadedIds` is the *record* index rather than the disk, which is the right authority here:
         * a chapter with no record is one this setting has no business deleting files for.
         */
        private fun Chapter.isExpendable(
            downloadedIds: Set<Uuid>,
            bookmarkedIds: Set<Uuid>,
        ): Boolean = progress?.alreadyRead == true && id in downloadedIds && id !in bookmarkedIds

        private companion object {
            const val TAG = "DeleteReadChapterDownloads"
        }
    }
}
