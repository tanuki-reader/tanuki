package app.tanuki.core.domain.manga.usecase

import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.common.service.uuidV7
import app.tanuki.core.domain.library.model.LibraryManga
import app.tanuki.core.domain.manga.model.Manga
import app.tanuki.core.domain.manga.model.MangaCover
import app.tanuki.core.domain.manga.model.PublishStatus
import app.tanuki.core.domain.manga.model.ReadingMode
import app.tanuki.core.domain.manga.model.ReadingStatus
import app.tanuki.core.domain.manga.repository.MangaRepository
import app.tanuki.core.domain.update.model.LibraryUpdateTarget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Tests [SetReadingMode] — the per-title reading-mode override. It is a thin delegation to
 * [MangaRepository.setReadingMode], so what matters is that the chosen [Uuid]/[ReadingMode] reach the
 * repository unchanged (and, unlike a status change, that nothing tries to add the manga to the library).
 */
class SetReadingModeTest {

    @Test
    fun `delegates the chosen mode to the repository`() = runTest {
        val repository = RecordingMangaRepository()
        val setReadingMode = SetReadingMode.Default(repository)
        val mangaId = uuidV7()

        setReadingMode(mangaId, ReadingMode.WEBTOON)

        assertEquals(mangaId to ReadingMode.WEBTOON, repository.readingModeWrite)
    }
}

/** In-memory [MangaRepository] that records the single [setReadingMode] call; other calls are unused. */
private class RecordingMangaRepository : MangaRepository {
    var readingModeWrite: Pair<Uuid, ReadingMode>? = null

    override suspend fun setReadingMode(mangaId: Uuid, mode: ReadingMode) {
        readingModeWrite = mangaId to mode
    }

    override fun observeManga(mangaId: Uuid): Flow<Manga?> = flowOf(null)

    override suspend fun getMangaByIds(mangaIds: Collection<Uuid>): List<Manga> = error("unused")
    override fun observeMangaByIds(mangaIds: Collection<Uuid>): Flow<List<Manga>> = error("unused")
    override fun observeLibraryKeys(sourceId: Uuid): Flow<Set<String>> = flowOf(emptySet())

    override fun observeLibrary(): Flow<List<LibraryManga>> = flowOf(emptyList())
    override suspend fun getLibraryUpdateTargets(): List<LibraryUpdateTarget> = error("unused")

    override suspend fun getMangaIdsBySource(sourceId: Uuid): List<Uuid> = emptyList()
    override suspend fun getMangaIdBySourceAndKey(sourceId: Uuid, sourceMangaKey: String): Uuid? = error("unused")
    override suspend fun upsertManga(manga: Manga) = error("unused")

    override suspend fun setSourceDetails(
        mangaId: Uuid,
        title: Text,
        author: Text?,
        description: Text?,
        publishStatus: PublishStatus,
        cover: MangaCover
    ) = error("unused")

    override suspend fun setCoverHeaders(mangaId: Uuid, headers: Map<String, String>) = error("unused")

    override suspend fun recordChapterSync(
        mangaId: Uuid,
        at: Instant,
        nextUpdateAt: Instant,
        fetchInterval: Int
    ) = error("unused")

    override suspend fun markDetailsFetched(mangaId: Uuid, at: Instant) = error("unused")

    override suspend fun setFilteredScanlators(mangaId: Uuid, scanlators: Set<String>) = error("unused")
    override suspend fun setReadingStatus(mangaId: Uuid, status: ReadingStatus) = error("unused")
    override suspend fun deleteManga(mangaId: Uuid) = error("unused")
    override suspend fun addToLibrary(mangaId: Uuid) = error("unused")
    override suspend fun removeFromLibrary(mangaId: Uuid) = error("unused")
}