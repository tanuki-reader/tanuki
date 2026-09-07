package app.tanuki.core.domain.manga.service

import app.tanuki.core.domain.chapter.model.Chapter
import app.tanuki.core.domain.chapter.model.ChapterNumber
import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.common.model.Url
import app.tanuki.core.domain.common.service.uuidV7
import app.tanuki.core.domain.manga.model.Manga
import app.tanuki.core.domain.manga.model.MangaCover
import app.tanuki.core.domain.manga.model.PublishStatus
import app.tanuki.core.domain.manga.model.ReadingMode
import app.tanuki.core.domain.manga.model.ReadingStatus
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Tests [fetchSchedule] and the [releaseInterval] median it is built on.
 *
 * Everything runs against a fixed [TimeZone.UTC] and fixed instants: the whole algorithm is calendar-day
 * arithmetic, so a test that took the machine's own zone would pass or fail depending on where it ran.
 */
class FetchIntervalTest {
    // --- cadence ---

    @Test
    fun `a weekly series is polled weekly`() {
        assertEquals(7, releaseInterval(uploadedOn(0, 7, 14, 21), TimeZone.UTC))
    }

    @Test
    fun `a daily series is polled daily`() {
        assertEquals(1, releaseInterval(uploadedOn(0, 1, 2, 3, 4), TimeZone.UTC))
    }

    @Test
    fun `one long gap does not move the cadence`() {
        // A hiatus between the third and fourth release. The mean would read ~33 days; the median reads
        // what the series actually does when it is publishing.
        assertEquals(7, releaseInterval(uploadedOn(0, 7, 14, 100), TimeZone.UTC))
    }

    @Test
    fun `chapters dropped on one day count as one release`() {
        // Three at once on day 0 is a batch, not three days of a one-day cadence.
        assertEquals(7, releaseInterval(uploadedOn(0, 0, 0, 7, 14), TimeZone.UTC))
    }

    @Test
    fun `different times of the same day are still one release`() {
        val midnight = day(0)
        val chapters =
            listOf(
                chapter(uploadedAt = midnight),
                chapter(uploadedAt = midnight + 9.hours),
                chapter(uploadedAt = midnight + 23.hours),
                chapter(uploadedAt = day(7)),
                chapter(uploadedAt = day(14))
            )

        assertEquals(7, releaseInterval(chapters, TimeZone.UTC))
    }

    @Test
    fun `a glacial series is capped at the maximum`() {
        assertEquals(MAX_FETCH_INTERVAL, releaseInterval(uploadedOn(0, 90, 180), TimeZone.UTC))
    }

    @Test
    fun `a series with too little history falls back to the default`() {
        assertEquals(7, releaseInterval(uploadedOn(0, 7), TimeZone.UTC))
    }

    @Test
    fun `a series with no chapters falls back to the default`() {
        assertEquals(7, releaseInterval(emptyList(), TimeZone.UTC))
    }

    @Test
    fun `a source that publishes no upload dates is read from when we fetched`() {
        val chapters = listOf(0, 3, 6, 9).map { chapter(uploadedAt = null, fetchedAt = day(it)) }

        assertEquals(3, releaseInterval(chapters, TimeZone.UTC))
    }

    @Test
    fun `too few upload dates falls through to the fetch dates`() {
        // Two dated chapters is not a cadence; the four fetch days behind them are.
        val chapters =
            listOf(
                chapter(uploadedAt = day(9), fetchedAt = day(9)),
                chapter(uploadedAt = day(6), fetchedAt = day(6)),
                chapter(uploadedAt = null, fetchedAt = day(3)),
                chapter(uploadedAt = null, fetchedAt = day(0))
            )

        assertEquals(3, releaseInterval(chapters, TimeZone.UTC))
    }

    @Test
    fun `a short series is read from its three most recent releases`() {
        // Eight chapters: monthly long ago, weekly now. Only the recent three days are sampled.
        assertEquals(7, releaseInterval(uploadedOn(0, 30, 60, 86, 93, 100, 100, 100), TimeZone.UTC))
    }

    @Test
    fun `a longer series is read from a wider window`() {
        // The same history plus one chapter. Past eight, the sample widens to ten days and the older
        // monthly stretch pulls the median up.
        assertEquals(26, releaseInterval(uploadedOn(0, 30, 60, 86, 93, 100, 100, 100, 100), TimeZone.UTC))
    }

    @Test
    fun `the order chapters arrive in does not matter`() {
        // They come off the DAO ordered by chapter number, which is not date order — a source that
        // backfills an old chapter must not look like a one-day cadence.
        val ascending = uploadedOn(0, 7, 14, 21)

        assertEquals(
            releaseInterval(ascending, TimeZone.UTC),
            releaseInterval(ascending.reversed(), TimeZone.UTC)
        )
    }

    // --- scheduling ---

    @Test
    fun `a weekly series is scheduled a week after its last chapter arrived`() {
        val schedule = fetchSchedule(manga(), weeklyUpTo(NOW), NOW, TimeZone.UTC)

        assertEquals(7, schedule.fetchInterval)
        assertEquals(Instant.parse("2026-03-08T00:00:00Z"), schedule.nextUpdateAt)
    }

    @Test
    fun `a series nobody has touched in months is pushed further out`() {
        // Weekly cadence, but the list has not changed in 200 days: the effective step doubles 7 → 14 →
        // 28 until a series this stale is not being asked twice a fortnight for nothing.
        val schedule = fetchSchedule(manga(), weeklyUpTo(NOW - 200.days), NOW, TimeZone.UTC)

        // The derived cadence is still the real one — a revival puts it straight back on weekly.
        assertEquals(7, schedule.fetchInterval)
        assertEquals(Instant.parse("2026-03-25T00:00:00Z"), schedule.nextUpdateAt)
        assertTrue(schedule.nextUpdateAt > NOW + 7.days, "a stale series must not come due sooner")
    }

    @Test
    fun `a finished series starts at the slowest cadence`() {
        val chapters = weeklyUpTo(NOW)

        assertEquals(
            MAX_FETCH_INTERVAL,
            fetchSchedule(manga(publishStatus = PublishStatus.COMPLETED), chapters, NOW, TimeZone.UTC).fetchInterval
        )
        assertEquals(
            MAX_FETCH_INTERVAL,
            fetchSchedule(manga(publishStatus = PublishStatus.CANCELLED), chapters, NOW, TimeZone.UTC).fetchInterval
        )
    }

    @Test
    fun `a hiatus is still read from its release history`() {
        // Only a series that has *ended* gets the floor handed to it; a paused one may resume.
        val schedule = fetchSchedule(manga(publishStatus = PublishStatus.HIATUS), weeklyUpTo(NOW), NOW, TimeZone.UTC)

        assertEquals(7, schedule.fetchInterval)
    }

    @Test
    fun `an interval the user pinned is never recomputed`() {
        val schedule = fetchSchedule(manga(fetchInterval = -3), weeklyUpTo(NOW), NOW, TimeZone.UTC)

        // Kept sign and all: the sign is what marks it as theirs.
        assertEquals(-3, schedule.fetchInterval)
        assertEquals(Instant.parse("2026-03-04T00:00:00Z"), schedule.nextUpdateAt)
    }

    @Test
    fun `a series already due today keeps the day it was given`() {
        val alreadyScheduled = Instant.parse("2026-03-01T00:00:00Z")
        val schedule = fetchSchedule(manga(nextUpdateAt = alreadyScheduled), weeklyUpTo(NOW), NOW, TimeZone.UTC)

        assertEquals(alreadyScheduled, schedule.nextUpdateAt)
    }

    @Test
    fun `a series due long ago is given a new day`() {
        val schedule =
            fetchSchedule(manga(nextUpdateAt = NOW - 60.days), weeklyUpTo(NOW), NOW, TimeZone.UTC)

        assertEquals(Instant.parse("2026-03-08T00:00:00Z"), schedule.nextUpdateAt)
    }

    @Test
    fun `a scanlator the user hid does not set the pace`() {
        // The series they read is monthly; a group they muted also posts it weekly.
        val visible = listOf(0, 30, 60).map { chapter(uploadedAt = day(it), scanlator = "Slow Scans") }
        val hidden = listOf(50, 57, 64).map { chapter(uploadedAt = day(it), scanlator = "Fast Scans") }

        val schedule =
            fetchSchedule(manga(filteredScanlators = setOf("Fast Scans")), visible + hidden, NOW, TimeZone.UTC)

        assertEquals(28, schedule.fetchInterval)
    }

    @Test
    fun `a hidden chapter still counts as the list having changed`() {
        // The filter decides the *pace*, not whether anything happened: a chapter landing is a chapter
        // landing, so the next check is counted from it either way.
        val chapters = listOf(chapter(uploadedAt = day(0), fetchedAt = NOW, scanlator = "Fast Scans"))

        val schedule =
            fetchSchedule(manga(filteredScanlators = setOf("Fast Scans")), chapters, NOW, TimeZone.UTC)

        assertEquals(Instant.parse("2026-03-08T00:00:00Z"), schedule.nextUpdateAt)
    }

    @Test
    fun `a series with no chapters at all is scheduled from the default`() {
        val schedule = fetchSchedule(manga(), chapters = emptyList(), now = NOW, zone = TimeZone.UTC)

        assertEquals(7, schedule.fetchInterval)
        assertEquals(Instant.parse("2026-03-08T00:00:00Z"), schedule.nextUpdateAt)
    }

    @Test
    fun `a chapter dated in the future does not schedule the series into the past`() {
        // A source with a skewed clock, or a device whose own clock went backwards.
        val chapters = listOf(chapter(uploadedAt = NOW + 5.days, fetchedAt = NOW + 5.days))

        val schedule = fetchSchedule(manga(), chapters, NOW, TimeZone.UTC)

        assertTrue(schedule.nextUpdateAt > NOW, "next check must never land behind the clock")
    }

    // --- the run's window ---

    @Test
    fun `the window spans yesterday to the end of tomorrow`() {
        val window = fetchWindow(NOW, TimeZone.UTC)

        assertEquals(Instant.parse("2026-02-28T00:00:00Z"), window.start)
        assertEquals(Instant.parse("2026-03-02T00:00:00Z") - 1.milliseconds, window.endInclusive)
        assertTrue(NOW in window)
    }

    // --- helpers ---

    /** Four weekly chapters, the newest uploaded and fetched on [latest]. */
    private fun weeklyUpTo(latest: Instant) =
        List(4) { index ->
            val at = latest - (index * 7).days
            chapter(uploadedAt = at, fetchedAt = at)
        }

    private fun uploadedOn(vararg offsets: Int) = offsets.map { chapter(uploadedAt = day(it)) }

    private fun day(offset: Int) = EPOCH_DAY.plus(offset, DateTimeUnit.DAY).atStartOfDayIn(TimeZone.UTC)

    private fun chapter(
        uploadedAt: Instant?,
        fetchedAt: Instant = uploadedAt ?: day(0),
        scanlator: String? = null
    ) = Chapter(
        id = uuidV7(),
        mangaId = MANGA,
        chapterNumber = ChapterNumber.orNull(1.0),
        name = null,
        scanlator = scanlator?.let(::Text),
        sourceChapterKey = Text(uuidV7().toString()),
        url = Url("https://mangadex.org/chapter"),
        dateUpload = uploadedAt,
        dateFetch = fetchedAt,
        progress = null
    )

    private fun manga(
        publishStatus: PublishStatus = PublishStatus.ONGOING,
        fetchInterval: Int? = null,
        nextUpdateAt: Instant? = null,
        filteredScanlators: Set<String> = emptySet()
    ) = Manga(
        id = MANGA,
        source = uuidV7(),
        title = Text("Blue Lock"),
        author = null,
        description = null,
        cover = MangaCover(mangaId = MANGA, url = null, lastModified = day(0)),
        sourceMangaKey = Text("blue-lock"),
        url = Url("https://mangadex.org/title/blue-lock"),
        dateAdded = day(0),
        readingMode = ReadingMode.LEFT_TO_RIGHT,
        publishStatus = publishStatus,
        readingStatus = ReadingStatus.READING,
        filteredScanlators = filteredScanlators,
        nextUpdateAt = nextUpdateAt,
        fetchInterval = fetchInterval
    )

    private companion object {
        val MANGA = uuidV7()
        val EPOCH_DAY = LocalDate(2026, 1, 1)
        val NOW = Instant.parse("2026-03-01T09:00:00Z")
    }
}
