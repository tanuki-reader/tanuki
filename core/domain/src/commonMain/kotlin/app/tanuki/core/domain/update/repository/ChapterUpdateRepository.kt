package app.tanuki.core.domain.update.repository

import app.tanuki.core.domain.update.model.ChapterUpdate
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

/**
 * Persistence for [ChapterUpdate] records — the local-only note that a chapter arrived after the user
 * was already following its series.
 *
 * Reads are reactive ([Flow]) or one-shot (`get*`); writes are `suspend`, following the same convention
 * as the other repositories. Records are keyed by chapter and cascade with it, so pruning a chapter (or
 * deleting its manga) clears the update without any bookkeeping here.
 *
 * @see ChapterUpdate
 */
interface ChapterUpdateRepository {
    /**
     * Observes every recorded update, most-recently-discovered first.
     *
     * Unfiltered on purpose: whether an update is still *pending* depends on the chapter's reading
     * progress and its series' scanlator filter, which are the domain's rules rather than the
     * database's — [app.tanuki.core.domain.update.usecase.ObserveNewChapters] applies them once, in one
     * place, using the same helpers the chapter list and reader use.
     *
     * @return A [Flow] of the records, re-emitting on any change.
     */
    fun observeUpdates(): Flow<List<ChapterUpdate>>

    /**
     * The updates the user has not been told about yet, oldest first — what an update run hands to the
     * notifier. Read once rather than observed: a notification is a one-shot effect, not a view.
     */
    suspend fun getUnnotified(): List<ChapterUpdate>

    /**
     * Records newly discovered chapters as updates. Existing records are left as they are, so a chapter
     * re-offered by a repeated sync keeps its original [ChapterUpdate.discoveredAt] and — more
     * importantly — its [ChapterUpdate.notified] flag, and can never be announced twice.
     *
     * @param updates The records to write; an empty list is a no-op.
     */
    suspend fun recordUpdates(updates: List<ChapterUpdate>)

    /**
     * Marks updates as announced, after the notification has actually been posted.
     *
     * @param chapterIds Chapters whose records should be flagged; unknown ids are ignored.
     */
    suspend fun markNotified(chapterIds: List<Uuid>)

    /**
     * Drops every update recorded for a series — what removing it from the library does, since an update
     * is only meaningful for a series the user follows. The chapters themselves are untouched.
     *
     * @param mangaId Local id of the series whose updates should be cleared.
     */
    suspend fun deleteForManga(mangaId: Uuid)

    /**
     * Drops every update recorded for specific chapters within a series.
     *
     * @param mangaId Local id of the series whose updates should be cleared.
     * @param chapterIds IDs of the chapters whose updates should be cleared.
     */
    suspend fun deleteChaptersForManga(
        mangaId: Uuid,
        chapterIds: List<Uuid>
    )
}