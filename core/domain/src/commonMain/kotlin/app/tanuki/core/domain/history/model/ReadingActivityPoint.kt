package app.tanuki.core.domain.history.model

import kotlinx.datetime.LocalDate

/**
 * One point on the Activity screen's reading curve: how many chapters the user read in a single bucket
 * of a [TimePeriod]. The bucket granularity is chosen by [GetReadingActivity] — a **day** for
 * [TimePeriod.WEEK]/[TimePeriod.MONTH], a **month** for [TimePeriod.YEAR] (so a year stays 12 points,
 * not 365).
 *
 * The series is **gap-filled**: buckets with no reads are emitted with [count] `0`, so the curve is a
 * continuous line over the whole period rather than a sparse scatter.
 *
 * @property date The bucket's key — the day, or the first of the month for year buckets. UI copy (axis
 *   labels) is formatted from this in the presentation layer, never here.
 * @property count Chapters read within the bucket; the plotted value.
 */
data class ReadingActivityPoint(
    val date: LocalDate,
    val count: Int
)