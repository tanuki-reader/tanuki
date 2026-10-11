package app.tanuki.core.domain.bookmark.usecase

import app.tanuki.core.domain.bookmark.model.PageBookmark
import app.tanuki.core.domain.bookmark.repository.PageBookmarkRepository
import app.tanuki.core.domain.chapter.model.PageImage
import app.tanuki.core.domain.common.service.uuidV7
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Bookmarks [page] of [chapterId], or removes the bookmark already there. Returns whether the page is
 * bookmarked afterwards. [image] is kept for the bookmark's thumbnail.
 */
interface TogglePageBookmark {
    suspend operator fun invoke(
        mangaId: Uuid,
        chapterId: Uuid,
        page: Int,
        image: PageImage?,
    ): Boolean

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val repository: PageBookmarkRepository,
    ) : TogglePageBookmark {
        override suspend fun invoke(
            mangaId: Uuid,
            chapterId: Uuid,
            page: Int,
            image: PageImage?,
        ): Boolean {
            if (repository.removeAt(chapterId, page)) return false
            repository.add(PageBookmark(uuidV7(), mangaId, chapterId, page, Clock.System.now(), image))
            return true
        }
    }
}
