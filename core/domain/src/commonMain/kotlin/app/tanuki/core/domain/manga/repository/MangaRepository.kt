package app.tanuki.core.domain.manga.repository

import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.library.model.LibraryManga
import app.tanuki.core.domain.manga.model.Manga
import app.tanuki.core.domain.manga.model.MangaCover
import app.tanuki.core.domain.manga.model.PublishStatus
import app.tanuki.core.domain.manga.model.ReadingMode
import app.tanuki.core.domain.manga.model.ReadingStatus
import app.tanuki.core.domain.update.model.LibraryUpdateTarget
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
     * [getMangaByIds] as a subscription — for a caller whose resolved list must stay *true*, not merely
     * have been true when it was read.
     *
     * Prefer [getMangaByIds]. Standing up a subscription to take a single emission costs more than the
     * one-shot it imitates, so this is for the case where the answer is on screen and has to correct
     * itself when the user renames, unfollows or re-filters one of these series from somewhere else.
     *
     * The id set is fixed for the life of the subscription — it is bound into the query. A caller whose
     * ids change resubscribes (`flatMapLatest`); this flow will never widen to cover an id it was not
     * given.
     *
     * @param mangaIds Local ids to resolve; duplicates and unknown ids are harmless.
     * @return A [Flow] of the manga that exist, in unspecified order — key it by [Manga.id] rather than
     *   position. Ids with no row are simply absent, so an emission may be shorter than [mangaIds].
     */
    fun observeMangaByIds(mangaIds: Collection<Uuid>): Flow<List<Manga>>

    /**
     * Observes the user's library, as [LibraryManga] rather than whole [Manga]s.
     *
     * The narrow row is the point, not an optimisation detail: this read stays live for as long as the
     * Library tab is warm and is invalidated by writes to six tables, so anything it carries is re-read
     * and re-allocated per title on each of them. [LibraryManga] holds what the grid draws and what
     * `ObserveLibrary` filters, searches and sorts by; a screen wanting more than that wants
     * [observeManga].
     *
     * @return A [Flow] of the library, re-emitting on any change that alters it.
     */
    fun observeLibrary(): Flow<List<LibraryManga>>

    /**
     * The library as an update run reads it — see [LibraryUpdateTarget].
     *
     * Read once rather than observed, and narrower still than [observeLibrary]: a run needs a schedule,
     * a source to group by and a title to log, so it pays for none of the grid's derived counts.
     *
     * @return Every series in the library, in unspecified order.
     */
    suspend fun getLibraryUpdateTargets(): List<LibraryUpdateTarget>

    /**
     * The local ids of every manga stored under a given source.
     *
     * @param sourceId Local id of the source.
     * @return That source's manga ids, in unspecified order.
     */
    suspend fun getMangaIdsBySource(sourceId: Uuid): List<Uuid>

    /**
     * The source-native keys of every library series from the source with local id [sourceId], live —
     * what a source's browse screens test membership against for their "In library" marker.
     */
    fun observeLibraryKeys(sourceId: Uuid): Flow<Set<String>>

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
     * @param cover The cover to store — its URL and the headers that URL has to be fetched with, both
     *   written as given.
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
     * Records that a chapter sync completed for a manga, along with the schedule it derived.
     *
     * [Manga.chaptersFetchedAt] is the stamp the staleness gate reads to decide whether re-opening a
     * series has to ask its source again; [Manga.nextUpdateAt] and [Manga.fetchInterval] are what a
     * library run reads to decide whether to consider the series at all (see
     * [app.tanuki.core.domain.manga.service.fetchSchedule]).
     *
     * One call rather than three, because they are written at the same instant by the same sync and
     * every write to `manga` re-runs the library and detail queries behind it. A targeted write like
     * [setSourceDetails], and for the same reason: it routinely lands while a details sync is in flight.
     *
     * @param mangaId Local id of the manga.
     * @param at When the sync completed.
     * @param nextUpdateAt When a library run should next consider this series.
     * @param fetchInterval Days between expected releases; negative when the user pinned it.
     */
    suspend fun recordChapterSync(
        mangaId: Uuid,
        at: Instant,
        nextUpdateAt: Instant,
        fetchInterval: Int
    )

    /**
     * The [recordChapterSync] counterpart for a details sync — stamps [Manga.detailsFetchedAt]. No
     * schedule to go with it: how often a synopsis changes says nothing about when chapters land.
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