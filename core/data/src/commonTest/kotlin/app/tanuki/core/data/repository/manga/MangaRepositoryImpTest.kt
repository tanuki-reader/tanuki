package app.tanuki.core.data.repository.manga

import app.tanuki.core.data.repository.library.LibraryEntryEntity
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.common.model.Url
import app.tanuki.core.domain.manga.model.Manga
import app.tanuki.core.domain.manga.model.MangaCover
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
    fun `an identical by-ids re-emission is not passed on`() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val row = mangaRow("Berserk")
            val dao = RecordingMangaDao()

            val emissions = mutableListOf<List<String>>()
            backgroundScope.launch(dispatcher) {
                repository(dao, dispatcher)
                    .observeMangaByIds(listOf(row.manga.id))
                    .collect { emissions += it.map(::titleOf) }
            }
            advanceUntilIdle()

            // This is the read that backs Home's pending-update list, so it is the one most likely to be
            // subscribed while a download fires `setProgress` once per page.
            dao.byIds.emit(listOf(row))
            dao.byIds.emit(listOf(row.copy()))
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

    @Test
    fun `a source-detail write reaches the dao as columns with the headers encoded`() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val dao = RecordingMangaDao()
            val id = Uuid.random()

            repository(dao, dispatcher).setSourceDetails(
                mangaId = id,
                title = Text("Berserk"),
                author = Text("Kentaro Miura"),
                description = null,
                publishStatus = PublishStatus.ONGOING,
                cover =
                    MangaCover(
                        mangaId = id,
                        url = Url("https://cdn.example/berserk.jpg"),
                        lastModified = Instant.fromEpochMilliseconds(7_000),
                        headers = mapOf("Referer" to "https://example.com/")
                    )
            )
            advanceUntilIdle()

            // The storage encoding stays in this layer: the domain hands over a Map and never sees the
            // newline-joined column the codec produces.
            assertEquals(
                listOf(
                    id,
                    "Berserk",
                    "Kentaro Miura",
                    null,
                    PublishStatus.ONGOING,
                    "https://cdn.example/berserk.jpg",
                    "Referer: https://example.com/",
                    Instant.fromEpochMilliseconds(7_000)
                ),
                dao.sourceDetails
            )
        }

    @Test
    fun `a cover-header write reaches the dao encoded`() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val dao = RecordingMangaDao()
            val id = Uuid.random()

            repository(dao, dispatcher).setCoverHeaders(id, mapOf("Referer" to "https://example.com/"))
            advanceUntilIdle()

            assertEquals(id to "Referer: https://example.com/", dao.coverHeaders)
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
 * Only the four read flows are implemented — everything else is a write path these tests never take.
 */
private class RecordingMangaDao : MangaDao {
    val library = MutableSharedFlow<List<MangaWithDetails>>()
    val manga = MutableSharedFlow<MangaWithDetails?>()
    val bySource = MutableSharedFlow<List<MangaWithDetails>>()
    val byIds = MutableSharedFlow<List<MangaWithDetails>>()

    var librarySubscriptions: Int = 0
        private set

    override fun observeLibrary(): Flow<List<MangaWithDetails>> = library.onStart { librarySubscriptions++ }

    override fun observeManga(mangaId: Uuid): Flow<MangaWithDetails?> = manga

    override fun observeMangaBySource(sourceId: Uuid): Flow<List<MangaWithDetails>> = bySource

    override suspend fun getMangaByIds(mangaIds: Collection<Uuid>): List<MangaWithDetails> = error("unused")

    override fun observeMangaByIds(mangaIds: Collection<Uuid>): Flow<List<MangaWithDetails>> = byIds

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

    /** Records the arguments of the targeted source-detail write, which one test asserts on. */
    var sourceDetails: List<Any?>? = null
        private set

    var coverHeaders: Pair<Uuid, String?>? = null
        private set

    val chaptersFetched = mutableListOf<Pair<Uuid, Instant>>()
    val detailsFetched = mutableListOf<Pair<Uuid, Instant>>()

    override suspend fun updateSourceDetails(
        mangaId: Uuid,
        title: String,
        author: String?,
        description: String?,
        publishStatus: PublishStatus,
        coverUrl: String?,
        coverHeaders: String?,
        coverLastModified: Instant
    ) {
        sourceDetails =
            listOf(mangaId, title, author, description, publishStatus, coverUrl, coverHeaders, coverLastModified)
    }

    override suspend fun updateCoverHeaders(
        mangaId: Uuid,
        encoded: String?
    ) {
        coverHeaders = mangaId to encoded
    }

    override suspend fun recordChapterSync(
        mangaId: Uuid,
        at: Instant,
        nextUpdateAt: Instant,
        fetchInterval: Int
    ) {
        chaptersFetched += mangaId to at
    }

    override suspend fun markDetailsFetched(
        mangaId: Uuid,
        at: Instant
    ) {
        detailsFetched += mangaId to at
    }

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
