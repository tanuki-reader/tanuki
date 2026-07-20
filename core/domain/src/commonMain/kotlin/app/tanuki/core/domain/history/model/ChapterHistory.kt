package app.tanuki.core.domain.history.model

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A reading-history entry: a record that the user opened a particular chapter.
 *
 * Drives the "recently read" / "continue reading" surfaces. [mangaId] is denormalized alongside
 * [chapterId] — although it could be derived by joining through the chapter — so the history can be
 * grouped and queried per manga without that join.
 *
 * @property id Local persistence identity for this history entry.
 * @property chapterId Local id of the chapter that was read.
 * @property mangaId Local id of the chapter's owning manga (denormalized for per-manga queries).
 * @property readAt When the chapter was last read;
 */
data class ChapterHistory(
    val id: Uuid,
    val chapterId: Uuid,
    val mangaId: Uuid,
    val readAt: Instant
)