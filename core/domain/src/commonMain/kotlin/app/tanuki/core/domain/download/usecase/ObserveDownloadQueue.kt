package app.tanuki.core.domain.download.usecase

import app.tanuki.core.domain.chapter.model.Chapter
import app.tanuki.core.domain.chapter.repository.ChapterRepository
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.download.model.ChapterDownload
import app.tanuki.core.domain.download.model.DownloadQueueSeries
import app.tanuki.core.domain.download.model.QueuedChapter
import app.tanuki.core.domain.download.repository.DownloadRepository
import app.tanuki.core.domain.manga.model.Manga
import app.tanuki.core.domain.manga.repository.MangaRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlin.uuid.Uuid

/**
 * Use case: the download queue, resolved and grouped by series — everything the queue screen renders.
 *
 * The join lives here rather than in the ViewModel, as it does in
 * [app.tanuki.core.domain.history.usecase.ObserveReadHistory]: what a queue row *is* — a chapter of a
 * series, with a state — is a domain fact, and the screen stays a projection of it.
 *
 * ### Why the resolution is cached rather than redone per emission
 *
 * The queue's own flow re-emits **once per downloaded page**, because that is how progress reaches the
 * UI ([DownloadRepository.setProgress]). Resolving every series and every chapter row on each of those
 * would put two database round trips between consecutive images of a chapter — the one place in this
 * feature where a naive join actually costs something. So the resolution is redone only when the *set of
 * queued chapters* changes, which is when a chapter is added, finishes, is cancelled, or fails.
 *
 * The cache is a local of the returned flow, so it belongs to each collector and dies with it: nothing
 * here is shared state, and a second collector cannot be served another's stale rows.
 *
 * Rows whose chapter or series no longer resolves are dropped rather than rendered blank. That is a
 * chapter pruned by a sync between two emissions — the record cascades away a moment later, so showing
 * a placeholder for something that is about to vanish is worse than simply not showing it.
 */
sealed interface ObserveDownloadQueue {
    operator fun invoke(): Flow<List<DownloadQueueSeries>>

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val downloadRepository: DownloadRepository,
        private val mangaRepository: MangaRepository,
        private val chapterRepository: ChapterRepository,
        private val dispatchers: DispatcherProvider,
    ) : ObserveDownloadQueue {
        override fun invoke(): Flow<List<DownloadQueueSeries>> =
            flow {
                var resolvedIds: Set<Uuid> = emptySet()
                var resolved = Resolved()

                downloadRepository.observeQueue().collect { records ->
                    val ids = records.mapTo(mutableSetOf(), ChapterDownload::chapterId)
                    if (ids != resolvedIds) {
                        resolved = resolve(records)
                        resolvedIds = ids
                    }
                    emit(group(records, resolved))
                }
            }.flowOn(dispatchers.io)

        /**
         * Two batch reads for the whole queue — and only the queued chapters, not every chapter of
         * every series they belong to.
         */
        private suspend fun resolve(records: List<ChapterDownload>): Resolved =
            Resolved(
                mangaById =
                    mangaRepository
                        .getMangaByIds(records.mapTo(mutableSetOf(), ChapterDownload::mangaId))
                        .associateBy(Manga::id),
                chapterById =
                    chapterRepository
                        .getChaptersByIds(records.mapTo(mutableSetOf(), ChapterDownload::chapterId))
                        .associateBy(Chapter::id),
            )

        /**
         * Projects the flat, oldest-queued-first record list into per-series groups.
         *
         * Both the chapters within a series and the series themselves keep the record list's order, so
         * the screen reads top to bottom as the order the engine will work in — the first row of the
         * first group is the chapter being fetched right now.
         */
        private fun group(
            records: List<ChapterDownload>,
            resolved: Resolved,
        ): List<DownloadQueueSeries> =
            records
                .mapNotNull { record ->
                    resolved.chapterById[record.chapterId]?.let { QueuedChapter(chapter = it, download = record) }
                }.groupBy { it.download.mangaId }
                .mapNotNull { (mangaId, chapters) ->
                    resolved.mangaById[mangaId]?.let { DownloadQueueSeries(manga = it, chapters = chapters) }
                }

        /** What the last resolution found, keyed for the projection above. */
        private class Resolved(
            val mangaById: Map<Uuid, Manga> = emptyMap(),
            val chapterById: Map<Uuid, Chapter> = emptyMap(),
        )
    }
}