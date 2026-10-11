package app.tanuki.core.domain.bookmark.model

import app.tanuki.core.domain.chapter.model.ChapterNumber
import app.tanuki.core.domain.chapter.model.PageImage
import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.manga.model.MangaCover
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A page the user marked to come back to. At most one per page of a chapter.
 *
 * @property page Zero-based page within the chapter.
 * @property image Where that page's image was when it was marked, for a thumbnail. A source's URL can
 *   expire (some sign theirs), so a thumbnail may fail to load; the UI falls back to the cover. `null`
 *   when nothing was known.
 */
data class PageBookmark(
    val id: Uuid,
    val mangaId: Uuid,
    val chapterId: Uuid,
    val page: Int,
    val createdAt: Instant,
    val image: PageImage?,
)

/** A bookmark with what a list across series shows beside it. */
data class PageBookmarkEntry(
    val bookmark: PageBookmark,
    val mangaTitle: Text,
    val cover: MangaCover,
    val chapterNumber: ChapterNumber?,
    val chapterName: Text?,
)
