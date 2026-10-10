package app.tanuki.core.presentation

import kotlin.time.Clock
import kotlin.time.Instant

/** The unit of a relative "… ago" caption. [JustNow] has no count. */
enum class ElapsedUnit { JustNow, Minutes, Hours, Days, Weeks }

/**
 * How long ago something happened, bucketed for a relative caption: "Just now", "5 min ago", "3 h ago",
 * "2 days ago", "4 weeks ago". State carries this meaning rather than text, and each platform maps
 * [unit] to its own localized plural with [count].
 */
data class Elapsed(
    val unit: ElapsedUnit,
    val count: Int
)

/**
 * Buckets the time since [instant]. Coarse on purpose: weeks is the widest bucket, enough for things the
 * user is expected to read soon.
 */
fun elapsedSince(
    instant: Instant,
    now: Instant = Clock.System.now()
): Elapsed {
    val elapsed = now - instant
    val minutes = elapsed.inWholeMinutes.toInt()
    val hours = elapsed.inWholeHours.toInt()
    val days = elapsed.inWholeDays.toInt()
    return when {
        minutes < 1 -> Elapsed(ElapsedUnit.JustNow, 0)
        minutes < 60 -> Elapsed(ElapsedUnit.Minutes, minutes)
        hours < 24 -> Elapsed(ElapsedUnit.Hours, hours)
        days < 7 -> Elapsed(ElapsedUnit.Days, days)
        else -> Elapsed(ElapsedUnit.Weeks, days / 7)
    }
}
