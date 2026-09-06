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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
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
 * ### It is triggered by the files, not only by the records
 *
 * [ChapterDownloadStore.observeChanges] is the trigger that matters, and getting this wrong is what
 * makes a storage figure lie. Deleting downloads removes the **rows first and the files second**, so a
 * reader woken only by the rows re-measures while the bytes are still on disk — and then never hears
 * about the delete that followed, leaving "1.2 GB used" on screen over an empty directory. The store is
 * the authority on the number, so the store has to be the thing that says the number moved.
 *
 * The record counts are still combined in, because they carry the per-series chapter count the
 * breakdown shows. Between the two triggers a delete produces two emissions — one stale, one correct —
 * rather than one stale one that sticks.
 *
 * Neither trigger fires per written page: a chapter in progress grows the on-disk total silently and the
 * commit at the end corrects it. The alternative is walking the whole downloads tree between two images.
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
            combine(
                downloadRepository.observeDownloadedCounts(),
                // `onStart` because the store's ticks are not replayed — a subscriber must not wait for
                // the next delete to get its first measurement.
                store.observeChanges().onStart { emit(Unit) },
            ) { counts, _ -> counts }
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
