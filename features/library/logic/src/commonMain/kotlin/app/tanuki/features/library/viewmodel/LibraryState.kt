package app.tanuki.features.library.viewmodel

import app.tanuki.core.domain.category.model.Category
import app.tanuki.core.domain.library.model.LibraryFilter
import app.tanuki.core.domain.library.model.LibraryManga
import app.tanuki.core.domain.library.model.LibrarySort
import app.tanuki.core.domain.settings.model.LibraryGridSize
import kotlin.uuid.Uuid

sealed interface LibraryState {
    data object Loading : LibraryState

    /**
     * The loaded library. The grid is the same whether or not the sort & filter sheet is over it, so
     * the sheet is a flag ([sheetOpen]) rather than a state of its own.
     *
     * @property items the titles passing [filter] and [searchQuery], in [sort] order.
     * @property categories every category, for the sheet's category section, each marked when selected.
     * @property gridSize the cover size chosen in Appearance; each platform maps it to a minimum width.
     */
    data class Content(
        val items: List<LibraryItem>,
        val searchQuery: String,
        val filter: LibraryFilter,
        val sort: LibrarySort,
        val categories: List<CategoryChip>,
        val sheetOpen: Boolean,
        val sheetTab: LibrarySheetTab,
        val gridSize: LibraryGridSize
    ) : LibraryState {
        /** Any filter is set — drives the toolbar's "filters on" indicator. */
        val filterActive: Boolean get() = filter.isActive

        /** Why the grid is empty, or `null` when it isn't. */
        val empty: LibraryEmpty?
            get() =
                when {
                    items.isNotEmpty() -> null
                    searchQuery.isNotBlank() -> LibraryEmpty.Search
                    filter.isActive -> LibraryEmpty.Filter
                    else -> LibraryEmpty.Library
                }
    }
}

/** The sort & filter sheet's two panes. */
enum class LibrarySheetTab { Sort, Filter }

/** Which empty state to show: nothing in the library, nothing matching the search, or the filters. */
enum class LibraryEmpty { Library, Search, Filter }

/**
 * One grid tile. Plain values only, so Swift reads them as-is (the domain's `Text` is a value class,
 * which reaches Swift untyped).
 *
 * @property title series title (source content, not localizable).
 * @property coverUrl remote cover URL, or `null` for the fallback ground.
 * @property coverHeaders headers the cover fetch must carry; empty for almost every source.
 * @property unreadCount chapters with no progress; `0` hides the badge.
 */
data class LibraryItem(
    val mangaId: Uuid,
    val title: String,
    val coverUrl: String?,
    val coverHeaders: Map<String, String>,
    val unreadCount: Int,
    val isDownloaded: Boolean
)

/** A category in the sheet, with how many titles it holds; [isEnabled] when it is in the active filter. */
data class CategoryChip(
    val id: Uuid,
    val name: String,
    val count: Int,
    val isEnabled: Boolean
)

internal fun LibraryManga.toItem(): LibraryItem =
    LibraryItem(
        mangaId = id,
        title = title.value,
        coverUrl = cover.url?.value,
        coverHeaders = cover.headers,
        unreadCount = unreadCount.toInt(),
        isDownloaded = isDownloaded
    )

internal fun Category.toChip(selectedIds: Set<Uuid>): CategoryChip =
    CategoryChip(id = id, name = name.value, count = associatedMangas.size, isEnabled = id in selectedIds)
