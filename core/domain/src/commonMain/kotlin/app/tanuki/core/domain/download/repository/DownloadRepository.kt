package app.tanuki.core.domain.download.repository

import app.tanuki.core.domain.download.model.ChapterDownload
import app.tanuki.core.domain.download.model.DownloadError
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Persistence for [ChapterDownload] records — the index over what is queued for, or already kept on,
 * the device.
 *
 * Reads are reactive ([Flow]) or one-shot (`get*`); writes are `suspend`, following the same convention
 * as the other repositories. Records are keyed by chapter and cascade with it, so a chapter pruned by a
 * sync (or a deleted series) takes its row without any bookkeeping here — the *files* are a separate
 * matter, and deleting those is always an explicit call to
 * [app.tanuki.core.domain.download.service.ChapterDownloadStore].
 *
 * @see ChapterDownload
 */
interface DownloadRepository {
    /**
     * Observes the queue as the user sees it, oldest-queued first — everything still waiting, the one
     * being fetched, and everything that stopped with an error.
     *
     * Deliberately **wider than [ChapterDownload.isPending]**, which covers only the work the engine
     * still owes. A failed chapter owes nothing and must not be picked up again on its own, but it is
     * exactly what the user opened this screen to deal with: dropping it here would make a chapter
     * disappear from the queue at the moment it needed a decision, leaving nothing anywhere in the app
     * to retry it from except the series it came from.
     *
     * The order is [ChapterDownload.queuedAt], which is also the order the engine works in — so the
     * screen reads top-to-bottom as "what happens next".
     */
    fun observeQueue(): Flow<List<ChapterDownload>>

    /**
     * Observes one series' records, keyed by chapter, for the chapter list's per-row state.
     *
     * A map rather than a list because the caller does one lookup per row: a chapter list runs to
     * thousands of rows, and a per-row flow (or a linear scan per row) is exactly the shape that makes
     * scrolling it expensive.
     */
    fun observeForManga(mangaId: Uuid): Flow<Map<Uuid, ChapterDownload>>

    /**
     * Observes how many chapters are fully downloaded per series, keyed by series. Feeds the library's
     * downloaded badge and its "is downloaded" filter; series with none are absent rather than zero.
     */
    fun observeDownloadedCounts(): Flow<Map<Uuid, Int>>

    /** The record for one chapter, or `null` if it was never queued. */
    suspend fun get(chapterId: Uuid): ChapterDownload?

    /**
     * The oldest chapter still waiting, or `null` when the queue is empty — one step of the engine's
     * loop. Read one at a time rather than as a batch so a record enqueued or cancelled mid-run is
     * picked up on the next turn instead of being missed by a snapshot.
     */
    suspend fun nextQueued(): ChapterDownload?

    /**
     * Enqueues chapters, **leaving any existing record untouched**.
     *
     * The invariant that makes "download" a safe button to press twice: re-queueing a chapter that is
     * already downloading must not restart it, and re-queueing one that is already downloaded must not
     * throw its files away. Callers that genuinely want a fresh attempt delete the record first.
     *
     * @param downloads The records to write; an empty list is a no-op.
     */
    suspend fun enqueue(downloads: List<ChapterDownload>)

    /** Writes a record's full state — what the engine calls on each state transition. */
    suspend fun update(download: ChapterDownload)

    /**
     * Records progress within a chapter: how many pages are on disk and how many bytes they take.
     *
     * Its own narrow write because it happens once *per page* and must stay cheap — a single-row,
     * two-column update rather than rewriting a record the engine would otherwise have to hold and
     * re-read. Ignores unknown chapters, which is what a page landing just after a cancel looks like.
     */
    suspend fun setProgress(
        chapterId: Uuid,
        downloadedPages: Int,
        bytes: Long
    )

    /** Marks a chapter downloaded, stamping its completion time. */
    suspend fun markDownloaded(
        chapterId: Uuid,
        pageCount: Int,
        bytes: Long,
        completedAt: Instant
    )

    /** Marks a chapter failed with a reason. Whatever it managed to fetch stays on disk for a retry. */
    suspend fun markFailed(
        chapterId: Uuid,
        error: DownloadError
    )

    /**
     * Returns every record left in [app.tanuki.core.domain.download.model.DownloadState.Downloading] to
     * [app.tanuki.core.domain.download.model.DownloadState.Queued].
     *
     * Run at startup. `Downloading` only ever describes a *running* engine, so any row still in it was
     * left there by a process that died — and without this they would sit in the queue screen looking
     * active forever while nothing fetched them.
     *
     * @return How many records were reset.
     */
    suspend fun requeueInterrupted(): Int

    /** Deletes records for specific chapters. Files are the caller's responsibility. */
    suspend fun delete(chapterIds: List<Uuid>)

    /** Deletes every record for a series. */
    suspend fun deleteForManga(mangaId: Uuid)

    /** Deletes every record. */
    suspend fun deleteAll()

    /** Every record, for the startup reconciliation sweep against what is actually on disk. */
    suspend fun getAll(): List<ChapterDownload>
}
