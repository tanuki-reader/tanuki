package app.tanuki.core.data.repository.manga

import app.tanuki.core.data.repository.library.LibraryEntryEntity
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.manga.model.Manga
import app.tanuki.core.domain.manga.model.PublishStatus
import app.tanuki.core.domain.manga.model.ReadingMode
import app.tanuki.core.domain.manga.model.ReadingStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Room invalidates the three observed manga reads on a write to any of the six tables they join or
 * sub-query, so most of what reaches them is a re-run producing the row-for-row identical result —
 * `ChapterDownloadDao.setProgress` alone fires once per downloaded page. These pin that such a re-run
 * stops at the repository instead of being mapped into fresh domain objects and pushed at the screens.
 *
 * The fake DAO emits from a [MutableSharedFlow] rather than a `MutableStateFlow` on purpose: a state
 * flow conflates equal values itself, which would make these pass with the dedupe removed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MangaRepositoryImpTest {
    @Test
    fun `an identical library re-emission is not passed on`() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val row = mangaRow("Berserk")
            val dao = RecordingMangaDao()

            val emissions = mutableListOf<List<String>>()
            backgroundScope.launch(dispatcher) {
                repository(dao, dispatcher).observeLibrary().collect { emissions += it.map(::titleOf) }
            }
            advanceUntilIdle()

            dao.library.emit(listOf(row))
            advanceUntilIdle()
            assertEquals(listOf(listOf("Berserk")), emissions)

            // What a download in progress looks like from here: the query re-runs, the rows don't change.
            dao.library.emit(listOf(row.copy()))
            advanceUntilIdle()

            assertEquals(1, emissions.size, "an identical query result must not reach the domain layer")
        }

    @Test
    fun `a genuine library change is passed on`() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val row = mangaRow("Berserk")
            val dao = RecordingMangaDao()

            val emissions = mutableListOf<List<String>>()
            backgroundScope.launch(dispatcher) {
                repository(dao, dispatcher).observeLibrary().collect { emissions += it.map(::titleOf) }
            }
            advanceUntilIdle()

            dao.library.emit(listOf(row))
            advanceUntilIdle()

            // A chapter actually finishing its download is a real change to the derived count.
            dao.library.emit(listOf(row.copy(downloadedCount = row.downloadedCount + 1)))
            advanceUntilIdle()
            assertEquals(2, emissions.size)

            dao.library.emit(listOf(row, mangaRow("Akira")))
            advanceUntilIdle()
            assertEquals(listOf("Berserk", "Akira"), emissions.last())
        }

    @Test
    fun `an identical single-manga re-emission is not passed on`() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val row = mangaRow("Berserk")
            val dao = RecordingMangaDao()

            val emissions = mutableListOf<Manga?>()
            backgroundScope.launch(dispatcher) {
                repository(dao, dispatcher).observeManga(row.manga.id).collect { emissions += it }
            }
            advanceUntilIdle()

            dao.manga.emit(row)
            advanceUntilIdle()
            assertEquals(1, emissions.size)

            // The detail screen sits under the reader, which writes reading_progress on every page turn.
            dao.manga.emit(row.copy())
            advanceUntilIdle()
            assertEquals(1, emissions.size)

            dao.manga.emit(row.copy(unreadCount = row.unreadCount - 1))
            advanceUntilIdle()
            assertEquals(2, emissions.size)
        }

    @Test
    fun `an identical by-source re-emission is not passed on`() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val row = mangaRow("Berserk")
            val dao = RecordingMangaDao()

            val emissions = mutableListOf<List<String>>()
            backgroundScope.launch(dispatcher) {
                repository(dao, dispatcher)
                    .observeMangaBySource(row.manga.source)
                    .collect { emissions += it.map(::titleOf) }
            }
            advanceUntilIdle()

            dao.bySource.emit(listOf(row))
            dao.bySource.emit(listOf(row.copy()))
            advanceUntilIdle()

            assertEquals(1, emissions.size)
        }

    @Test
    fun `deduping does not resubscribe the query`() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val row = mangaRow("Berserk")
            val dao = RecordingMangaDao()

            backgroundScope.launch(dispatcher) {
                repository(dao, dispatcher).observeLibrary().collect { }
            }
            advanceUntilIdle()

            dao.library.emit(listOf(row))
            dao.library.emit(listOf(row.copy()))
            dao.library.emit(listOf(row.copy(unreadCount = 9)))
            advanceUntilIdle()

            assertEquals(1, dao.librarySubscriptions)
        }

    private fun repository(
        dao: MangaDao,
        dispatcher: CoroutineDispatcher
    ): MangaRepositoryImp = MangaRepositoryImp(dao, MangaRepoDispatchers(dispatcher))
}

private fun titleOf(manga: Manga): String = manga.title.value

private fun mangaRow(
    title: String,
    id: Uuid = Uuid.random()
): MangaWithDetails =
    MangaWithDetails(
        manga =
            MangaEntity(
                id = id,
                source = Uuid.random(),
                title = title,
                author = null,
                // A description is exactly the field that makes an identical re-emission expensive to
                // map, so the fixture carries one.
                description = "A long description that would be re-wrapped in Text on every emission",
                coverUrl = "https://example.com/$title.jpg",
                coverHeaders = null,
                coverLastModified = Instant.DISTANT_PAST,
                sourceMangaKey = title.lowercase(),
                url = "https://example.com/$title",
                readingMode = ReadingMode.RIGHT_TO_LEFT,
                publishStatus = PublishStatus.ONGOING,
                filteredScanlators = null,
            ),
        lastRead = null,
        dateAdded = Instant.DISTANT_PAST,
        readingStatus = ReadingStatus.READING,
        unreadCount = 12,
        downloadedCount = 3,
    )

/**
 * A [MangaDao] whose observed reads are driven by hand and whose library subscriptions are counted.
 * Only the three read flows are implemented — everything else is a write path these tests never take.
 */
private class RecordingMangaDao : MangaDao {
    val library = MutableSharedFlow<List<MangaWithDetails>>()
    val manga = MutableSharedFlow<MangaWithDetails?>()
    val bySource = MutableSharedFlow<List<MangaWithDetails>>()

    var librarySubscriptions: Int = 0
        private set

    override fun observeLibrary(): Flow<List<MangaWithDetails>> = library.onStart { librarySubscriptions++ }

    override fun observeManga(mangaId: Uuid): Flow<MangaWithDetails?> = manga

    override fun observeMangaBySource(sourceId: Uuid): Flow<List<MangaWithDetails>> = bySource

    override suspend fun getMangaByIds(mangaIds: Collection<Uuid>): List<MangaWithDetails> = error("unused")

    override suspend fun findMangaIdBySourceAndKey(
        sourceId: Uuid,
        sourceMangaKey: String
    ): Uuid? = error("unused")

    override suspend fun upsert(manga: MangaEntity) = error("unused")

    override suspend fun upsertLibraryEntry(entry: LibraryEntryEntity) = error("unused")

    override suspend fun deleteLibraryEntry(mangaId: Uuid) = error("unused")

    override suspend fun clearCategories(mangaId: Uuid) = error("unused")

    override suspend fun updateFilteredScanlators(
        mangaId: Uuid,
        encoded: String?
    ) = error("unused")

    override suspend fun updateReadingMode(
        mangaId: Uuid,
        mode: ReadingMode
    ) = error("unused")

    override suspend fun updateReadingStatus(
        mangaId: Uuid,
        status: ReadingStatus
    ) = error("unused")

    override suspend fun deleteById(mangaId: Uuid) = error("unused")

    override suspend fun insertLibraryEntryIfAbsent(entry: LibraryEntryEntity) = error("unused")
}

private class MangaRepoDispatchers(
    private val dispatcher: CoroutineDispatcher
) : DispatcherProvider {
    override val default: CoroutineDispatcher = dispatcher
    override val io: CoroutineDispatcher = dispatcher
    override val main: CoroutineDispatcher = dispatcher
}
