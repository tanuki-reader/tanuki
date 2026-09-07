package app.tanuki.core.data.repository.manga

import app.tanuki.core.data.repository.library.LibraryEntryEntity
import app.tanuki.core.data.repository.readInBatches
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.manga.model.Manga
import app.tanuki.core.domain.manga.model.MangaCover
import app.tanuki.core.domain.manga.model.PublishStatus
import app.tanuki.core.domain.manga.model.ReadingMode
import app.tanuki.core.domain.manga.model.ReadingStatus
import app.tanuki.core.domain.manga.repository.MangaRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Room-backed [MangaRepository]. Maps between [Manga] domain models and [MangaEntity] rows (rebuilding
 * the flattened [app.tanuki.core.domain.manga.model.MangaCover] and the derived `lastRead`/library
 * fields). [upsertManga] also reconciles the manga's [app.tanuki.core.data.repository.library.LibraryEntryEntity]
 * from its favorite state, delegating to [MangaDao].
 *
 * **Every observed read is deduped before it is mapped.** The three read queries all derive
 * `last_read`, `unread_count` and `downloaded_count` by joining or sub-querying `history`, `chapters`,
 * `reading_progress` and `chapter_downloads`, so Room invalidates them on a write to any of six
 * tables — and most of those writes cannot change the result. `ChapterDownloadDao.setProgress` is the
 * clearest case: it fires once per downloaded *page* and only touches `downloaded_pages`/`bytes`,
 * while `downloaded_count` counts committed chapters, so a chapter download re-runs the library query
 * once per page to produce a byte-identical list every time.
 *
 * `distinctUntilChanged` sits **before** the mapper rather than after it so a duplicate costs only the
 * comparison: the per-row [Manga]/[app.tanuki.core.domain.common.model.Text]/`MangaCover` allocation,
 * the filter/search/sort passes in `ObserveLibrary`, and the recomposition behind them are all skipped.
 * The entities are `data class`es of scalars, so the generated `equals` is structural, and the compare
 * runs inside [DispatcherProvider.io] like the query itself. Don't remove it as redundant — Room's
 * invalidation is per-table, not per-result.
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
            .distinctUntilChanged()
            .map(MangaWithDetails?::toDomain)
            .flowOn(dispatcherProvider.io)

    override suspend fun getMangaByIds(mangaIds: Collection<Uuid>): List<Manga> =
        withContext(dispatcherProvider.io) {
            mangaIds.readInBatches(mangaDao::getMangaByIds).toDomainList()
        }

    override fun observeLibrary(): Flow<List<Manga>> =
        mangaDao
            .observeLibrary()
            .distinctUntilChanged()
            .map(List<MangaWithDetails>::toDomainList)
            .flowOn(dispatcherProvider.io)

    override fun observeMangaBySource(sourceId: Uuid): Flow<List<Manga>> =
        mangaDao
            .observeMangaBySource(sourceId)
            .distinctUntilChanged()
            .map(List<MangaWithDetails>::toDomainList)
            .flowOn(dispatcherProvider.io)

    override suspend fun getMangaIdBySourceAndKey(sourceId: Uuid, sourceMangaKey: String): Uuid? =
        mangaDao.findMangaIdBySourceAndKey(sourceId, sourceMangaKey)

    override suspend fun upsertManga(manga: Manga) =
        mangaDao.upsertWithLibrary(manga.toEntity(), manga.toLibraryEntry())

    override suspend fun setFilteredScanlators(mangaId: Uuid, scanlators: Set<String>) =
        mangaDao.updateFilteredScanlators(mangaId, encodeScanlators(scanlators))

    override suspend fun setSourceDetails(
        mangaId: Uuid,
        title: Text,
        author: Text?,
        description: Text?,
        publishStatus: PublishStatus,
        cover: MangaCover
    ) = mangaDao.updateSourceDetails(
        mangaId = mangaId,
        title = title.value,
        author = author?.value,
        description = description?.value,
        publishStatus = publishStatus,
        coverUrl = cover.url?.value,
        // The header codec lives here with the other column codecs, so the domain never sees the
        // storage encoding.
        coverHeaders = encodeHeaders(cover.headers),
        coverLastModified = cover.lastModified
    )

    override suspend fun setCoverHeaders(mangaId: Uuid, headers: Map<String, String>) =
        mangaDao.updateCoverHeaders(mangaId, encodeHeaders(headers))

    override suspend fun markChaptersFetched(mangaId: Uuid, at: Instant) =
        mangaDao.markChaptersFetched(mangaId, at)

    override suspend fun markDetailsFetched(mangaId: Uuid, at: Instant) =
        mangaDao.markDetailsFetched(mangaId, at)

    override suspend fun setReadingStatus(mangaId: Uuid, status: ReadingStatus) =
        // `dateAddedIfAbsent` is only used when the manga isn't yet in the library (implicit add). An
        // existing membership keeps its original added date; only the status column is updated.
        mangaDao.setReadingStatus(mangaId, status, Clock.System.now())

    override suspend fun setReadingMode(mangaId: Uuid, mode: ReadingMode) =
        mangaDao.updateReadingMode(mangaId, mode)

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