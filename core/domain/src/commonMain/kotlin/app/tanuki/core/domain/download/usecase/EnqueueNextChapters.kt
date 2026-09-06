package app.tanuki.core.domain.download.usecase

import app.tanuki.core.domain.chapter.model.Chapter
import app.tanuki.core.domain.chapter.model.isHiddenBy
import app.tanuki.core.domain.chapter.repository.ChapterRepository
import app.tanuki.core.domain.manga.repository.MangaRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.first
import kotlin.uuid.Uuid

/**
 * Use case: download the next few chapters a series has waiting — what "Download → next 5 / next 10 /
 * all unread" calls, and the shape most of the feature's real use has.
 *
 * "Next" means **the unread chapters in reading order**, which is the same order and the same set the
 * chapter list shows, because the two rules that define it are shared rather than restated:
 *
 *  - **The scanlator filter applies** ([isHiddenBy]). Queueing a chapter the user has hidden would
 *    download something they cannot see, and on an aggregator where three groups publish the same
 *    chapter that is three times the bytes for one chapter's worth of reading.
 *  - **Read chapters are skipped**, including one that is only partly read: the point of "next" is what
 *    comes up, and a chapter already open in the reader is not it.
 *
 * Everything it selects goes through [EnqueueChapterDownloads], so being already queued or already
 * downloaded is handled there — asking for "next 5" twice does not queue ten.
 */
sealed interface EnqueueNextChapters {
    /**
     * @param mangaId The series to take chapters from.
     * @param count How many to queue, or `null` for every unread chapter ("all unread"). A [count] of
     *   zero or less queues nothing.
     * @return How many chapters were actually queued — fewer than [count] when the series has fewer
     *   unread chapters left, or when some were queued already.
     */
    suspend operator fun invoke(
        mangaId: Uuid,
        count: Int? = null,
    ): Int

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val mangaRepository: MangaRepository,
        private val chapterRepository: ChapterRepository,
        private val enqueueChapterDownloads: EnqueueChapterDownloads,
    ) : EnqueueNextChapters {
        override suspend fun invoke(
            mangaId: Uuid,
            count: Int?,
        ): Int {
            if (count != null && count <= 0) return 0
            val manga = mangaRepository.observeManga(mangaId).first() ?: return 0

            val next =
                chapterRepository
                    .getChapters(mangaId)
                    .asSequence()
                    .filterNot { it.isHiddenBy(manga.filteredScanlators) }
                    .filterNot { it.progress?.alreadyRead == true }
                    // The repository already reads chapters in chapter-number order; sorting again here
                    // keeps "next" meaningful if that ever stops being true, and costs nothing at these
                    // sizes. Unnumbered chapters sort last rather than first — a chapter whose number
                    // could not be recognized is the one case where "next" is a guess.
                    .sortedWith(compareBy(nullsLast<Double>()) { it.chapterNumber?.value })
                    .let { if (count == null) it else it.take(count) }
                    .map(Chapter::id)
                    .toList()

            return enqueueChapterDownloads(next)
        }
    }
}