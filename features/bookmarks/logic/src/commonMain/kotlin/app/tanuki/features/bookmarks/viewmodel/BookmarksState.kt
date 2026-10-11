package app.tanuki.features.bookmarks.viewmodel

import kotlin.uuid.Uuid

/** Every page bookmark, grouped by series. Plain values only, so SwiftUI and Compose draw the same state. */
sealed interface BookmarksState {
    data object Loading : BookmarksState

    /** [series] newest first: by each series' newest bookmark. Empty when nothing is bookmarked. */
    data class Content(
        val series: List<BookmarkSeries>
    ) : BookmarksState
}

/** One series and its bookmarks, newest first. */
data class BookmarkSeries(
    val mangaId: Uuid,
    val title: String,
    val coverUrl: String?,
    val coverHeaders: Map<String, String>,
    val bookmarks: List<BookmarkRow>
)

/**
 * One bookmarked page. [page] is zero-based (shown plus one); [chapterNumberLabel] ("12") or
 * [chapterName] title the row. The thumbnail is [imageUrl] + [imageHeaders] or [localPath]; a source's
 * URL may have expired, and the row then shows the cover's ground.
 */
data class BookmarkRow(
    val id: Uuid,
    val mangaId: Uuid,
    val chapterId: Uuid,
    val chapterNumberLabel: String?,
    val chapterName: String?,
    val page: Int,
    val imageUrl: String?,
    val imageHeaders: Map<String, String>,
    val localPath: String?
)
