package app.tanuki.core.domain.download.model

import app.tanuki.core.domain.chapter.model.Chapter
import app.tanuki.core.domain.manga.model.Manga

/**
 * One series' share of the download queue: the series itself, and the chapters of it the queue still has
 * something to say about.
 *
 * Grouped in the domain rather than in the screen because the grouping is not a layout choice — it is
 * what makes the queue *readable*. A flat list of chapters queued from three series interleaves them by
 * time, and "Chapter 12" three rows below another "Chapter 12" is unreadable without the series beside
 * it. The series is also the unit every bulk action here works on.
 *
 * @property manga The series, for the header's cover and title.
 * @property chapters Its queued chapters in the order the engine will work them — oldest-queued first,
 *   the same order the flat queue has. Never empty: a series with nothing queued is absent rather than
 *   present-and-empty, so the screen never has to render a header over nothing.
 */
data class DownloadQueueSeries(
    val manga: Manga,
    val chapters: List<QueuedChapter>,
) {
    /** How many of this series' queued chapters have stopped with an error. */
    val failedCount: Int get() = chapters.count { it.download.state == DownloadState.Failed }
}

/**
 * One row of the queue: a chapter and what the queue knows about it.
 *
 * The chapter is carried whole rather than reduced to a label because the row's title follows the same
 * rules as the chapter list's ("Chapter 4", else the source's name, else a placeholder), and those rules
 * are localized — so they belong to the composable that renders them, which needs the chapter to apply
 * them.
 *
 * @property chapter The chapter being fetched.
 * @property download Its queue record — the state, the progress, and the reason it stopped.
 */
data class QueuedChapter(
    val chapter: Chapter,
    val download: ChapterDownload,
)