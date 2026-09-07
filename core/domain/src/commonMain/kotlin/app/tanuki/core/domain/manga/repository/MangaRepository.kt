package app.tanuki.core.domain.manga.repository

import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.manga.model.Manga
import app.tanuki.core.domain.manga.model.MangaCover
import app.tanuki.core.domain.manga.model.PublishStatus
import app.tanuki.core.domain.manga.model.ReadingMode
import app.tanuki.core.domain.manga.model.ReadingStatus
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Persistence operations for [Manga].
 *
 * Reactive reads use [Flow]; one-shot reads and writes are `suspend`. Naming convention: `observe*`
 * returns a live [Flow], `get*` is a one-shot lookup, and `add`/`update`/`delete` mutate a single
 * manga. Implemented by the data layer and consumed by use cases via Koin.
 *
 * @see Manga
 */
interface MangaRepository {
    /**
     * Observes a single manga by id.
     *
     * @param mangaId Local id of the manga.
     * @return A [Flow] emitting the [Manga], or `null` while it does not exist.
     */
    fun observeManga(mangaId: Uuid): Flow<Manga?>

    /**
     * Reads many manga by id in one query, rather than observed — the batch counterpart of
     * [observeManga], for callers resolving a list of ids (history rows, download records, update
     * records) to the series they refer to.
     *
     * Prefer this over a per-id [observeManga] loop: each such subscription runs the full detail query
     * (two correlated subqueries over `chapters` / `reading_progress` / `chapter_downloads`) and is torn
     * down immediately, so resolving 300 series costs 300 queries where this costs one.
     *
     * @param mangaIds Local ids to resolve; duplicates and unknown ids are harmless.
     * @return The manga that exist, in unspecified order — key it by [Manga.id] rather than position.
     *   Ids with no row are simply absent, so the result may be shorter than [mangaIds].
     */
    suspend fun getMangaByIds(mangaIds: Collection<Uuid>): List<Manga>

    /**
     * Observes the user's library.
     *
     * @return A [Flow] of the library, re-emitting on any change.
     */
    fun observeLibrary(): Flow<List<Manga>>

    /**
     * Observes all locally stored manga belonging to a given source.
     *
     * @param sourceId Local id of the source.
     * @return A [Flow] of that source's manga.
     */
    fun observeMangaBySource(sourceId: Uuid): Flow<List<Manga>>

    /**
     * The local id of the manga keyed [sourceMangaKey] within source [sourceId], or `null` if it isn't
     * cached.
     *
     * @param sourceId Local id of the source.
     * @param sourceMangaKey The manga's source-native id (its stable key within the source).
     */
    suspend fun getMangaIdBySourceAndKey(
        sourceId: Uuid,
        sourceMangaKey: String
    ): Uuid?

    /**
     * Inserts or updates a new manga.
     *
     * @param manga The manga to persist.
     */
    suspend fun upsertManga(manga: Manga)

    /**
     * Replaces the set of scanlation groups hidden from a manga's chapter list.
     *
     * A targeted column write rather than a whole-row [upsertManga]: a chapter/detail sync may be
     * writing the same row concurrently, and a full upsert built from a stale snapshot would undo it.
     *
     * @param mangaId Local id of the manga.
     * @param scanlators Group names to hide; empty clears the filter.
     */
    suspend fun setFilteredScanlators(
        mangaId: Uuid,
        scanlators: Set<String>
    )

    /**
     * Rewrites exactly the fields a details sync owns — title, author, description, publish status and
     * cover — leaving local identity, library membership and every user preference untouched.
     *
     * A targeted write rather than a whole-row [upsertManga] of the [Manga] the sync started from. That
     * object is read before the source call and written back after it, so anything that touched the row
     * in the meantime is reverted: a reading mode the user just picked, or the chapter-sync stamp from
     * [markChaptersFetched]. The detail screen runs both syncs at once, so that overlap is routine.
     *
     * @param mangaId Local id of the manga.
     * @param title Title to store; a details sync passes the existing one through when the source
     *   returned nothing, so this is never blank.
     * @param author Author credit, or `null` for none.
     * @param description Synopsis, or `null` for none.
     * @param publishStatus Publication status as the source now reports it.
     * @param cover The cover to store. Its `lastModified` is written as given — the caller decides
     *   whether the art actually changed, since that value is the image loader's cache key.
     */
    suspend fun setSourceDetails(
        mangaId: Uuid,
        title: Text,
        author: Text?,
        description: Text?,
        publishStatus: PublishStatus,
        cover: MangaCover
    )

    /**
     * Replaces just the request headers a manga's cover must be fetched with, leaving its URL and
     * cache-key timestamp alone. Targeted for the same reason as [setSourceDetails]; used by the
     * browse-time refresh that adopts headers a source gained after the row was cached.
     *
     * @param mangaId Local id of the manga.
     * @param headers Header name/value pairs; empty clears them.
     */
    suspend fun setCoverHeaders(
        mangaId: Uuid,
        headers: Map<String, String>
    )

    /**
     * Records that a chapter sync completed for a manga.
     *
     * This is the [Manga.chaptersFetchedAt] stamp the staleness gate reads to decide whether re-opening
     * a series has to ask its source again. A targeted write like [setSourceDetails], and for the same
     * reason: the stamp routinely lands while a details sync is in flight.
     *
     * @param mangaId Local id of the manga.
     * @param at When the sync completed.
     */
    suspend fun markChaptersFetched(
        mangaId: Uuid,
        at: Instant
    )

    /**
     * The [markChaptersFetched] counterpart for a details sync — stamps [Manga.detailsFetchedAt].
     *
     * @param mangaId Local id of the manga.
     * @param at When the sync completed.
     */
    suspend fun markDetailsFetched(
        mangaId: Uuid,
        at: Instant
    )

    /**
     * Sets the user's reading status for a manga, adding it to the library first when it isn't a member
     * (the status lives on the `library` row). A targeted write like [setFilteredScanlators], not a
     * whole-row upsert: a concurrent detail sync must not be clobbered. Adding preserves any existing
     * `date_added`; only the status column changes for a manga already in the library.
     *
     * @param mangaId Local id of the manga.
     * @param status The reading status to record.
     */
    suspend fun setReadingStatus(
        mangaId: Uuid,
        status: ReadingStatus
    )

    /**
     * Sets how a manga's pages are laid out when reading. A targeted write to the `manga` row's
     * reading-mode column (like [setFilteredScanlators]), so a concurrent detail sync can't clobber it.
     * Unlike [setReadingStatus] this is a source-owned column that exists whether or not the manga is in
     * the library, so it never adds a membership.
     *
     * @param mangaId Local id of the manga.
     * @param mode The reading mode to record.
     */
    suspend fun setReadingMode(
        mangaId: Uuid,
        mode: ReadingMode
    )

    /**
     * Deletes a manga by its local id.
     *
     * @param mangaId Local id of the manga to remove.
     */
    suspend fun deleteManga(mangaId: Uuid)

    /**
     * Adds a manga to the user's library — records its membership (with the current time as the added
     * date) while leaving the cached manga row untouched. A no-op if it is already in the library, so
     * the original added date is preserved.
     *
     * @param mangaId Local id of the manga to add to the library.
     */
    suspend fun addToLibrary(mangaId: Uuid)

    /**
     * Removes a manga from the user's library: deletes its `library` membership and all of its category
     * memberships, while keeping the cached manga (and its chapters) so it can still be browsed and
     * read. A no-op if the manga is not in the library. Contrast with [deleteManga], which purges the
     * manga row entirely.
     *
     * @param mangaId Local id of the manga to remove from the library.
     */
    suspend fun removeFromLibrary(mangaId: Uuid)
}