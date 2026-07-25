package app.tanuki.core.domain.category.usecase

import app.tanuki.core.domain.category.repository.CategoryRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlin.uuid.Uuid

sealed interface DeleteCategory {
    suspend operator fun invoke(categoryId: Uuid)

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val categoryRepository: CategoryRepository
    ) : DeleteCategory {
        override suspend fun invoke(categoryId: Uuid) = categoryRepository.deleteCategory(categoryId)
    }
}