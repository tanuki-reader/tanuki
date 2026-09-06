package app.tanuki.core.domain.download.usecase

import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.download.model.DownloadStorageUsage
import app.tanuki.core.domain.download.model.SeriesStorage
import app.tanuki.core.domain.download.repository.DownloadRepository
import app.tanuki.core.domain.download.service.ChapterDownloadStore
import app.tanuki.core.domain.manga.repository.MangaRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlin.uuid.Uuid

/**
 * Use case: what the downloads take on disk, broken down by series.
 *
 * ### Measured, not summed
 *
 * The totals come from [ChapterDownloadStore], which walks the files, rather than from adding up
 * `ChapterDownload.bytes`. The two can disagree — a write that died mid-flight, a `.part` directory an
 * abandoned run left behind, storage the user cleared from the OS settings — and the number this screen
 * exists to justify ("should I delete something?") is only useful if it matches what the system would
 * report. Summing the records would show a tidy figure that is not the one taking up the space.
 *
 * ### Recomputed when the *set* of downloads changes, not per byte
 *
 * Driven by [DownloadRepository.observeDownloadedCounts], which changes when a chapter finishes or is
 * deleted — not by the queue's own flow, which re-emits once per page and would send this walking the
 * whole downloads tree between consecutive images. Progress mid-chapter is not on disk in a committed
 * form anyway, so there is nothing to report until a chapter lands.
 */
sealed interface ObserveDownloadStorageUsage {
    operator fun invoke(): Flow<DownloadStorageUsage>

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val downloadRepository: DownloadRepository,
        private val mangaRepository: MangaRepository,
        private val store: ChapterDownloadStore,
        private val dispatchers: DispatcherProvider,
    ) : ObserveDownloadStorageUsage {
        override fun invoke(): Flow<DownloadStorageUsage> =
            downloadRepository
                .observeDownloadedCounts()
                // Nothing is emitted eagerly: the first read is slow (Room, then a directory walk) and
                // an empty total flashed ahead of the real one would read as "nothing is downloaded",
                // which is the one wrong answer this screen can give. The caller shows a spinner.
                .map { counts -> usage(counts) }
                .flowOn(dispatchers.io)

        private suspend fun usage(counts: Map<Uuid, Int>): DownloadStorageUsage {
            val series =
                counts
                    .mapNotNull { (mangaId, chapterCount) ->
                        val manga = mangaRepository.observeManga(mangaId).first() ?: return@mapNotNull null
                        SeriesStorage(
                            manga = manga,
                            chapterCount = chapterCount,
                            bytes = store.usedBytes(mangaId),
                        )
                    }
                    // Largest first: the screen exists to answer "what is taking the room", and the
                    // answer is at the top rather than found by scrolling an alphabetical list.
                    .sortedByDescending(SeriesStorage::bytes)
            return DownloadStorageUsage(totalBytes = store.usedBytes(), series = series)
        }
    }
}
