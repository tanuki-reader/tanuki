package app.tanuki.core.data.repository.manga

import app.tanuki.core.data.repository.library.LibraryEntryEntity
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.manga.model.Manga
import app.tanuki.core.domain.manga.model.ReadingStatus
import app.tanuki.core.domain.manga.repository.MangaRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Room-backed [MangaRepository]. Maps between [Manga] domain models and [MangaEntity] rows (rebuilding
 * the flattened [app.tanuki.core.domain.manga.model.MangaCover] and the derived `lastRead`/library
 * fields). [upsertManga] also reconciles the manga's [app.tanuki.core.data.repository.library.LibraryEntryEntity]
 * from its favorite state, delegating to [MangaDao].
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class MangaRepositoryImp(
    private val mangaDao: MangaDao,
    private val dispatcherProvider: DispatcherProvider
) : MangaRepository {
    override fun observeManga(mangaId: Uuid): Flow<Manga?> =
        mangaDao
            .observeManga(mangaId)
            .map(MangaWithDetails?::toDomain)
            .flowOn(dispatcherProvider.io)

    override fun observeLibrary(): Flow<List<Manga>> =
        mangaDao
            .observeLibrary()
            .map(List<MangaWithDetails>::toDomainList)
            .flowOn(dispatcherProvider.io)

    override fun observeMangaBySource(sourceId: Uuid): Flow<List<Manga>> =
        mangaDao
            .observeMangaBySource(sourceId)
            .map(List<MangaWithDetails>::toDomainList)
            .flowOn(dispatcherProvider.io)

    override suspend fun getMangaIdBySourceAndKey(sourceId: Uuid, sourceMangaKey: String): Uuid? =
        mangaDao.findMangaIdBySourceAndKey(sourceId, sourceMangaKey)

    override suspend fun upsertManga(manga: Manga) =
        mangaDao.upsertWithLibrary(manga.toEntity(), manga.toLibraryEntry())

    override suspend fun setFilteredScanlators(mangaId: Uuid, scanlators: Set<String>) =
        mangaDao.updateFilteredScanlators(mangaId, encodeScanlators(scanlators))

    override suspend fun deleteManga(mangaId: Uuid) = mangaDao.deleteById(mangaId)

    override suspend fun addToLibrary(mangaId: Uuid) =
        mangaDao.addToLibrary(
            // A fresh membership: added now, with the default "plan to read" status (see toLibraryEntry).
            // Re-adding is ignored by the DAO, so an existing added date/status is never overwritten.
            LibraryEntryEntity(
                id = mangaId,
                dateAdded = Clock.System.now(),
                readingStatus = ReadingStatus.PLANNING_TO_READ
            )
        )

    override suspend fun removeFromLibrary(mangaId: Uuid) = mangaDao.removeFromLibrary(mangaId)
}

private fun List<MangaWithDetails>.toDomainList() = map(MangaWithDetails::toDomain)

private fun MangaWithDetails?.toDomain() = this?.toDomain()