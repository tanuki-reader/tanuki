package app.tanuki.features.library.viewmodel

import app.tanuki.core.domain.category.model.Category
import app.tanuki.core.domain.library.model.LibraryFilter
import app.tanuki.core.domain.library.model.LibraryFilter.Companion.None
import app.tanuki.core.domain.library.model.LibrarySort
import app.tanuki.core.domain.library.model.LibrarySort.Companion.Default
import app.tanuki.core.domain.manga.model.Manga
import app.tanuki.features.library.viewmodel.LibraryState.ShowingBottomSheet.CategoryChip
import kotlin.uuid.Uuid

sealed interface LibraryState {
    data object Loading : LibraryState

    data class DisplayLibrary(
        val items: List<Manga>,
        val filter: LibraryFilter = None,
        val sort: LibrarySort = Default,
        val searchQuery: String = ""
    ) : LibraryState

    data class ShowingBottomSheet(
        val items: List<Manga>,
        val filter: LibraryFilter,
        val sort: LibrarySort,
        val categories: List<CategoryChip>
    ) : LibraryState {
        data class CategoryChip(
            val category: Category,
            val isEnabled: Boolean
        )
    }
}

/**
 * Projects a [Category] into a sheet [CategoryChip], marking it enabled when its id is in [selectedIds]
 * (the ids in the active [LibraryFilter.categories]). Selection lives on the filter — the single source of
 * truth `ObserveLibrary` filters by — so the chip's `isEnabled` is always derived, never stored twice.
 */
internal fun Category.toCategoryChip(selectedIds: Set<Uuid>): CategoryChip =
    CategoryChip(
        category = this,
        isEnabled = id in selectedIds
    )