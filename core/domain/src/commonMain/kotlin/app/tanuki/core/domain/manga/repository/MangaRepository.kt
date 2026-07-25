package app.tanuki.core.domain.manga.repository

import app.tanuki.core.domain.manga.model.Manga
import kotlinx.coroutines.flow.Flow
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