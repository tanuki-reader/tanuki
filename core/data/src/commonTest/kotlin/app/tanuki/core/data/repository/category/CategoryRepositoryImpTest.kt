package app.tanuki.core.data.repository.category

import app.tanuki.core.domain.category.model.Category
import app.tanuki.core.domain.common.service.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/**
 * Both readers on [CategoryRepositoryImp] need the whole `manga_categories` table and regroup it in
 * Kotlin — by category for `observeCategories`, by manga for `observeLibraryCategories`. These pin that
 * the table is read through one deduped flow, so a write that leaves the memberships as they were
 * (a `setMangaCategories` rewriting the same rows, say) costs neither grouping.
 *
 * The fake DAO emits from a [MutableSharedFlow] rather than a `MutableStateFlow` on purpose: a state
 * flow conflates equal values itself, which would make these pass with the dedupe removed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CategoryRepositoryImpTest {
    @Test
    fun `an identical membership re-emission does not re-derive the library categories`() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val dao = HandDrivenCategoryDao()
            val mangaId = Uuid.random()
            val categoryId = Uuid.random()
            val rows = listOf(MangaCategoryEntity(mangaId = mangaId, categoryId = categoryId))

            val emissions = mutableListOf<Map<Uuid, Set<Uuid>>>()
            backgroundScope.launch(dispatcher) {
                repository(dao, dispatcher).observeLibraryCategories().collect { emissions += it }
            }
            advanceUntilIdle()

            dao.mangaCategories.emit(rows)
            advanceUntilIdle()
            assertEquals(mapOf(mangaId to setOf(categoryId)), emissions.single())

            dao.mangaCategories.emit(rows.map { it.copy() })
            advanceUntilIdle()
            assertEquals(1, emissions.size, "an unchanged join table must not re-emit")

            dao.mangaCategories.emit(emptyList())
            advanceUntilIdle()
            assertEquals(emptyMap(), emissions.last())
        }

    @Test
    fun `an identical membership re-emission does not re-derive the categories`() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val dao = HandDrivenCategoryDao()
            val mangaId = Uuid.random()
            val category = CategoryEntity(id = Uuid.random(), name = "Reading")
            val rows = listOf(MangaCategoryEntity(mangaId = mangaId, categoryId = category.id))

            val emissions = mutableListOf<List<Category>>()
            backgroundScope.launch(dispatcher) {
                repository(dao, dispatcher).observeCategories().collect { emissions += it }
            }
            advanceUntilIdle()

            dao.categories.emit(listOf(category))
            dao.mangaCategories.emit(rows)
            advanceUntilIdle()
            assertEquals(setOf(mangaId), emissions.single().single().associatedMangas)

            dao.mangaCategories.emit(rows.map { it.copy() })
            advanceUntilIdle()
            assertEquals(1, emissions.size, "an unchanged join table must not re-emit")
        }

    private fun repository(
        dao: CategoryDao,
        dispatcher: CoroutineDispatcher
    ): CategoryRepositoryImp = CategoryRepositoryImp(dao, CategoryRepoDispatchers(dispatcher))
}

/** A [CategoryDao] whose two observed reads are driven by hand; the write paths are never taken. */
private class HandDrivenCategoryDao : CategoryDao {
    val categories = MutableSharedFlow<List<CategoryEntity>>()
    val mangaCategories = MutableSharedFlow<List<MangaCategoryEntity>>()

    override fun observeCategories(): Flow<List<CategoryEntity>> = categories

    override fun observeMangaCategories(): Flow<List<MangaCategoryEntity>> = mangaCategories

    override suspend fun upsert(category: CategoryEntity) = error("unused")

    override suspend fun deleteById(categoryId: Uuid) = error("unused")

    override suspend fun clearMangaCategories(mangaId: Uuid) = error("unused")

    override suspend fun insertMangaCategories(rows: List<MangaCategoryEntity>) = error("unused")
}

private class CategoryRepoDispatchers(
    private val dispatcher: CoroutineDispatcher
) : DispatcherProvider {
    override val default: CoroutineDispatcher = dispatcher
    override val io: CoroutineDispatcher = dispatcher
    override val main: CoroutineDispatcher = dispatcher
}
