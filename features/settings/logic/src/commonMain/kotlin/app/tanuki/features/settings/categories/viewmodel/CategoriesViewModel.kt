package app.tanuki.features.settings.categories.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tanuki.core.domain.category.model.Category
import app.tanuki.core.domain.category.usecase.CreateCategory
import app.tanuki.core.domain.category.usecase.DeleteCategory
import app.tanuki.core.domain.category.usecase.ObserveCategories
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.presentation.StateViewModel
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/**
 * Presentation logic for the category-management sub-screen. Owns the library shelves' CRUD; reuses the
 * existing category use cases so this feature adds no new persistence.
 *
 * Compose-free (per CLAUDE.md → MVVM); Metro-injected via [ViewModelKey] + [ContributesIntoMap]. Writes
 * ([onCreateCategory]/[onDeleteConfirmed]) delegate to use cases and never mutate state directly — the
 * store change flows back through [ObserveCategories]. Which dialog is open is state too, so neither
 * platform's UI holds it.
 */
@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
class CategoriesViewModel(
    observeCategories: ObserveCategories,
    private val createCategory: CreateCategory,
    private val deleteCategory: DeleteCategory,
    dispatcher: DispatcherProvider,
    logger: Logger
) : StateViewModel<CategoriesState>(dispatcher, logger, CategoriesState()) {
    private val creating = MutableStateFlow(false)
    private val pendingDeletion = MutableStateFlow<CategoryItem?>(null)

    override val state: Flow<CategoriesState> =
        combine(observeCategories(), creating, pendingDeletion) { categories, creating, pending ->
            CategoriesState(categories.map(Category::toItem), creating, pending)
        }

    /** The add button was tapped: open the create dialog. */
    fun onCreateRequested() {
        creating.value = true
    }

    /**
     * Create a category from the dialog's text and close it. A blank name is a no-op (handled in
     * [CreateCategory]), so the UI can call this without re-validating.
     */
    fun onCreateCategory(name: String) {
        creating.value = false
        viewModelScope.launch { createCategory(name) }
    }

    /** A category's delete action was tapped: ask for confirmation. */
    fun onDeleteRequested(category: CategoryItem) {
        pendingDeletion.value = category
    }

    /** The deletion was confirmed: delete it (and, by cascade, its manga memberships). */
    fun onDeleteConfirmed() {
        val category = pendingDeletion.value ?: return
        pendingDeletion.value = null
        viewModelScope.launch { deleteCategory(category.id) }
    }

    /** Either dialog was dismissed without acting. */
    fun onDialogDismissed() {
        creating.value = false
        pendingDeletion.value = null
    }
}

/**
 * @property categories the user's categories, in display order.
 * @property creating whether the create dialog is open.
 * @property pendingDeletion the category awaiting delete confirmation, or `null` when none is.
 */
data class CategoriesState(
    val categories: List<CategoryItem> = emptyList(),
    val creating: Boolean = false,
    val pendingDeletion: CategoryItem? = null
)

/**
 * One category row. Its own type rather than the domain [Category], whose name is the value class
 * `Text`: value classes reach Swift as an untyped `id`, so the name has to be a plain [String] here.
 */
data class CategoryItem(
    val id: Uuid,
    val name: String
)

private fun Category.toItem() = CategoryItem(id, name.value)
