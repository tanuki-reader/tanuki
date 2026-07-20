package app.tanuki.core.domain.chapter.model

/**
 * Reader progress for a chapter — mutable state owned by the user.
 *
 * This is the only part of [Chapter] that changes after the chapter is fetched from the source.
 * It is updated locally as the user reads and is never overwritten by source syncs.
 *
 * @property hasBookmark `true` when the user has bookmarked this chapter.
 * @property lastPageRead Zero-based index of the last page the user viewed.
 * @property alreadyRead `true` when the user has marked this chapter as read.
 *
 * @see Chapter
 */
data class ReadingProgress(
    val hasBookmark: Boolean,
    val lastPageRead: UInt,
    val alreadyRead: Boolean
)