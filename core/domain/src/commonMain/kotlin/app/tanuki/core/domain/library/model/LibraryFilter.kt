package app.tanuki.core.domain.library.model

import app.tanuki.core.domain.category.model.Category
import app.tanuki.core.domain.common.model.TriState
import app.tanuki.core.domain.library.model.CategoryMatchMode.Any
import app.tanuki.core.domain.manga.model.ReadingStatus
import kotlin.uuid.Uuid

/**
 * The active library filters. An item is shown only if it satisfies every set criterion.
 *
 * Criteria backed by data already on `Manga` (reading status, publication) and by the
 * manga↔category join ([categories]) exist today. Downloaded / unread / tracked filters wait on the
 * download, chapter, and tracking subsystems respectively; add them here when those land.
 *
 * @property readingStatus When non-null, keep only manga with this user reading status; `null` = any.
 * @property publicationCompleted Gate on whether the series' publication has finished; see [TriState].
 * @property categories Local ids of the categories to filter by; empty = any. How multiple selected
 *   categories combine is governed by [categoryMatchMode].
 * @property categoryMatchMode Whether a manga must be in ANY or ALL of the selected [categories].
 */
data class LibraryFilter(
    val readingStatus: ReadingStatus? = null,
    val publicationCompleted: TriState = TriState.DISABLED,
    val categories: Set<Category> = emptySet(),
    val categoryMatchMode: CategoryMatchMode = Any
) {
    /** `true` when any criterion is set — drives the "filters active" indicator in the UI. */
    val isActive: Boolean
        get() =
            readingStatus != null ||
                publicationCompleted != TriState.DISABLED ||
                categories.isNotEmpty()

    companion object {
        /** No filtering — the whole library passes. */
        val None = LibraryFilter()
    }
}

/**
 * How multiple selected [LibraryFilter.categories] combine when filtering the library.
 *
 * [Any] keeps manga in at least one selected category (OR); [All] keeps only manga that are in every
 * selected category (AND). Ignored when no categories are selected.
 */
enum class CategoryMatchMode { Any, All }