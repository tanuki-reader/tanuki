package app.tanuki.core.domain.category.usecase

import app.tanuki.core.domain.category.model.Category
import app.tanuki.core.domain.category.repository.CategoryRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

sealed interface UpsertCategory {
    suspend operator fun invoke(category: Category)

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val categoryRepository: CategoryRepository
    ) : UpsertCategory {
        override suspend fun invoke(category: Category) = categoryRepository.upsertCategory(category)
    }
}