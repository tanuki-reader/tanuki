package app.tanuki.core.domain.bookmark.usecase

import app.tanuki.core.domain.bookmark.model.PageBookmark
import app.tanuki.core.domain.bookmark.repository.PageBookmarkRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

/** One series' page bookmarks (the reader's Bookmarks tab, Manga detail's markers and filter). */
interface ObservePageBookmarks {
    operator fun invoke(mangaId: Uuid): Flow<List<PageBookmark>>

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val repository: PageBookmarkRepository,
    ) : ObservePageBookmarks {
        override fun invoke(mangaId: Uuid): Flow<List<PageBookmark>> = repository.observeForManga(mangaId)
    }
}
