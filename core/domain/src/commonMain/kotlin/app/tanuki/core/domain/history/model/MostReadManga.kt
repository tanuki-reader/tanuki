package app.tanuki.core.domain.history.model

import app.tanuki.core.domain.manga.model.Manga

/**
 * One entry of a period's most-read leaderboard.
 *
 * @property previousRank where the series stood (1-based) on the previous period's leaderboard, or `null`
 *   when it wasn't read then — so the UI can say "Up 2" or "New" rather than show a bare arrow.
 */
data class MostReadManga(
    val manga: Manga,
    val readCount: Int,
    val previousRank: Int?
)
