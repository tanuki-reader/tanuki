package app.tanuki.core.data.repository.history

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Room DAO for [HistoryEntity]. */
@Dao
interface HistoryDao {
    /** Reactive history read at or after [since], most-recent first. */
    @Query("SELECT * FROM history WHERE read_at >= :since ORDER BY read_at DESC")
    fun observeHistorySince(since: Instant): Flow<List<HistoryEntity>>

    /**
     * One row per chapter: opening an already-read chapter just refreshes its timestamp. Written as a
     * raw upsert (rather than `@Upsert`) so the conflict is resolved on the unique `chapter_id` and the
     * existing entry's [HistoryEntity.id] is preserved.
     */
    @Query(
        """
        INSERT INTO history(id, chapter_id, manga_id, read_at)
        VALUES (:id, :chapterId, :mangaId, :readAt)
        ON CONFLICT(chapter_id) DO UPDATE SET read_at = excluded.read_at
        """,
    )
    suspend fun upsertHistory(
        id: Uuid,
        chapterId: Uuid,
        mangaId: Uuid,
        readAt: Instant,
    )

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun deleteById(id: Uuid)

    @Query("DELETE FROM history WHERE manga_id = :mangaId")
    suspend fun deleteForManga(mangaId: Uuid)

    @Query("DELETE FROM history")
    suspend fun clear()

    /**
     * Retention sweep: delete history older than [before], **except** each series' most recent row.
     *
     * Two rules, and the second one is the load-bearing half. History is never pruned by anything else,
     * so without this the table grows for the life of the install — one row per distinct chapter ever
     * read — and the two all-time readers walk all of it on every chapter the reader opens.
     *
     * **Why a series' newest row is never deleted, whatever its age.** Two queries read the *whole*,
     * date-unfiltered history to answer "when did this series last get read":
     *
     * - [app.tanuki.core.data.repository.manga.MangaDao] derives `last_read` as `MAX(history.read_at)`
     *   in every one of its projections, including the library grid's — so a series whose rows all aged
     *   out would sort as if it had never been read.
     * - [observeLibraryHistory]'s subquery groups the same unfiltered table to build Home's
     *   continue-reading shortlist — so a series whose rows all aged out would vanish from Home.
     *
     * Keeping the max row makes both exact rather than approximately preserved, and costs at most one
     * row per series ever read.
     *
     * The rule is expressed as *older than this series' own newest* rather than by id, which keeps the
     * correlated subquery to a single `MAX` and avoids the bare-column rule [observeLibraryHistory]
     * depends on. A tie for a series' max keeps both rows; harmless. Both predicates are index-backed
     * (`index_history_read_at`, `index_history_manga_id`), so this needs no migration.
     *
     * @param before Rows at or after this instant are always kept. See
     *   `app.tanuki.core.domain.history.model.historyRetentionCutoff` for how it is derived.
     * @return How many rows were deleted — 0 on the overwhelmingly common launch, which is what makes
     *   running this every start free: Room invalidates from per-row triggers, so a DELETE that matches
     *   nothing re-runs no query.
     */
    @Query(
        """
        DELETE FROM history
        WHERE read_at < :before
          AND read_at < (
              SELECT MAX(h2.read_at) FROM history AS h2 WHERE h2.manga_id = history.manga_id
          )
        """,
    )
    suspend fun pruneHistoryBefore(before: Instant): Int

    /**
     * The Home screen's "continue reading" shortlist: the library series the user has read, one row per
     * **series** carrying its most recent read, most-recently-read first, capped at [limit].
     *
     * Everything the shortlist needs is resolved here rather than in the domain — the library filter,
     * the one-per-series collapse, the ordering, the cap, and the joins to the manga and the chapter
     * that was read. Doing any of it above SQL means loading the whole history, the whole library, and
     * every chapter of every library series to produce a dozen rows.
     *
     * Three things worth knowing before editing this query:
     *
     * - **`h.id` / `h.chapter_id` rely on SQLite's bare-column rule.** They are bare columns in a
     *   `GROUP BY` whose only aggregate is `MAX(read_at)`, so SQLite guarantees they come from the row
     *   that produced that max — which is what makes each series carry *the chapter it was last read
     *   at*. The aggregation is isolated in the subquery precisely to keep exactly one `min`/`max` in
     *   scope; add a second one and the guarantee is gone.
     * - **`h.read_at AS manga_last_read` is exact, not an approximation.** The subquery's `history` is
     *   unfiltered by date, so the group's max *is* the series' all-time last read — the same value
     *   [app.tanuki.core.data.repository.manga.MangaDao] derives with its own `MAX(history.read_at)`.
     * - **`library` is joined twice on purpose:** inside the subquery so membership filters *before* the
     *   grouping, and outside only to read `date_added` / `reading_status` for the projection.
     *
     * Neither outer join fans out — `chapters` and `reading_progress` both match at most one row by id
     * — so the result is exactly one row per group. The `unread_count` / `downloaded_count` subqueries
     * are the same ones `MangaDao` uses; they run over at most [limit] rows here.
     */
    @Query(
        """
        SELECT h.id AS id,
               h.chapter_id AS chapter_id,
               h.read_at AS read_at,
               manga.id AS manga_id,
               manga.source AS manga_source,
               manga.title AS manga_title,
               manga.author AS manga_author,
               manga.description AS manga_description,
               manga.cover_url AS manga_cover_url,
               manga.cover_headers AS manga_cover_headers,
               manga.source_manga_key AS manga_source_manga_key,
               manga.url AS manga_url,
               manga.reading_mode AS manga_reading_mode,
               manga.publish_status AS manga_publish_status,
               manga.filtered_scanlators AS manga_filtered_scanlators,
               h.read_at AS manga_last_read,
               library.date_added AS manga_date_added,
               library.reading_status AS manga_reading_status,
               (
                   SELECT COUNT(*)
                   FROM chapters
                   LEFT JOIN reading_progress ON reading_progress.chapter_id = chapters.id
                   WHERE chapters.manga_id = manga.id
                     AND COALESCE(reading_progress.already_read, 0) = 0
                     AND (
                         chapters.scanlator IS NULL
                         OR manga.filtered_scanlators IS NULL
                         OR instr(char(10) || manga.filtered_scanlators || char(10),
                                  char(10) || chapters.scanlator || char(10)) = 0
                     )
               ) AS manga_unread_count,
               (
                   SELECT COUNT(*)
                   FROM chapter_downloads
                   WHERE chapter_downloads.manga_id = manga.id
                     AND chapter_downloads.state = 'Downloaded'
               ) AS manga_downloaded_count,
               chapters.id AS ch_id,
               chapters.manga_id AS ch_manga_id,
               chapters.chapter_number AS ch_chapter_number,
               chapters.name AS ch_name,
               chapters.scanlator AS ch_scanlator,
               chapters.source_chapter_key AS ch_source_chapter_key,
               chapters.url AS ch_url,
               chapters.date_upload AS ch_date_upload,
               chapters.date_fetch AS ch_date_fetch,
               reading_progress.has_bookmark AS ch_has_bookmark,
               reading_progress.last_page_read AS ch_last_page_read,
               reading_progress.already_read AS ch_already_read
        FROM (
            SELECT history.id AS id,
                   history.chapter_id AS chapter_id,
                   history.manga_id AS manga_id,
                   MAX(history.read_at) AS read_at
            FROM history
            JOIN library ON library.id = history.manga_id
            GROUP BY history.manga_id
        ) AS h
        JOIN manga ON manga.id = h.manga_id
        JOIN library ON library.id = h.manga_id
        LEFT JOIN chapters ON chapters.id = h.chapter_id
        LEFT JOIN reading_progress ON reading_progress.chapter_id = h.chapter_id
        ORDER BY h.read_at DESC
        LIMIT :limit
        """,
    )
    fun observeLibraryHistory(limit: Int): Flow<List<LibraryHistoryEntry>>
}
