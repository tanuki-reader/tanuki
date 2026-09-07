package app.tanuki.core.data.repository.manga

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.tanuki.core.data.repository.library.LibraryEntryEntity
import app.tanuki.core.domain.manga.model.PublishStatus
import app.tanuki.core.domain.manga.model.ReadingMode
import app.tanuki.core.domain.manga.model.ReadingStatus
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Room DAO for [MangaEntity] and its 1:1 [LibraryEntryEntity]. Reads LEFT JOIN `library` and
 * `history` so a single [MangaWithDetails] projection carries membership (`date_added` /
 * `reading_status`) and the derived `last_read`; `GROUP BY manga.id` collapses the per-chapter
 * history rows. `unread_count` is a correlated subquery over `chapters` / `reading_progress` (a
 * chapter counts as unread when it has no progress row or `already_read = 0`), kept out of the
 * `GROUP BY` so it never fans out the history join. It also excludes chapters whose `scanlator` is in
 * the manga's newline-encoded `filtered_scanlators` (matched delimited by `char(10)` so a group name is
 * never a partial match of another), so the badge counts only the chapters the detail screen shows —
 * the same filter the chapter list and the reader apply. A chapter with a `NULL` scanlator is never
 * hidden.
 */
@Dao
interface MangaDao {
    @Query(
        """
        SELECT manga.*, MAX(history.read_at) AS last_read, library.date_added, library.reading_status,
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
               ) AS unread_count,
               (
                   SELECT COUNT(*)
                   FROM chapter_downloads
                   WHERE chapter_downloads.manga_id = manga.id
                     AND chapter_downloads.state = 'Downloaded'
               ) AS downloaded_count
        FROM manga
        LEFT JOIN history ON history.manga_id = manga.id
        LEFT JOIN library ON library.id = manga.id
        WHERE manga.id = :mangaId
        GROUP BY manga.id
        """,
    )
    fun observeManga(mangaId: Uuid): Flow<MangaWithDetails?>

    /**
     * The same projection as [observeManga] for many ids at once, read once rather than observed — what
     * a caller resolving a list of ids (history rows, download records, update records) uses instead of
     * subscribing to [observeManga] per id. Each of those subscriptions runs this query's two correlated
     * subqueries and is torn down immediately, so a per-id loop costs one full query per row.
     *
     * Ids with no row are simply absent from the result, and the order is unspecified — callers key the
     * result by [MangaWithDetails.manga]'s id rather than relying on position.
     */
    @Query(
        """
        SELECT manga.*, MAX(history.read_at) AS last_read, library.date_added, library.reading_status,
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
               ) AS unread_count,
               (
                   SELECT COUNT(*)
                   FROM chapter_downloads
                   WHERE chapter_downloads.manga_id = manga.id
                     AND chapter_downloads.state = 'Downloaded'
               ) AS downloaded_count
        FROM manga
        LEFT JOIN history ON history.manga_id = manga.id
        LEFT JOIN library ON library.id = manga.id
        WHERE manga.id IN (:mangaIds)
        GROUP BY manga.id
        """,
    )
    suspend fun getMangaByIds(mangaIds: Collection<Uuid>): List<MangaWithDetails>

    /** The user's library: every manga with a `library` row, titled for stable display ordering. */
    @Query(
        """
        SELECT manga.*, MAX(history.read_at) AS last_read, library.date_added, library.reading_status,
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
               ) AS unread_count,
               (
                   SELECT COUNT(*)
                   FROM chapter_downloads
                   WHERE chapter_downloads.manga_id = manga.id
                     AND chapter_downloads.state = 'Downloaded'
               ) AS downloaded_count
        FROM library
        JOIN manga ON manga.id = library.id
        LEFT JOIN history ON history.manga_id = manga.id
        GROUP BY manga.id
        ORDER BY manga.title
        """,
    )
    fun observeLibrary(): Flow<List<MangaWithDetails>>

    /** All manga belonging to the source identified by its local [Uuid]. */
    @Query(
        """
        SELECT manga.*, MAX(history.read_at) AS last_read, library.date_added, library.reading_status,
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
               ) AS unread_count,
               (
                   SELECT COUNT(*)
                   FROM chapter_downloads
                   WHERE chapter_downloads.manga_id = manga.id
                     AND chapter_downloads.state = 'Downloaded'
               ) AS downloaded_count
        FROM manga
        LEFT JOIN history ON history.manga_id = manga.id
        LEFT JOIN library ON library.id = manga.id
        WHERE manga.source = :sourceId
        GROUP BY manga.id
        ORDER BY manga.title
        """,
    )
    fun observeMangaBySource(sourceId: Uuid): Flow<List<MangaWithDetails>>

    /**
     * The local id of the manga keyed [sourceMangaKey] within source [sourceId], or `null` if none is
     * cached yet. Used to dedupe when caching a browsed manga — a second tap reuses the existing row
     * (and its library membership) instead of minting a duplicate. Keyed on the source-native id, not
     * the permalink, so url-less series each get their own row.
     */
    @Query("SELECT id FROM manga WHERE source = :sourceId AND source_manga_key = :sourceMangaKey LIMIT 1")
    suspend fun findMangaIdBySourceAndKey(
        sourceId: Uuid,
        sourceMangaKey: String,
    ): Uuid?

    @Upsert
    suspend fun upsert(manga: MangaEntity)

    @Upsert
    suspend fun upsertLibraryEntry(entry: LibraryEntryEntity)

    @Query("DELETE FROM library WHERE id = :mangaId")
    suspend fun deleteLibraryEntry(mangaId: Uuid)

    /** Drops every category membership of a manga (rows in the `library`↔`category` join table). */
    @Query("DELETE FROM manga_categories WHERE manga_id = :mangaId")
    suspend fun clearCategories(mangaId: Uuid)

    /**
     * Writes only the hidden-scanlator column, leaving the rest of the row alone — a detail sync may be
     * rewriting the same manga, and a whole-row upsert built from the screen's snapshot would undo it.
     */
    @Query("UPDATE manga SET filtered_scanlators = :encoded WHERE id = :mangaId")
    suspend fun updateFilteredScanlators(
        mangaId: Uuid,
        encoded: String?,
    )

    /**
     * Writes only the reading-mode column of a manga's row, leaving the rest alone — like
     * [updateFilteredScanlators], a whole-row upsert from a stale detail-sync snapshot would otherwise
     * undo the user's choice.
     */
    @Query("UPDATE manga SET reading_mode = :mode WHERE id = :mangaId")
    suspend fun updateReadingMode(
        mangaId: Uuid,
        mode: ReadingMode,
    )

    /**
     * Rewrites exactly the columns a details sync owns, leaving the rest of the row alone.
     *
     * A targeted write rather than a whole-row upsert of the domain object the sync started from: that
     * snapshot is read before the network call and written back after it, so anything else that touched
     * the row in between — the user picking a reading mode, a concurrent chapter sync stamping
     * [markChaptersFetched] — would be silently reverted. The detail screen fires the two syncs at the
     * same time, so that window is the common case, not an edge one.
     */
    @Query(
        """
        UPDATE manga
        SET title = :title,
            author = :author,
            description = :description,
            publish_status = :publishStatus,
            cover_url = :coverUrl,
            cover_headers = :coverHeaders,
            cover_last_modified = :coverLastModified
        WHERE id = :mangaId
        """,
    )
    suspend fun updateSourceDetails(
        mangaId: Uuid,
        title: String,
        author: String?,
        description: String?,
        publishStatus: PublishStatus,
        coverUrl: String?,
        coverHeaders: String?,
        coverLastModified: Instant,
    )

    /**
     * Writes only the cover's request headers, for the browse-time refresh that adopts headers a source
     * gained after a row was cached. Targeted for the same reason as [updateSourceDetails]; deliberately
     * leaves `cover_last_modified` alone, since the art has not changed, only how it must be requested.
     */
    @Query("UPDATE manga SET cover_headers = :encoded WHERE id = :mangaId")
    suspend fun updateCoverHeaders(
        mangaId: Uuid,
        encoded: String?,
    )

    /**
     * Records that a chapter sync completed for a manga, which is what the staleness gate in
     * `SyncSourceChapters` reads to decide whether re-opening a series needs to ask its source again.
     *
     * Targeted, like every other write in this block: the stamp routinely lands while a details sync is
     * in flight, and a whole-row upsert from that sync's snapshot would erase it.
     */
    @Query("UPDATE manga SET chapters_fetched_at = :at WHERE id = :mangaId")
    suspend fun markChaptersFetched(
        mangaId: Uuid,
        at: Instant,
    )

    /** The [markChaptersFetched] counterpart for a details sync. */
    @Query("UPDATE manga SET details_fetched_at = :at WHERE id = :mangaId")
    suspend fun markDetailsFetched(
        mangaId: Uuid,
        at: Instant,
    )

    /** Writes only the reading-status column of an existing `library` row; a no-op if there is none. */
    @Query("UPDATE library SET reading_status = :status WHERE id = :mangaId")
    suspend fun updateReadingStatus(
        mangaId: Uuid,
        status: ReadingStatus,
    )

    /**
     * Sets a manga's reading status, adding it to the library first when it isn't a member — the status
     * lives on the `library` row, so there must be one to write. Runs in one transaction: insert the
     * membership only if absent ([dateAddedIfAbsent] with the chosen status, so a fresh add lands with it),
     * then update the column (the case where the row already existed, preserving its `date_added`).
     */
    @Transaction
    suspend fun setReadingStatus(
        mangaId: Uuid,
        status: ReadingStatus,
        dateAddedIfAbsent: Instant,
    ) {
        insertLibraryEntryIfAbsent(LibraryEntryEntity(mangaId, dateAddedIfAbsent, status))
        updateReadingStatus(mangaId, status)
    }

    @Query("DELETE FROM manga WHERE id = :mangaId")
    suspend fun deleteById(mangaId: Uuid)

    /**
     * Adds a manga to the library without touching the cached manga row — inserts its `library`
     * membership, or leaves an existing one untouched (so a re-add never resets `date_added`).
     */
    suspend fun addToLibrary(entry: LibraryEntryEntity) = insertLibraryEntryIfAbsent(entry)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLibraryEntryIfAbsent(entry: LibraryEntryEntity)

    /**
     * Removes a manga from the library without deleting the cached manga itself: clears its category
     * memberships, then deletes the `library` row (the membership marker). Runs in one transaction so a
     * title can never be left half-removed. The `manga`/`chapters` rows stay, so it can still be
     * browsed and read — unlike [deleteById], which purges the manga entirely.
     */
    @Transaction
    suspend fun removeFromLibrary(mangaId: Uuid) {
        clearCategories(mangaId)
        deleteLibraryEntry(mangaId)
    }

    /**
     * Upserts a manga and reconciles its library membership in one transaction: [libraryEntry] is
     * removed when `null` (not a favorite), otherwise upserted, committing with the manga.
     */
    @Transaction
    suspend fun upsertWithLibrary(
        manga: MangaEntity,
        libraryEntry: LibraryEntryEntity?,
    ) {
        upsert(manga)
        when (libraryEntry) {
            null -> deleteLibraryEntry(manga.id)
            else -> upsertLibraryEntry(libraryEntry)
        }
    }
}