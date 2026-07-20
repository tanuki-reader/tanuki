package app.tanuki.core.domain.category.repository

import app.tanuki.core.domain.category.model.Category
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

/**
 * Persistence operations for library [Category]s.
 *
 * Reads are reactive ([Flow]); writes are `suspend`. Naming convention: `observe*` returns a live
 * [Flow], while `add`/`update`/`delete` mutate a single category. Implementations must reject
 * mutations to the immutable built-in "Uncategorized" category (see [Category.isSystemCategory]).
 *
 * @see Category
 */
interface CategoryRepository {
    /**
     * Observes all categories ordered for display.
     *
     * @return A [Flow] of categories, re-emitting on any change.
     */
    fun observeCategories(): Flow<List<Category>>

    /**
     * Observes the whole library's manga→category memberships as a map.
     *
     * @return A [Flow] of `mangaId -> set of its category ids`, re-emitting on any membership change.
     *   Manga with no row in the join table is simply absent from the map (i.e. "uncategorized").
     */
    fun observeLibraryCategories(): Flow<Map<Uuid, Set<Uuid>>>

    /**
     * Replaces a manga's category memberships with exactly [categoryIds] (clears, then assigns), in a
     * single transaction.
     *
     * @param mangaId Local id of the manga whose memberships to set.
     * @param categoryIds The categories the manga should belong to; empty leaves it uncategorized.
     */
    suspend fun setMangaCategories(
        mangaId: Uuid,
        categoryIds: Set<Uuid>
    )

    /**
     * Inserts or updates a category.
     *
     * @param category The category to persist.
     */
    suspend fun upsertCategory(category: Category)

    /**
     * Deletes a category by its id.
     *
     * @param categoryId Represent's the id of the category to remove.
     */
    suspend fun deleteCategory(categoryId: Uuid)
}