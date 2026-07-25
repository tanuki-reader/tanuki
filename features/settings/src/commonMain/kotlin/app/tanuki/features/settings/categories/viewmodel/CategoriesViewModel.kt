package app.tanuki.features.settings.categories.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tanuki.core.domain.category.model.Category
import app.tanuki.core.domain.category.usecase.CreateCategory
import app.tanuki.core.domain.category.usecase.DeleteCategory
import app.tanuki.core.domain.category.usecase.ObserveCategories
import app.tanuki.core.domain.common.service.DispatcherProvider
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/**
 * Presentation logic for the category-management sub-screen. Owns the library shelves' CRUD; reuses the
 * existing category use cases so this feature adds no new persistence.
 *
 * Compose-free (per CLAUDE.md → MVVM); Metro-injected via [ViewModelKey] + [ContributesIntoMap]. Writes
 * ([onCreateCategory]/[onDeleteCategory]) delegate to use cases and never mutate state directly — the
 * store change flows back through [ObserveCategories].
 */
@Inject
@ContributesIntoMap(AppScope::class)
@ViewModelKey
class CategoriesViewModel(
    observeCategories: ObserveCategories,
    private val createCategory: CreateCategory,
    private val deleteCategory: DeleteCategory,
    dispatcher: DispatcherProvider
) : ViewModel() {
    val categories: StateFlow<List<Category>> =
        observeCategories()
            .flowOn(dispatcher.default)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = emptyList()
            )

    /**
     * Create a category from the dialog's text. A blank name is a no-op (handled in [CreateCategory]),
     * so the UI can call this without re-validating.
     */
    fun onCreateCategory(name: String) {
        viewModelScope.launch { createCategory(name) }
    }

    /** Delete a category (and, by cascade, its manga memberships). */
    fun onDeleteCategory(categoryId: Uuid) {
        viewModelScope.launch { deleteCategory(categoryId) }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}