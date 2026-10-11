package app.tanuki.features.home.viewmodel

import app.tanuki.core.domain.chapter.model.Chapter
import app.tanuki.core.domain.chapter.model.ChapterNumber
import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.common.model.Url
import app.tanuki.core.domain.manga.model.Manga
import app.tanuki.core.domain.manga.model.MangaCover
import app.tanuki.core.domain.manga.model.PublishStatus
import app.tanuki.core.domain.manga.model.ReadingMode
import app.tanuki.core.domain.update.model.NewChapter
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** New chapters merge like the Activity history: one row per series per day of discovery. */
class NewChapterRowsTest {
    private val zone = TimeZone.UTC

    @Test
    fun `a batch of one series on one day is one row that opens its earliest chapter`() {
        val eleceed = manga("Eleceed")
        val kingdom = manga("Kingdom")
        // Newest first, as the domain lists them.
        val pending =
            listOf(
                new(eleceed, 421.0, at(10, 11, 9)),
                new(kingdom, 860.0, at(10, 11, 8)),
                new(eleceed, 418.0, at(10, 11, 7)),
                new(eleceed, 419.0, at(10, 11, 6)),
                new(eleceed, 417.0, at(10, 10, 9))
            )

        val rows = pending.toItems(zone)

        assertEquals(listOf("Eleceed", "Kingdom", "Eleceed"), rows.map { it.title })
        val batch = rows.first()
        assertEquals(3, batch.chapterCount)
        assertEquals("418" to "421", batch.firstChapter to batch.lastChapter)
        assertEquals(pending[2].chapter.id, batch.chapterId)
        assertEquals(1, rows.last().chapterCount)
    }

    private fun at(
        month: Int,
        day: Int,
        hour: Int
    ): Instant = LocalDateTime(2026, month, day, hour, 0).toInstant(zone)

    private fun manga(title: String): Manga {
        val id = Uuid.random()
        return Manga(
            id = id,
            source = Uuid.random(),
            title = Text(title),
            author = null,
            description = null,
            cover = MangaCover(id, null),
            url = Url("https://example.com/$title"),
            dateAdded = null,
            readingMode = ReadingMode.RIGHT_TO_LEFT,
            publishStatus = PublishStatus.ONGOING,
            readingStatus = null
        )
    }

    private fun new(
        manga: Manga,
        number: Double,
        discoveredAt: Instant
    ) = NewChapter(
        chapter =
            Chapter(
                id = Uuid.random(),
                mangaId = manga.id,
                chapterNumber = ChapterNumber(number),
                name = null,
                scanlator = null,
                sourceChapterKey = null,
                url = Url("https://example.com/$number"),
                dateUpload = null,
                dateFetch = discoveredAt,
                progress = null
            ),
        manga = manga,
        discoveredAt = discoveredAt
    )
}
