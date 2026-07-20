package app.tanuki.core.domain.category.usecase

import app.tanuki.core.domain.category.repository.CategoryRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlin.uuid.Uuid

/**
 * Use case: set exactly which categories a manga belongs to, replacing its current memberships in one
 * transaction. Passing an empty set leaves the manga uncategorized.
 */
sealed interface SetMangaCategories {
    suspend operator fun invoke(mangaId: Uuid, categoryIds: Set<Uuid>)

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val categoryRepository: CategoryRepository
    ) : SetMangaCategories {
        override suspend fun invoke(mangaId: Uuid, categoryIds: Set<Uuid>) =
            categoryRepository.setMangaCategories(mangaId, categoryIds)
    }
}
