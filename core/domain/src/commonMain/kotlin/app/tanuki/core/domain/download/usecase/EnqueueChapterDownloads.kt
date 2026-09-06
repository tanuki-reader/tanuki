package app.tanuki.core.domain.download.usecase

import app.tanuki.core.domain.chapter.repository.ChapterRepository
import app.tanuki.core.domain.download.model.ChapterDownload
import app.tanuki.core.domain.download.model.DownloadState
import app.tanuki.core.domain.download.repository.DownloadRepository
import app.tanuki.core.domain.download.service.DownloadScheduler
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Use case: put chapters in the download queue — what every "download" affordance ends up calling.
 *
 * Safe to invoke twice on the same chapters, which is the property that lets the UI treat downloading as
 * a plain toggle rather than a state machine it has to guard:
 *
 *  - **Already downloaded** → left alone. Re-queueing would throw away files the user still has and
 *    re-fetch them for no reason.
 *  - **Queued or downloading** → left alone, keeping its place. Re-stamping `queuedAt` would send a
 *    chapter that is halfway through to the back of its own queue.
 *  - **Failed** → queued again, with the error cleared and the original stamp kept. This is the retry
 *    path, and keeping the stamp is what puts a chapter that failed at position three back at position
 *    three rather than behind everything queued since.
 *  - **Never queued** → enqueued now.
 *
 * Ids that don't resolve to a chapter are skipped: the series they belonged to may have been pruned by
 * a sync between the list being rendered and the button being pressed, and failing the whole batch for
 * one stale row would make bulk selection unusable.
 *
 * Enqueueing always ends in [DownloadScheduler.ensureRunning] — writing a row that nothing picks up is
 * the one outcome that looks, from the outside, exactly like the feature being broken.
 */
sealed interface EnqueueChapterDownloads {
    /**
     * @param chapterIds Local ids of the chapters to download.
     * @return How many chapters this call actually queued (newly enqueued plus retried); `0` when
     *   everything asked for was already queued or already downloaded.
     */
    suspend operator fun invoke(chapterIds: Collection<Uuid>): Int

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val downloadRepository: DownloadRepository,
        private val chapterRepository: ChapterRepository,
        private val scheduler: DownloadScheduler,
    ) : EnqueueChapterDownloads {
        override suspend fun invoke(chapterIds: Collection<Uuid>): Int {
            if (chapterIds.isEmpty()) return 0

            val now = Clock.System.now()
            val fresh = mutableListOf<ChapterDownload>()
            var retried = 0

            for (chapterId in chapterIds.distinct()) {
                val existing = downloadRepository.get(chapterId)
                when (existing?.state) {
                    null -> {
                        val chapter = chapterRepository.getChapter(chapterId) ?: continue
                        fresh +=
                            ChapterDownload(
                                chapterId = chapterId,
                                mangaId = chapter.mangaId,
                                state = DownloadState.Queued,
                                queuedAt = now,
                            )
                    }

                    DownloadState.Failed -> {
                        downloadRepository.update(
                            existing.copy(
                                state = DownloadState.Queued,
                                error = null,
                                completedAt = null,
                            ),
                        )
                        retried++
                    }

                    DownloadState.Queued, DownloadState.Downloading, DownloadState.Downloaded -> Unit
                }
            }

            downloadRepository.enqueue(fresh)
            val queued = fresh.size + retried
            if (queued > 0) scheduler.ensureRunning()
            return queued
        }
    }
}