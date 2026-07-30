package app.tanuki.core.domain.update.usecase

import app.tanuki.core.domain.update.model.ChapterUpdate
import app.tanuki.core.domain.update.model.MangaUpdateSummary
import app.tanuki.core.domain.update.model.NewChapter
import app.tanuki.core.domain.update.repository.ChapterUpdateRepository
import app.tanuki.core.domain.update.service.LibraryUpdateNotifier
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

/**
 * Use case: announce the chapters the user hasn't been told about yet, then remember that they have.
 *
 * ### It announces exactly what Home shows
 *
 * The pending records go through [ResolveChapterUpdates] — the same resolver behind the "New Chapters"
 * list — rather than being announced straight from the table. That is the whole reason the resolver is
 * a separate injectable: a notification naming a chapter from a scanlation group the user hid on that
 * series (or one they have already read) would be a notification the list then refuses to show. One
 * resolver, so the two cannot disagree.
 *
 * ### Marking, and why it happens after
 *
 * Every pending record is marked notified once the notification is posted — including the ones the
 * resolver dropped. They *were* considered; leaving them unmarked would make them pile up and be
 * reconsidered on every future run, and un-hiding a group months later would suddenly announce its
 * back catalogue.
 *
 * Marking after the post (rather than before) is what makes a killed run safe: a background task the OS
 * reclaims mid-notification leaves the records unmarked and the next run announces them. The cost of
 * being wrong in this direction is a repeated notification; the other direction loses it silently.
 */
sealed interface NotifyNewChapters {
    /** No-ops when nothing is pending, or when everything pending has since stopped being relevant. */
    suspend operator fun invoke()

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val chapterUpdateRepository: ChapterUpdateRepository,
        private val resolveChapterUpdates: ResolveChapterUpdates,
        private val notifier: LibraryUpdateNotifier
    ) : NotifyNewChapters {
        override suspend fun invoke() {
            val pending = chapterUpdateRepository.getUnnotified()
            if (pending.isEmpty()) return

            val summaries = resolveChapterUpdates(pending).toSummaries()
            if (summaries.isNotEmpty()) notifier.notifyNewChapters(summaries)

            chapterUpdateRepository.markNotified(pending.map(ChapterUpdate::chapterId))
        }

        /**
         * Collapses resolved chapters into one summary per series. Series keep the order the resolver
         * produced (most recently discovered first), so the newest news leads the notification.
         */
        private fun List<NewChapter>.toSummaries(): List<MangaUpdateSummary> =
            groupBy { it.manga.id }
                .map { (mangaId, chapters) ->
                    MangaUpdateSummary(
                        mangaId = mangaId,
                        title =
                            chapters
                                .first()
                                .manga.title.value,
                        newChapterCount = chapters.size,
                    )
                }
    }
}