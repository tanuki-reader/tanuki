package app.tanuki.core.domain.bookmark.usecase

import app.tanuki.core.domain.bookmark.model.PageBookmarkEntry
import app.tanuki.core.domain.bookmark.repository.PageBookmarkRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow

/** Every page bookmark across the library, newest first (Library → Bookmarks). */
interface ObserveAllPageBookmarks {
    operator fun invoke(): Flow<List<PageBookmarkEntry>>

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val repository: PageBookmarkRepository,
    ) : ObserveAllPageBookmarks {
        override fun invoke(): Flow<List<PageBookmarkEntry>> = repository.observeAll()
    }
}
