package app.tanuki.core.domain.category.usecase

import app.tanuki.core.domain.category.model.Category
import app.tanuki.core.domain.category.repository.CategoryRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow

interface ObserveCategories {
    operator fun invoke(): Flow<List<Category>>

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val categoryRepository: CategoryRepository
    ) : ObserveCategories {
        override fun invoke(): Flow<List<Category>> =
            categoryRepository
                .observeCategories()
    }
}