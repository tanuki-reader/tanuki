package app.tanuki.core.domain.download.usecase

import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.common.service.info
import app.tanuki.core.domain.download.model.ChapterDownload
import app.tanuki.core.domain.download.repository.DownloadRepository
import app.tanuki.core.domain.download.service.ChapterDownloadStore
import app.tanuki.core.domain.download.service.StoredChapter
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

/**
 * Use case: reconcile the download records against what is actually on disk. Run once at launch.
 *
 * The database is an **index over the files**, and the two drift: the user can clear app storage from
 * the OS, a write can die mid-chapter, a sync can prune a chapter (the foreign key takes its record and
 * cannot reach a single byte), and a cancel can race a page write and leave a part behind. Nothing else
 * in the app ever notices any of that — every read path trusts one side or the other — so without this
 * pass a device accumulates bytes nothing can identify and badges over chapters that are not there.
 *
 * ### The four rules
 *
 * | Found | Done | Why |
 * |---|---|---|
 * | A `Downloaded` record with no committed directory | delete the **record** | Its bytes are gone. Kept, it makes the library's downloaded badge and the offline read path both lie. |
 * | A committed directory with no record | delete the **directory** | The pruned chapter and the removed series. This is the case the sweep exists for. |
 * | A `.part` directory with no record at all | delete it | An abandoned run, or a cancel that raced a page write. |
 * | Anything else | leave it | See below. |
 *
 * ### Why "anything else" is left alone, and why that makes this safe beside a running engine
 *
 * A record that is [app.tanuki.core.domain.download.model.DownloadState.Queued],
 * [app.tanuki.core.domain.download.model.DownloadState.Downloading] or
 * [app.tanuki.core.domain.download.model.DownloadState.Failed] is *work*, not an inconsistency: having
 * no files yet is the normal state of a queued chapter, and a `.part` beside one is precisely the resume
 * state a retry needs. So a part is discarded **only when there is no record for it whatsoever** — and
 * the engine always writes the record before it opens the part
 * ([DownloadManager] enqueues, then `openPart`). That ordering is what lets this run concurrently with a
 * live queue without a lock: the sweep cannot see a part the engine is about to fill, because by then
 * its record already exists.
 *
 * Cheap by construction: two listings ([DownloadRepository.getAll] and
 * [ChapterDownloadStore.storedChapters], a directory walk that never opens a manifest), then set
 * arithmetic. Deleting through the store emits on [ChapterDownloadStore.observeChanges], so the storage
 * figure in settings re-measures itself.
 */
sealed interface VerifyDownloads {
    /** @return What the sweep reclaimed; all zeros on a consistent device, which is the normal case. */
    suspend operator fun invoke(): VerifyDownloadsResult

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val downloadRepository: DownloadRepository,
        private val store: ChapterDownloadStore,
        private val logger: Logger,
    ) : VerifyDownloads {
        override suspend fun invoke(): VerifyDownloadsResult {
            val records = downloadRepository.getAll()
            val stored = store.storedChapters()
            if (records.isEmpty() && stored.isEmpty()) return VerifyDownloadsResult()

            val recordsByChapter = records.associateBy(ChapterDownload::chapterId)
            val completeOnDisk = stored.filter(StoredChapter::isComplete).mapTo(mutableSetOf(), StoredChapter::chapterId)

            // Records whose files are gone. Only completed ones: a queued chapter having nothing on disk
            // is what a queued chapter *is*.
            val stale =
                records
                    .filter { it.isComplete && it.chapterId !in completeOnDisk }
                    .map(ChapterDownload::chapterId)
            if (stale.isNotEmpty()) downloadRepository.delete(stale)

            // Directories nothing claims. A part is only debris when there is no record at all — see the
            // class docs for why that is also what makes this safe next to a running engine.
            val orphans = stored.filter { recordsByChapter[it.chapterId] == null }
            orphans.forEach { store.delete(it.mangaId, it.chapterId) }

            val (orphanParts, orphanDirectories) = orphans.partition { !it.isComplete }
            return VerifyDownloadsResult(
                staleRecords = stale.size,
                orphanDirectories = orphanDirectories.size,
                discardedParts = orphanParts.size,
            ).also { result ->
                // Silent when there was nothing to do, which is every launch on a healthy device — a log
                // line per start would bury the ones that mean something.
                if (result.reclaimedAnything) logger.info(TAG, "Reconciled downloads: $result")
            }
        }

        private companion object {
            const val TAG = "VerifyDownloads"
        }
    }
}

/**
 * What one reconciliation pass changed.
 *
 * @property staleRecords Records deleted because their files were gone.
 * @property orphanDirectories Committed chapter directories deleted because no record claimed them.
 * @property discardedParts Leftover `.part` directories deleted for the same reason.
 */
data class VerifyDownloadsResult(
    val staleRecords: Int = 0,
    val orphanDirectories: Int = 0,
    val discardedParts: Int = 0,
) {
    /** Whether the device was actually inconsistent — false on a healthy launch. */
    val reclaimedAnything: Boolean get() = staleRecords > 0 || orphanDirectories > 0 || discardedParts > 0
}
