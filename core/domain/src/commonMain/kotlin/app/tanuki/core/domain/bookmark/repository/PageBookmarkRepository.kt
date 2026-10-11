package app.tanuki.core.domain.bookmark.repository

import app.tanuki.core.domain.bookmark.model.PageBookmark
import app.tanuki.core.domain.bookmark.model.PageBookmarkEntry
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

/** Page bookmarks. A chapter counts as bookmarked when it has at least one. */
interface PageBookmarkRepository {
    /** One series' bookmarks, by chapter then page. */
    fun observeForManga(mangaId: Uuid): Flow<List<PageBookmark>>

    /** Every bookmark with its series and chapter, newest first. */
    fun observeAll(): Flow<List<PageBookmarkEntry>>

    /** Ids of [mangaId]'s chapters that have a bookmark. */
    suspend fun bookmarkedChapterIds(mangaId: Uuid): Set<Uuid>

    /** Adds [bookmark], replacing one already on the same page. */
    suspend fun add(bookmark: PageBookmark)

    /** Removes the bookmark on [page] of [chapterId]; returns whether there was one. */
    suspend fun removeAt(
        chapterId: Uuid,
        page: Int,
    ): Boolean

    suspend fun remove(id: Uuid)
}
