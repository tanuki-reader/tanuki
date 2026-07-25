package app.tanuki.core.domain.category.usecase

import app.tanuki.core.domain.category.model.Category
import app.tanuki.core.domain.category.repository.CategoryRepository
import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.common.service.uuidV7
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

/**
 * Creates a new library [Category] from a user-typed name: trims it, mints a fresh time-ordered id, and
 * persists it. A blank name (after trimming) is ignored — the UI shouldn't offer creation for empty input,
 * and this guards the invariant so [Text]'s non-blank contract can't throw here.
 *
 * @return `true` if a category was created, `false` if the name was blank and nothing was persisted.
 */
sealed interface CreateCategory {
    suspend operator fun invoke(name: String): Boolean

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val categoryRepository: CategoryRepository
    ) : CreateCategory {
        override suspend fun invoke(name: String): Boolean {
            val trimmed = name.trim()
            if (trimmed.isBlank()) return false
            categoryRepository.upsertCategory(Category(id = uuidV7(), name = Text(trimmed)))
            return true
        }
    }
}