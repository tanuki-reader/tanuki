package app.tanuki.core.domain.update.model

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A record that a chapter arrived *after* the user was already following its series — the raw fact the
 * "New Chapters" list and the update notification are both built from.
 *
 * Local-only state kept beside the source-owned chapter (like
 * [app.tanuki.core.domain.chapter.model.ReadingProgress]), rather than a column on the chapter itself,
 * so a source sync can never write or clobber it. Written only by
 * [app.tanuki.core.domain.chapter.usecase.SyncSourceChapters], and only for a chapter it genuinely
 * inserted into a library series that already had chapters — the *first* sync of a series is a baseline
 * that establishes what "already there" means, not a hundred updates.
 *
 * Deliberately **not** a "seen" flag: whether the user has looked at a chapter is already recorded by
 * its [app.tanuki.core.domain.chapter.model.ReadingProgress] row, which the reader writes the moment a
 * chapter opens. [notified] is the one piece of state that has no other home — it is about the
 * *notification*, not the chapter, and it exists so a run that is killed (or whose notification is
 * suppressed) doesn't silently lose the updates it found, nor announce them twice.
 *
 * @property chapterId The chapter that arrived; also this record's identity, so a chapter can only be
 *   an update once.
 * @property mangaId The series it belongs to, carried so updates can be grouped and cleared per series
 *   without resolving the chapter first.
 * @property discoveredAt When the sync that inserted the chapter ran. Drives the newest-first ordering,
 *   and is distinct from the chapter's own `dateUpload` (when the source published it) — a series can
 *   publish a chapter long before the user's library learns about it.
 * @property notified Whether the user has already been told about this chapter.
 */
data class ChapterUpdate(
    val chapterId: Uuid,
    val mangaId: Uuid,
    val discoveredAt: Instant,
    val notified: Boolean = false
)