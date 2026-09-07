package app.tanuki.core.domain.manga.service

import app.tanuki.core.domain.chapter.model.Chapter
import app.tanuki.core.domain.chapter.model.isHiddenBy
import app.tanuki.core.domain.manga.model.Manga
import app.tanuki.core.domain.manga.model.PublishStatus
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.offsetAt
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * How often a series should be polled, and when it is next worth polling.
 *
 * @property fetchInterval Days between this series' expected releases. Keeps the sign convention of
 *   [Manga.fetchInterval]: **negative** means the user pinned it and it was not recomputed.
 * @property nextUpdateAt When a library run should next consider the series.
 */
data class FetchSchedule(
    val fetchInterval: Int,
    val nextUpdateAt: Instant
)

/**
 * Works out how often a series actually publishes, and when to next check it.
 *
 * A library run that polls every series on the same clock spends nearly all of its requests confirming
 * that nothing changed — a series that finished in 2009 is asked exactly as often as a weekly ongoing
 * one. This derives a per-series cadence from the only evidence available for free: the release dates
 * of the chapters the caller already has in hand.
 *
 * A **pure function over chapters the caller already holds**, not a use case with a repository: its one
 * caller is [app.tanuki.core.domain.chapter.usecase.SyncSourceChapters], which has just merged the
 * chapter list and would otherwise have to re-read and re-materialize every row of it. On a long series
 * that is a thousand domain objects rebuilt per library run, per series, for two aggregates.
 *
 * ### The cadence is a median, not an average
 *
 * The gaps between releases are sorted and the middle one wins. A series that skipped a month for a
 * hiatus, or dropped a five-chapter batch in one afternoon, has one wild gap in its history — an
 * average would let that single gap set the schedule for the whole series, a median ignores it.
 *
 * ### Days, not hours
 *
 * Every comparison happens on **calendar days in the user's own time zone**, not on elapsed duration. A
 * weekly series that publishes Thursday morning and then Thursday evening is still weekly; measured in
 * hours it would look like an 8-day cadence and slowly drift off the day it actually releases on. This
 * is the whole point of the algorithm and is why it takes a [TimeZone].
 *
 * ### Anchored on the last *change*, not the last *poll*
 *
 * The schedule counts forward from the last time the chapter list actually gained something — the newest
 * [Chapter.dateFetch], since a sync stamps that on new chapters only and leaves existing ones alone.
 * [Manga.chaptersFetchedAt] is deliberately not used: it moves on every run, so anchoring on it would
 * push the next check forward forever and no series could ever decay to a slower cadence.
 *
 * Ported from Mihon's `FetchInterval`, with one deliberate correction — see [nextUpdateAfter].
 *
 * @param manga The series to schedule. Its [Manga.nextUpdateAt] and [Manga.fetchInterval] are read as
 *   the previous schedule, so pass the snapshot from *before* the sync.
 * @param chapters The series' chapter list as the sync leaves it.
 * @param now The sync's clock.
 */
fun fetchSchedule(
    manga: Manga,
    chapters: List<Chapter>,
    now: Instant,
    zone: TimeZone = TimeZone.currentSystemDefault()
): FetchSchedule {
    val interval =
        // A negative interval is the user's own choice (see [Manga.fetchInterval]); the whole point of
        // the sign is that nothing derived may overwrite it. Checked first so a pinned series never
        // pays for the scan below.
        manga.fetchInterval?.takeIf { it < 0 }
            ?: when (manga.publishStatus) {
                // A series that has ended will not publish again. Let it start at the floor rather than
                // spend months earning it one doubling at a time.
                PublishStatus.COMPLETED,
                PublishStatus.CANCELLED -> MAX_FETCH_INTERVAL

                // A group the user muted must not set the pace: if the only recent releases are ones
                // they never see, the series is dormant to them.
                else -> releaseInterval(chapters.visibleTo(manga.filteredScanlators), zone)
            }

    // Max over instants, then one conversion — the zone lookup is the expensive half, and comparing
    // instants needs none of it. Unfiltered on purpose: the list changed whoever scanlated it.
    val lastChange = chapters.maxOfOrNull(Chapter::dateFetch) ?: now

    return FetchSchedule(
        fetchInterval = interval,
        nextUpdateAt =
            nextUpdateAfter(
                previous = manga.nextUpdateAt,
                interval = interval,
                latestDay = lastChange.epochDayIn(zone),
                today = now.epochDayIn(zone),
                zone = zone,
                window = fetchWindow(now, zone)
            )
    )
}

/**
 * The grace window a library run schedules against: the start of today, ±1 day, in [zone].
 *
 * Its upper bound is what "due" means — a series whose next check falls anywhere today is checked today
 * rather than being pushed to tomorrow by a few hours; its lower bound is what keeps an
 * already-scheduled series from being rescheduled out from under the run that is about to check it.
 */
fun fetchWindow(
    now: Instant,
    zone: TimeZone = TimeZone.currentSystemDefault()
): ClosedRange<Instant> {
    val today = now.epochDayIn(zone)
    return startOfDay(today - GRACE_PERIOD_DAYS, zone)..(startOfDay(today + GRACE_PERIOD_DAYS, zone) - 1.milliseconds)
}

/**
 * Days between releases, as the median gap across this series' recent history, clamped to
 * `1..`[MAX_FETCH_INTERVAL].
 *
 * Looks at the last 3 release days for a short series and the last 10 for a longer one — enough to
 * describe the current cadence without letting a series' distant past outvote what it is doing now.
 *
 * [Chapter.dateUpload] is preferred because it is when the series actually published;
 * [Chapter.dateFetch] is the fallback for sources that omit upload dates, and describes when *we*
 * noticed, which is the same shape of signal one poll-interval blurrier. Either needs three distinct
 * days to give two gaps to take a median of; below that there is no cadence to speak of and the result
 * is [DEFAULT_INTERVAL] — and the fetch dates are only scanned when the upload dates came up short.
 *
 * Distinct **days**, not distinct chapters: a five-chapter batch dropped in one afternoon is one
 * release, and counting it as five would collapse the interval to the floor.
 */
internal fun releaseInterval(
    chapters: List<Chapter>,
    zone: TimeZone
): Int {
    // Three chapters cannot give three distinct days, so there is nothing to measure and nothing to
    // allocate. The common case for a series just added to the library.
    if (chapters.size < MIN_SAMPLE) return DEFAULT_INTERVAL

    val sampleSize = if (chapters.size <= SHORT_SERIES_CHAPTERS) SHORT_SERIES_SAMPLE else LONG_SERIES_SAMPLE
    return medianGap(recentDays(chapters, sampleSize, zone) { it.dateUpload })
        ?: medianGap(recentDays(chapters, sampleSize, zone) { it.dateFetch })
        ?: DEFAULT_INTERVAL
}

/**
 * The most recent [sampleSize] distinct calendar days among the chapters' [dateOf] dates, newest first.
 *
 * Epoch-day `Int`s in an [IntArray] rather than `LocalDate`s in a list, in the style of
 * [app.tanuki.core.domain.chapter.service.missingChaptersCount]: this runs once per series per library
 * update over the series' whole chapter list, and a long-running title has upwards of a thousand of
 * them. Sorting the days as primitives — rather than sorting instants and then decomposing each into a
 * year/month/day object — keeps the whole pass allocation-free apart from the two arrays.
 */
private inline fun recentDays(
    chapters: List<Chapter>,
    sampleSize: Int,
    zone: TimeZone,
    dateOf: (Chapter) -> Instant?
): IntArray {
    val days = IntArray(chapters.size)
    var found = 0
    for (chapter in chapters) {
        val at = dateOf(chapter) ?: continue
        days[found++] = at.epochDayIn(zone)
    }
    if (found < MIN_SAMPLE) return EMPTY_DAYS
    days.sort(fromIndex = 0, toIndex = found)

    // Walk down from the newest day, keeping only the ones we haven't seen — the array is sorted, so a
    // repeat is always the neighbour we just took.
    val recent = IntArray(minOf(sampleSize, found))
    var taken = 0
    var index = found - 1
    while (index >= 0 && taken < recent.size) {
        val day = days[index--]
        if (taken == 0 || recent[taken - 1] != day) recent[taken++] = day
    }
    return if (taken == recent.size) recent else recent.copyOf(taken)
}

/** The median gap between consecutive [days] (newest first), or `null` when there are too few. */
private fun medianGap(days: IntArray): Int? {
    if (days.size < MIN_SAMPLE) return null

    val gaps = IntArray(days.size - 1) { days[it] - days[it + 1] }
    gaps.sort()
    // The lower of the two middles on an even count, as the reference implementation does.
    return gaps[(gaps.size - 1) / 2].coerceIn(1, MAX_FETCH_INTERVAL)
}

/**
 * When to next check a series: the first multiple of its cadence, counted in whole days from
 * [latestDay], that lands after [today].
 *
 * A series nobody has touched in a long time has its *effective* step doubled — repeatedly, up to
 * [MAX_FETCH_INTERVAL] — once it has been checked more than [INCREASE_WHEN_OVER] times without the list
 * changing. That is what lets a dead weekly series decay toward monthly polling instead of being asked
 * every seven days forever. A user-pinned (negative) interval is exempt: they chose the number.
 *
 * The stored [FetchSchedule.fetchInterval] stays the *derived* cadence, not the doubled step, so a
 * series that revives is back on its real schedule as soon as one new chapter lands.
 *
 * **Deviation from Mihon:** its `calculateNextUpdate` divides by the increased step but then adds
 * multiples of the *base* interval, which makes a stale series come due sooner rather than later — the
 * opposite of what the doubling is for. Here the increased step is used on both sides.
 */
private fun nextUpdateAfter(
    previous: Instant?,
    interval: Int,
    latestDay: Int,
    today: Int,
    zone: TimeZone,
    window: ClosedRange<Instant>
): Instant {
    // Already scheduled for today (give or take the grace period): leave it alone rather than move a
    // series in or out of the run that is happening right now.
    if (previous != null && previous in window) return previous

    // Never negative: a clock that went backwards would otherwise schedule the series into the past.
    val daysSinceLatest = (today - latestDay).coerceAtLeast(0)
    val step = if (interval < 0) -interval else increaseInterval(interval, daysSinceLatest)
    val cycles = daysSinceLatest.floorDiv(step)
    return startOfDay(latestDay + (cycles + 1) * step, zone)
}

/**
 * Doubles [delta] until a series [daysSinceLatest] days stale would have been checked no more than
 * [INCREASE_WHEN_OVER] times at that step, capped at [MAX_FETCH_INTERVAL].
 */
private tailrec fun increaseInterval(
    delta: Int,
    daysSinceLatest: Int
): Int {
    if (delta >= MAX_FETCH_INTERVAL) return MAX_FETCH_INTERVAL
    val cycles = daysSinceLatest.floorDiv(delta) + 1
    return if (cycles > INCREASE_WHEN_OVER) increaseInterval(delta * 2, daysSinceLatest) else delta
}

/**
 * Which calendar day in [zone] this instant falls on, as a day number.
 *
 * Deliberately not `toLocalDateTime(zone).date`: that resolves the same offset and then goes on to
 * decompose it into a year, month, day and time of day and allocate two objects for them, none of which
 * this algorithm looks at. Only the day *number* matters, and gaps between day numbers are subtraction.
 */
private fun Instant.epochDayIn(zone: TimeZone): Int =
    (epochSeconds + zone.offsetAt(this).totalSeconds).floorDiv(SECONDS_PER_DAY).toInt()

/**
 * The inverse, for the handful of day numbers that become timestamps again.
 *
 * Goes through [LocalDate] rather than multiplying back out, because the first instant of a day is not
 * always midnight — Brazil, one of this app's two locales, used to begin daylight saving at midnight,
 * and on those dates the day starts an hour in. There are two of these per schedule against one per
 * chapter, so the careful conversion belongs here and the cheap one belongs in [epochDayIn].
 */
private fun startOfDay(
    epochDay: Int,
    zone: TimeZone
): Instant = LocalDate.fromEpochDays(epochDay).atStartOfDayIn(zone)

/** Chapters the user has not hidden — the receiver itself when they have hidden nothing. */
private fun List<Chapter>.visibleTo(filteredScanlators: Set<String>): List<Chapter> =
    if (filteredScanlators.isEmpty()) this else filterNot { it.isHiddenBy(filteredScanlators) }

/** The slowest a series is ever polled, in days. */
internal const val MAX_FETCH_INTERVAL = 28

/** What a series with too little history to read a cadence from gets, in days. */
private const val DEFAULT_INTERVAL = 7

/** Release days needed before a median means anything — two gaps. */
private const val MIN_SAMPLE = 3

private const val SHORT_SERIES_CHAPTERS = 8
private const val SHORT_SERIES_SAMPLE = 3
private const val LONG_SERIES_SAMPLE = 10

/** Missed checks at the current step before it is doubled. */
private const val INCREASE_WHEN_OVER = 10

private const val GRACE_PERIOD_DAYS = 1

private const val SECONDS_PER_DAY = 86_400L

private val EMPTY_DAYS = IntArray(0)
