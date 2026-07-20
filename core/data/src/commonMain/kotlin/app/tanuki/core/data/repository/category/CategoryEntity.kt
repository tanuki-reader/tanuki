package app.tanuki.core.data.repository.category

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.tanuki.core.data.repository.library.LibraryEntryEntity
import app.tanuki.core.domain.category.model.Category
import app.tanuki.core.domain.common.model.Text
import kotlin.uuid.Uuid

/**
 * Room row for a library [Category].
 *
 * The domain [Text] name is flattened to a plain [String] column; conversion happens in the mappers
 * ([toDomain] / [Category.toEntity]).
 */
@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val id: Uuid,
    val name: String,
)

/**
 * Row of the library-entry↔category join table. Only manga that are in the library can be categorized,
 * so `manga_id` foreign-keys to `library(id)` (not `manga`); "uncategorized" is the absence of any row
 * here, never a stored value. Both FKs cascade, so removing a manga from the library — or deleting a
 * category — drops its memberships automatically.
 */
@Entity(
    tableName = "manga_categories",
    primaryKeys = ["manga_id", "category_id"],
    foreignKeys = [
        ForeignKey(
            entity = LibraryEntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["manga_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    // manga_id is covered by the composite PK prefix; index the reverse-lookup side.
    indices = [Index("category_id")],
)
data class MangaCategoryEntity(
    @ColumnInfo(name = "manga_id") val mangaId: Uuid,
    @ColumnInfo(name = "category_id") val categoryId: Uuid,
)

/**
 * Maps a category row to its domain model. [associatedMangas] is not stored on the row — it comes from
 * the `manga_categories` join table and is supplied by the repository (see
 * [CategoryRepositoryImp.observeCategories]); it defaults to empty for callers that don't need it.
 */
fun CategoryEntity.toDomain(associatedMangas: Set<Uuid> = emptySet()): Category =
    Category(
        id = id,
        name = Text(name),
        associatedMangas = associatedMangas,
    )

fun Category.toEntity(): CategoryEntity =
    CategoryEntity(
        id = id,
        name = name.value,
    )