package app.tanuki.core.data.repository.category

import app.tanuki.core.domain.category.model.Category
import app.tanuki.core.domain.category.repository.CategoryRepository
import app.tanuki.core.domain.common.service.DispatcherProvider
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlin.uuid.Uuid

/**
 * Room-backed [CategoryRepository]. Maps between [Category] domain models and [CategoryEntity] rows,
 * delegating persistence to [CategoryDao].
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class CategoryRepositoryImp(
    private val categoryDao: CategoryDao,
    private val dispatcherProvider: DispatcherProvider
) : CategoryRepository {
    /**
     * The `manga_categories` join table, declared once and shared by both readers below.
     *
     * Each of them needs the whole table and regroups it in Kotlin — by `categoryId` for
     * [observeCategories], by `mangaId` for [observeLibraryCategories] — so subscribing the query
     * separately per reader meant paying that regrouping twice for every change. Worse, Room invalidates
     * the flow on *any* write to the table, including the membership rewrites `setMangaCategories`
     * performs as a clear-then-insert, which frequently land on the same rows the reader already has.
     * [distinctUntilChanged] stops those where they cost nothing: before either grouping.
     */
    private val mangaCategories: Flow<List<MangaCategoryEntity>> =
        categoryDao.observeMangaCategories().distinctUntilChanged()

    override fun observeCategories(): Flow<List<Category>> =
        combine(
            categoryDao.observeCategories(),
            mangaCategories
        ) { categories, memberships ->
            val mangasByCategory =
                memberships
                    .groupBy(MangaCategoryEntity::categoryId)
                    .mapValues { (_, rows) -> rows.mapTo(mutableSetOf(), MangaCategoryEntity::mangaId) }
            categories.map { entity ->
                entity.toDomain(associatedMangas = mangasByCategory[entity.id].orEmpty())
            }
        }.flowOn(dispatcherProvider.io)

    override fun observeLibraryCategories(): Flow<Map<Uuid, Set<Uuid>>> =
        mangaCategories
            .map { rows ->
                rows
                    .groupBy(MangaCategoryEntity::mangaId)
                    .mapValues { (_, memberships) ->
                        memberships.mapTo(
                            mutableSetOf(),
                            MangaCategoryEntity::categoryId
                        )
                    }
            }.flowOn(dispatcherProvider.io)

    override suspend fun setMangaCategories(
        mangaId: Uuid,
        categoryIds: Set<Uuid>
    ) = categoryDao.setMangaCategories(mangaId, categoryIds)

    override suspend fun upsertCategory(category: Category) = categoryDao.upsert(category.toEntity())

    override suspend fun deleteCategory(categoryId: Uuid) = categoryDao.deleteById(categoryId)
}