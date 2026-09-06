package app.tanuki.core.domain.download.model

import app.tanuki.core.domain.chapter.model.Chapter
import app.tanuki.core.domain.manga.model.Manga

/**
 * What the queue is working on right now, as much of it as a notification can say.
 *
 * Carries whole domain models rather than pre-formatted text because everything a notification shows is
 * *localized* — "Chapter 4", "3 of 20 pages", "2 remaining" — and copy cannot be resolved in the domain
 * (see CLAUDE.md → Localization). The platform notifier holds the strings and does the formatting; this
 * only says which chapter of which series, and how far along.
 *
 * @property manga The series being fetched, for the notification's title.
 * @property chapter The chapter being fetched.
 * @property download Its queue record — where the page counts come from.
 * @property remaining How many chapters are still waiting *behind* this one. Zero on the last one, which
 *   is what lets a notification say "downloading the last chapter" rather than counting to itself.
 */
data class DownloadProgress(
    val manga: Manga,
    val chapter: Chapter,
    val download: ChapterDownload,
    val remaining: Int,
)