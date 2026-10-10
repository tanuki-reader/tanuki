package app.tanuki.features.settings.categories.viewmodel

import app.tanuki.core.domain.category.model.Category
import app.tanuki.core.domain.category.repository.CategoryRepository
import app.tanuki.core.domain.category.usecase.CreateCategory
import app.tanuki.core.domain.category.usecase.DeleteCategory
import app.tanuki.core.domain.category.usecase.ObserveCategories
import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.common.service.LogPriority
import app.tanuki.core.domain.common.service.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** The dialogs are ViewModel state now, so both UIs only render them; these cases pin that flow. */
@OptIn(ExperimentalCoroutinesApi::class)
class CategoriesViewModelTest {
    private val mainDispatcher = StandardTestDispatcher()
    private val repository = FakeCategoryRepository()
    private val viewModel =
        CategoriesViewModel(
            observeCategories = ObserveCategories.Default(repository),
            createCategory = CreateCategory.Default(repository),
            deleteCategory = DeleteCategory.Default(repository),
            dispatcher = MainDispatchers,
            logger = SilentLogger
        )

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the create dialog opens then creates and closes`() = runTest {
        val job = collect()

        viewModel.onCreateRequested()
        runCurrent()
        assertTrue(viewModel.uiState.value.creating)

        viewModel.onCreateCategory("Favourites")
        runCurrent()
        assertFalse(viewModel.uiState.value.creating)
        assertEquals(listOf("Favourites"), viewModel.uiState.value.categories.map { it.name })
        job.cancel()
    }

    @Test
    fun `a deletion waits for confirmation`() = runTest {
        repository.categories.value = listOf(shelf)
        val job = collect()

        viewModel.onDeleteRequested(shelfItem)
        runCurrent()
        assertEquals(shelfItem, viewModel.uiState.value.pendingDeletion)
        assertEquals(1, viewModel.uiState.value.categories.size)

        viewModel.onDeleteConfirmed()
        runCurrent()
        assertNull(viewModel.uiState.value.pendingDeletion)
        assertTrue(viewModel.uiState.value.categories.isEmpty())
        job.cancel()
    }

    @Test
    fun `dismissing closes either dialog without acting`() = runTest {
        repository.categories.value = listOf(shelf)
        val job = collect()

        viewModel.onDeleteRequested(shelfItem)
        viewModel.onDialogDismissed()
        runCurrent()
        assertNull(viewModel.uiState.value.pendingDeletion)
        assertEquals(listOf(shelfItem), viewModel.uiState.value.categories)
        job.cancel()
    }

    private val shelf = Category(Uuid.random(), Text("Shelf"))
    private val shelfItem = CategoryItem(shelf.id, "Shelf")

    private fun TestScope.collect() = backgroundScope.launch { viewModel.uiState.collect {} }.also { runCurrent() }
}

private class FakeCategoryRepository : CategoryRepository {
    val categories = MutableStateFlow(emptyList<Category>())

    override fun observeCategories(): Flow<List<Category>> = categories

    override fun observeLibraryCategories(): Flow<Map<Uuid, Set<Uuid>>> = categories.map { emptyMap() }

    override suspend fun setMangaCategories(
        mangaId: Uuid,
        categoryIds: Set<Uuid>
    ) = Unit

    override suspend fun upsertCategory(category: Category) {
        categories.value = categories.value.filterNot { it.id == category.id } + category
    }

    override suspend fun deleteCategory(categoryId: Uuid) {
        categories.value = categories.value.filterNot { it.id == categoryId }
    }
}

private object MainDispatchers : DispatcherProvider {
    override val default: CoroutineDispatcher get() = Dispatchers.Main
    override val io: CoroutineDispatcher get() = Dispatchers.Main
    override val main: CoroutineDispatcher get() = Dispatchers.Main
}

private object SilentLogger : Logger {
    override fun log(
        priority: LogPriority,
        tag: String?,
        message: String?,
        throwable: Throwable?,
        analyticsEvent: String?,
        addToBreadcrumbs: Boolean
    ) = Unit

    override fun addReceiver(receiver: Logger.LogReceiver) = Unit

    override fun removeReceiver(receiver: Logger.LogReceiver) = Unit
}
