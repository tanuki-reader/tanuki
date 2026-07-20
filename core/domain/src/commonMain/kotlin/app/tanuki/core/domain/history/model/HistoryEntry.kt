package app.tanuki.core.domain.history.model

import app.tanuki.core.domain.chapter.model.Chapter
import app.tanuki.core.domain.manga.model.Manga
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A [ChapterHistory] entry resolved for display: the raw record joined to the [Manga] it belongs to and
 * the [Chapter] that was read. This is what the Activity screen's read-history list renders — the VM
 * never has to resolve ids itself, keeping the join in the domain (as [ObserveReadHistory] does).
 *
 * @property id The originating [ChapterHistory.id] — a stable list key that survives re-emissions.
 * @property chapterId The read chapter's id, kept raw (not only via [chapter]) so the entry can be
 *   deleted even when the chapter has been pruned and [chapter] is `null`.
 * @property manga The series the read chapter belongs to (resolved from [ChapterHistory.mangaId]).
 * @property chapter The chapter that was read, or `null` if it has since been pruned from the manga's
 *   chapter list (the history row still shows, keyed by [manga] and [readAt]).
 * @property readAt When the chapter was read; drives the most-recent-first ordering and the row's
 *   relative-time caption.
 */
data class HistoryEntry(
    val id: Uuid,
    val chapterId: Uuid,
    val manga: Manga,
    val chapter: Chapter?,
    val readAt: Instant
)
