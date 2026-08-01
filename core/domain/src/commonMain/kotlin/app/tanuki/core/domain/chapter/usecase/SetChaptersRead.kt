package app.tanuki.core.domain.chapter.usecase

import app.tanuki.core.domain.chapter.model.Chapter
import app.tanuki.core.domain.chapter.model.ReadingProgress
import app.tanuki.core.domain.chapter.repository.ChapterRepository
import app.tanuki.core.domain.update.repository.ChapterUpdateRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlin.uuid.Uuid

/**
 * Use case: mark a set of a manga's chapters read or unread in one go — what the chapter list's
 * multi-select ("mark the 40 chapters above this one as read") calls.
 *
 * Deliberately **not** [UpdateReadingProgress]: that one models *reading* a chapter and therefore latches
 * `alreadyRead` on and stamps history, so it can neither un-read a chapter nor mark forty of them without
 * inventing forty reading sessions. This is the user asserting a state instead, so:
 *  - **It goes both ways.** [read] `false` clears the flag, which is the only path back from a mistaken
 *    mark (or a re-read of a series).
 *  - **Marking unread resets the page.** An unread chapter that still remembers page 37 would resume
 *    mid-chapter on the next open, which is not what "unread" means anywhere else in the app. That also
 *    makes it the way to discard progress on a chapter that was only started, not finished.
 *  - **Bookmarks survive**, exactly as in [UpdateReadingProgress] — a bookmark is the user's own marker
 *    and no read-state change may drop it.
 *  - **No history is written.** History drives "continue reading"; bulk-marking a back catalogue read is
 *    housekeeping, not reading, and stamping it would push forty chapters the user never opened to the
 *    top of the Activity feed.
 *
 * Marking read still clears the chapters' "new chapter" records, the same acknowledgement
 * [UpdateReadingProgress] performs when a chapter is opened: a chapter the user has declared read has no
 * business sitting in the updates feed. Marking unread does not put them back — the update rows record
 * what a *sync* found, and re-listing them would resurrect chapters the user has already seen.
 *
 * Chapters already in the requested state are skipped, so re-applying the action writes nothing and the
 * observing chapter list stays quiet.
 */
sealed interface SetChaptersRead {
    /**
     * @param mangaId Local id of the manga the chapters belong to — the whole selection is one series, so
     *   the chapters are read back in one query rather than one per id.
     * @param chapterIds Local ids of the chapters to mark. Ids that don't belong to [mangaId] are ignored.
     * @param read `true` marks them read, `false` marks them unread (and resets their page).
     */
    suspend operator fun invoke(
        mangaId: Uuid,
        chapterIds: Set<Uuid>,
        read: Boolean
    )

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val chapterRepository: ChapterRepository,
        private val chapterUpdateRepository: ChapterUpdateRepository
    ) : SetChaptersRead {
        override suspend fun invoke(
            mangaId: Uuid,
            chapterIds: Set<Uuid>,
            read: Boolean
        ) {
            if (chapterIds.isEmpty()) return
            val targets =
                chapterRepository
                    .getChapters(mangaId)
                    .filter { it.id in chapterIds && it.needsChange(read) }
            if (targets.isEmpty()) return

            chapterRepository.updateProgress(
                targets.associate { chapter ->
                    chapter.id to
                        ReadingProgress(
                            hasBookmark = chapter.progress?.hasBookmark ?: false,
                            // Marking read keeps where the user actually got to (a partially-read chapter
                            // marked read still resumes there); marking unread rewinds to the first page.
                            lastPageRead = if (read) chapter.progress?.lastPageRead ?: 0u else 0u,
                            alreadyRead = read
                        )
                }
            )

            if (read) {
                chapterUpdateRepository.deleteChaptersForManga(
                    mangaId = mangaId,
                    chapterIds = targets.map(Chapter::id)
                )
            }
        }

        /**
         * Whether marking this chapter would actually change anything. A chapter with no progress row is
         * unread on a blank page, so only marking it read does. Un-reading also covers a chapter the user
         * merely got partway through: its saved page is what "unread" has to clear, even though its read
         * flag was already `false`.
         */
        private fun Chapter.needsChange(read: Boolean): Boolean {
            val current = progress ?: return read
            return if (read) !current.alreadyRead else current.alreadyRead || current.lastPageRead > 0u
        }
    }
}