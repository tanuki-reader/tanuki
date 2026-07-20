package app.tanuki.core.domain.category.usecase

import app.tanuki.core.domain.category.repository.CategoryRepository
import kotlin.uuid.Uuid

sealed interface DeleteCategory {
    suspend operator fun invoke(categoryId: Uuid)

    class Default(
        private val categoryRepository: CategoryRepository
    ) : DeleteCategory {
        override suspend fun invoke(categoryId: Uuid) = categoryRepository.deleteCategory(categoryId)
    }
}