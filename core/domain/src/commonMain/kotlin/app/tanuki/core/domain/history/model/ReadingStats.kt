package app.tanuki.core.domain.history.model

import app.tanuki.core.domain.category.model.Category
import app.tanuki.core.domain.manga.model.Manga

/**
 * All-time reading aggregates for the Activity screen's overview — deliberately **not** scoped to a
 * [TimePeriod] (unlike the reading curve or the most-read leaderboard). These headline totals and the
 * per-series/-category breakdowns describe the user's whole reading history at a glance.
 *
 * @property totalChapters Total chapters ever read (every history row).
 * @property seriesCount Distinct series the user has read at least one chapter of.
 * @property dayStreak Consecutive days up to today on which at least one chapter was read (0 if nothing
 *   was read today or yesterday-onward — i.e. the streak is broken).
 * @property distribution Chapters read per series, most-read first — the source for the reading
 *   distribution chart. Covers every read series; the UI decides how many slices to show before folding
 *   the rest into an "other" bucket.
 * @property categoryShares Chapters read per library category, most-read first. A read counts toward
 *   **every** category its series belongs to, so shares may overlap (their sum can exceed [totalChapters]);
 *   reads of uncategorized series contribute to no share.
 */
data class ReadingStats(
    val totalChapters: Int,
    val seriesCount: Int,
    val dayStreak: Int,
    val distribution: List<MangaShare>,
    val categoryShares: List<CategoryShare>
) {
    /** One series' share of the reading distribution — [manga] read [count] times, all-time. */
    data class MangaShare(
        val manga: Manga,
        val count: Int
    )

    /** One category's share of reads — chapters of series in [category] read [count] times, all-time. */
    data class CategoryShare(
        val category: Category,
        val count: Int
    )

    companion object {
        val Empty =
            ReadingStats(
                totalChapters = 0,
                seriesCount = 0,
                dayStreak = 0,
                distribution = emptyList(),
                categoryShares = emptyList()
            )
    }
}
