package app.tanuki.core.domain.update.model

import app.tanuki.core.domain.chapter.model.Chapter
import app.tanuki.core.domain.manga.model.Manga
import kotlin.time.Instant

/**
 * A [ChapterUpdate] resolved for display: the chapter that arrived, joined to the series it belongs to.
 * What Home's "New Chapters" list renders — the ViewModel never resolves ids itself, keeping the join in
 * the domain exactly as [app.tanuki.core.domain.history.model.HistoryEntry] does for read history.
 *
 * @property chapter The chapter that arrived. Its [Chapter.progress] is `null` by construction — an
 *   opened chapter is no longer a pending update and is filtered out upstream (see
 *   [app.tanuki.core.domain.update.usecase.ObserveNewChapters]).
 * @property manga The series it belongs to, for the title and cover the row is drawn from.
 * @property discoveredAt When the update was recorded; drives ordering and the row's relative caption.
 */
data class NewChapter(
    val chapter: Chapter,
    val manga: Manga,
    val discoveredAt: Instant
)