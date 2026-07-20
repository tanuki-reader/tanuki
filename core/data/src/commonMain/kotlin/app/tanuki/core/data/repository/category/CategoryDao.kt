package app.tanuki.core.data.repository.category

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

/** Room DAO for [CategoryEntity] and the manga↔category join table. */
@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY name")
    fun observeCategories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM manga_categories")
    fun observeMangaCategories(): Flow<List<MangaCategoryEntity>>

    @Upsert
    suspend fun upsert(category: CategoryEntity)

    @Query("DELETE FROM categories WHERE id = :categoryId")
    suspend fun deleteById(categoryId: Uuid)

    @Query("DELETE FROM manga_categories WHERE manga_id = :mangaId")
    suspend fun clearMangaCategories(mangaId: Uuid)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMangaCategories(rows: List<MangaCategoryEntity>)

    /** Replaces a manga's memberships with exactly [categoryIds] in a single transaction. */
    @Transaction
    suspend fun setMangaCategories(
        mangaId: Uuid,
        categoryIds: Set<Uuid>,
    ) {
        clearMangaCategories(mangaId)
        insertMangaCategories(categoryIds.map { MangaCategoryEntity(mangaId = mangaId, categoryId = it) })
    }
}