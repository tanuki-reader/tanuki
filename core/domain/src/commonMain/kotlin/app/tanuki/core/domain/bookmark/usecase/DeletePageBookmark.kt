package app.tanuki.core.domain.bookmark.usecase

import app.tanuki.core.domain.bookmark.repository.PageBookmarkRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlin.uuid.Uuid

interface DeletePageBookmark {
    suspend operator fun invoke(id: Uuid)

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val repository: PageBookmarkRepository,
    ) : DeletePageBookmark {
        override suspend fun invoke(id: Uuid) = repository.remove(id)
    }
}
