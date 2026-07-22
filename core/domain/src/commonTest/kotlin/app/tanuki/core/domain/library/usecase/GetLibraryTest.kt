package app.tanuki.core.domain.library.usecase

import app.tanuki.core.domain.category.repository.CategoryRepository
import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.common.model.Url
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.library.model.LibraryFilter
import app.tanuki.core.domain.library.model.LibrarySort
import app.tanuki.core.domain.library.model.LibrarySort.Direction.Ascending
import app.tanuki.core.domain.library.model.LibrarySort.Direction.Descending
import app.tanuki.core.domain.library.model.LibrarySort.Mode.Alphabetical
import app.tanuki.core.domain.manga.model.Manga
import app.tanuki.core.domain.manga.model.MangaCover
import app.tanuki.core.domain.manga.model.PublishStatus
import app.tanuki.core.domain.manga.model.ReadingMode
import app.tanuki.core.domain.manga.model.ReadingStatus
import app.tanuki.core.domain.manga.repository.MangaRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class GetLibraryTest {
    @Test
    fun `filter and sort are applied and re-derive in memory when the knobs change`() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val library = listOf(manga("Berserk"), manga("Akira"), manga("Naruto"))
            val repo = FakeMangaRepository(MutableStateFlow(library))

            val search = MutableStateFlow("")
            val sort = MutableStateFlow(LibrarySort(Alphabetical, Ascending))
            val filter = MutableStateFlow(LibraryFilter.None)

            val emissions = mutableListOf<List<String>>()
            backgroundScope.launch(dispatcher) {
                getLibrary(repo, dispatcher).invoke(search, sort, filter).collect {
                    emissions += it.map(::titleOf)
                }
            }
            advanceUntilIdle()

            // Sorted alphabetically ascending by default.
            assertEquals(listOf("Akira", "Berserk", "Naruto"), emissions.last())

            // Flip the sort direction: same rows, re-ordered in memory.
            sort.value = LibrarySort(Alphabetical, Descending)
            advanceUntilIdle()
            assertEquals(listOf("Naruto", "Berserk", "Akira"), emissions.last())

            // Only one subscription to the repository across all of this — the knobs never re-query.
            assertEquals(1, repo.subscriptions)
        }

    @Test
    fun `search matches titles case-insensitively and a blank query matches everything`() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val library = listOf(manga("Berserk"), manga("Akira"), manga("Naruto"))
            val repo = FakeMangaRepository(MutableStateFlow(library))

            val search = MutableStateFlow("")
            val sort = MutableStateFlow(LibrarySort(Alphabetical, Ascending))
            val filter = MutableStateFlow(LibraryFilter.None)

            val emissions = mutableListOf<List<String>>()
            backgroundScope.launch(dispatcher) {
                getLibrary(repo, dispatcher).invoke(search, sort, filter).collect {
                    emissions += it.map(::titleOf)
                }
            }
            advanceUntilIdle()

            search.value = "ak" // case-insensitive substring: only "Akira" contains it
            advanceUntilIdle()
            assertEquals(listOf("Akira"), emissions.last())

            search.value = "   " // blank/whitespace matches everything
            advanceUntilIdle()
            assertEquals(listOf("Akira", "Berserk", "Naruto"), emissions.last())

            assertEquals(1, repo.subscriptions)
        }

    @Test
    fun `filter narrows the library by reading status and re-derives without re-subscribing`() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val library =
                listOf(
                    manga("Berserk", readingStatus = ReadingStatus.READING),
                    manga("Akira", readingStatus = ReadingStatus.COMPLETED)
                )
            val repo = FakeMangaRepository(MutableStateFlow(library))

            val search = MutableStateFlow("")
            val sort = MutableStateFlow(LibrarySort(Alphabetical, Ascending))
            val filter = MutableStateFlow(LibraryFilter.None)

            val emissions = mutableListOf<List<String>>()
            backgroundScope.launch(dispatcher) {
                getLibrary(repo, dispatcher).invoke(search, sort, filter).collect {
                    emissions += it.map(::titleOf)
                }
            }
            advanceUntilIdle()
            assertEquals(listOf("Akira", "Berserk"), emissions.last())

            filter.value = LibraryFilter(readingStatus = ReadingStatus.READING)
            advanceUntilIdle()
            assertEquals(listOf("Berserk"), emissions.last())

            assertEquals(1, repo.subscriptions)
        }

    private fun getLibrary(
        repo: MangaRepository,
        dispatcher: CoroutineDispatcher
    ): ObserveLibrary =
        ObserveLibrary.Default(
            mangaRepository = repo,
            categoryRepository = NoCategoriesRepository,
            dispatchers = SingleDispatcherProvider(dispatcher)
        )
}

private fun titleOf(manga: Manga): String = manga.title.value

private fun manga(
    title: String,
    readingStatus: ReadingStatus? = ReadingStatus.READING,
    publishStatus: PublishStatus = PublishStatus.ONGOING,
    id: Uuid = Uuid.random()
): Manga =
    Manga(
        id = id,
        source = Uuid.random(),
        title = Text(title),
        author = null,
        description = null,
        cover = MangaCover(mangaId = id, url = null, lastModified = Instant.DISTANT_PAST),
        url = Url("https://example.com/$title"),
        dateAdded = null,
        readingMode = ReadingMode.RIGHT_TO_LEFT,
        publishStatus = publishStatus,
        readingStatus = readingStatus
    )

/** Records how many times [observeLibrary] is subscribed, to prove knob changes don't re-query. */
private class FakeMangaRepository(
    private val library: MutableStateFlow<List<Manga>>
) : MangaRepository {
    var subscriptions: Int = 0
        private set

    override fun observeLibrary(): Flow<List<Manga>> = library.onStart { subscriptions++ }

    override fun observeManga(mangaId: Uuid): Flow<Manga?> = error("unused")

    override fun observeMangaBySource(sourceId: Uuid): Flow<List<Manga>> = error("unused")

    override suspend fun getMangaIdBySourceAndKey(sourceId: Uuid, sourceMangaKey: String): Uuid? = error("unused")

    override suspend fun upsertManga(manga: Manga) = error("unused")

    override suspend fun deleteManga(mangaId: Uuid) = error("unused")

    override suspend fun addToLibrary(mangaId: Uuid) = error("unused")

    override suspend fun removeFromLibrary(mangaId: Uuid) = error("unused")
}

private object NoCategoriesRepository : CategoryRepository {
    override fun observeLibraryCategories(): Flow<Map<Uuid, Set<Uuid>>> = flowOf(emptyMap())

    override fun observeCategories() = error("unused")

    override suspend fun setMangaCategories(
        mangaId: Uuid,
        categoryIds: Set<Uuid>
    ) = error("unused")

    override suspend fun upsertCategory(category: app.tanuki.core.domain.category.model.Category) = error("unused")

    override suspend fun deleteCategory(categoryId: Uuid) = error("unused")
}

private class SingleDispatcherProvider(
    private val dispatcher: CoroutineDispatcher
) : DispatcherProvider {
    override val default: CoroutineDispatcher = dispatcher
    override val io: CoroutineDispatcher = dispatcher
    override val main: CoroutineDispatcher = dispatcher
}