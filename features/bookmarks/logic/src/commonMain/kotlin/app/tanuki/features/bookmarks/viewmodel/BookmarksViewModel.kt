package app.tanuki.features.bookmarks.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tanuki.core.domain.bookmark.model.PageBookmarkEntry
import app.tanuki.core.domain.bookmark.usecase.DeletePageBookmark
import app.tanuki.core.domain.bookmark.usecase.ObserveAllPageBookmarks
import app.tanuki.core.domain.chapter.model.PageImage
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.navigation.Reader
import app.tanuki.core.presentation.StateViewModel
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/**
 * Library → ⋯ → Bookmarks: every page bookmark across the library, grouped by series, newest first.
 * Opening one reads that page; deleting one is a swipe.
 */
@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
class BookmarksViewModel(
    observeAllPageBookmarks: ObserveAllPageBookmarks,
    private val deletePageBookmark: DeletePageBookmark,
    dispatcher: DispatcherProvider,
    logger: Logger
) : StateViewModel<BookmarksState>(dispatcher, logger, BookmarksState.Loading) {
    override val state: Flow<BookmarksState> =
        observeAllPageBookmarks().map { entries -> BookmarksState.Content(group(entries)) }

    /** Read the bookmarked page. */
    fun onOpen(row: BookmarkRow) = navigateTo(Reader(row.mangaId, row.chapterId, row.page))

    fun onDelete(id: Uuid) {
        viewModelScope.launch { tryIo("Could not delete a bookmark") { deletePageBookmark(id) } }
    }
}

/**
 * Groups [entries] — newest first, as the repository returns them — by series. A series sits where its
 * newest bookmark does, and keeps its own bookmarks newest first.
 */
internal fun group(entries: List<PageBookmarkEntry>): List<BookmarkSeries> =
    entries.groupBy { it.bookmark.mangaId }.map { (mangaId, marks) ->
        val first = marks.first()
        BookmarkSeries(
            mangaId = mangaId,
            title = first.mangaTitle.value,
            coverUrl = first.cover.url?.value,
            coverHeaders = first.cover.headers,
            bookmarks =
                marks.map { entry ->
                    val image = entry.bookmark.image
                    BookmarkRow(
                        id = entry.bookmark.id,
                        mangaId = mangaId,
                        chapterId = entry.bookmark.chapterId,
                        chapterNumberLabel = entry.chapterNumber?.display,
                        chapterName = entry.chapterName?.value,
                        page = entry.bookmark.page,
                        imageUrl = (image as? PageImage.Remote)?.url?.value,
                        imageHeaders = (image as? PageImage.Remote)?.headers.orEmpty(),
                        localPath = (image as? PageImage.Local)?.path
                    )
                }
        )
    }
