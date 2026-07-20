package app.tanuki.core.domain.history.model

import app.tanuki.core.domain.manga.model.Manga

/**
 * A manga ranked by how many chapters of it the user read within a [TimePeriod] — one row of the
 * Activity screen's "most read" leaderboard.
 *
 * @property manga The series itself (resolved from the read history's denormalized `mangaId`).
 * @property readCount Chapters of [manga] read within the period; the ranking key (higher ranks first).
 * @property trend How this period's [readCount] compares to the previous equivalent period; see [ReadTrend].
 */
data class MostReadManga(
    val manga: Manga,
    val readCount: Int,
    val trend: ReadTrend
) {
    /** Direction of a manga's read count this period versus the previous one. */
    enum class ReadTrend {
        /** Read more this period than last. */
        UP,

        /** Read less this period than last. */
        DOWN,

        /** Read the same amount (including newly read with no prior history). */
        FLAT
    }
}