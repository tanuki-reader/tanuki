package app.tanuki.core.data.repository.download

import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.download.model.ChapterDownload
import app.tanuki.core.domain.download.model.DownloadError
import app.tanuki.core.domain.download.model.DownloadState
import app.tanuki.core.domain.download.repository.DownloadRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Room-backed [DownloadRepository]. */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DownloadRepositoryImp(
    private val chapterDownloadDao: ChapterDownloadDao,
    private val dispatcherProvider: DispatcherProvider
) : DownloadRepository {
    override fun observeQueue(): Flow<List<ChapterDownload>> =
        chapterDownloadDao
            .observeInStates(QUEUE_STATES)
            .map { downloads -> downloads.map(ChapterDownloadEntity::toDomain) }
            .flowOn(dispatcherProvider.io)

    override fun observeForManga(mangaId: Uuid): Flow<Map<Uuid, ChapterDownload>> =
        chapterDownloadDao
            .observeForManga(mangaId)
            .map { downloads -> downloads.associate { it.chapterId to it.toDomain() } }
            .flowOn(dispatcherProvider.io)

    override fun observeDownloadedCounts(): Flow<Map<Uuid, Int>> =
        chapterDownloadDao
            .observeCountsInState(DownloadState.Downloaded)
            .map { counts -> counts.associate { it.mangaId to it.count } }
            .flowOn(dispatcherProvider.io)

    override suspend fun get(chapterId: Uuid): ChapterDownload? =
        withContext(dispatcherProvider.io) { chapterDownloadDao.get(chapterId)?.toDomain() }

    override suspend fun nextQueued(): ChapterDownload? =
        withContext(dispatcherProvider.io) {
            chapterDownloadDao.firstInState(DownloadState.Queued)?.toDomain()
        }

    override suspend fun enqueue(downloads: List<ChapterDownload>) {
        if (downloads.isEmpty()) return
        withContext(dispatcherProvider.io) {
            chapterDownloadDao.insertIgnoring(downloads.map(ChapterDownload::toEntity))
        }
    }

    override suspend fun update(download: ChapterDownload) =
        withContext(dispatcherProvider.io) { chapterDownloadDao.update(download.toEntity()) }

    override suspend fun setProgress(
        chapterId: Uuid,
        downloadedPages: Int,
        bytes: Long
    ) = withContext(dispatcherProvider.io) {
        chapterDownloadDao.setProgress(chapterId, downloadedPages, bytes)
    }

    override suspend fun markDownloaded(
        chapterId: Uuid,
        pageCount: Int,
        bytes: Long,
        completedAt: Instant
    ) = withContext(dispatcherProvider.io) {
        chapterDownloadDao.markDownloaded(
            chapterId = chapterId,
            state = DownloadState.Downloaded,
            pageCount = pageCount,
            bytes = bytes,
            completedAt = completedAt,
        )
    }

    override suspend fun markFailed(
        chapterId: Uuid,
        error: DownloadError
    ) = withContext(dispatcherProvider.io) {
        chapterDownloadDao.markFailed(chapterId, DownloadState.Failed, error)
    }

    override suspend fun requeueInterrupted(): Int =
        withContext(dispatcherProvider.io) {
            chapterDownloadDao.moveState(from = DownloadState.Downloading, to = DownloadState.Queued)
        }

    override suspend fun delete(chapterIds: List<Uuid>) {
        if (chapterIds.isEmpty()) return
        withContext(dispatcherProvider.io) { chapterDownloadDao.delete(chapterIds) }
    }

    override suspend fun deleteForManga(mangaId: Uuid) =
        withContext(dispatcherProvider.io) { chapterDownloadDao.deleteForManga(mangaId) }

    override suspend fun deleteAll() = withContext(dispatcherProvider.io) { chapterDownloadDao.deleteAll() }

    override suspend fun getAll(): List<ChapterDownload> =
        withContext(dispatcherProvider.io) { chapterDownloadDao.getAll().map(ChapterDownloadEntity::toDomain) }

    private companion object {
        /**
         * The states the queue screen shows. `Downloaded` is the one left out: it is no longer queue,
         * it is library — the chapter list is where it belongs, and a queue that never empties would
         * make "is anything still downloading?" unanswerable at a glance.
         */
        val QUEUE_STATES = listOf(DownloadState.Queued, DownloadState.Downloading, DownloadState.Failed)
    }
}
