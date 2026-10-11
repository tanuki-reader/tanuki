package app.tanuki.core.domain.download.usecase

import app.tanuki.core.domain.download.model.ChapterDownload
import app.tanuki.core.domain.download.repository.DownloadRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

/**
 * Use case: observe one series' download records, keyed by chapter — what a chapter list reads to show
 * each row's download state.
 *
 * A thin pass-through over [DownloadRepository.observeForManga]; feature ViewModels depend on this
 * interface rather than the repository, so the rule for what a row shows can grow here without the UI
 * changing.
 *
 * A **map**, and one flow for the whole series, because the caller does one lookup per row: a chapter
 * list runs to thousands of rows, and either a per-row flow or a linear scan per row is exactly the shape
 * that makes scrolling it expensive. Chapters with no record are simply absent — "never queued" is the
 * overwhelmingly common case and does not deserve a row of its own.
 */
interface ObserveChapterDownloads {
    operator fun invoke(mangaId: Uuid): Flow<Map<Uuid, ChapterDownload>>

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val downloadRepository: DownloadRepository,
    ) : ObserveChapterDownloads {
        override fun invoke(mangaId: Uuid): Flow<Map<Uuid, ChapterDownload>> =
            downloadRepository.observeForManga(mangaId)
    }
}
