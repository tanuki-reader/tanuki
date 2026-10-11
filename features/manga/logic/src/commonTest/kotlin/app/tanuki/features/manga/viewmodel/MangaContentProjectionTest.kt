package app.tanuki.features.manga.viewmodel

import app.tanuki.core.domain.chapter.model.Chapter
import app.tanuki.core.domain.chapter.model.ChapterNumber
import app.tanuki.core.domain.chapter.model.ReadingProgress
import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.common.model.Url
import app.tanuki.core.domain.download.model.ChapterDownload
import app.tanuki.core.domain.download.model.DownloadState
import app.tanuki.core.domain.manga.model.Manga
import app.tanuki.core.domain.manga.model.MangaCover
import app.tanuki.core.domain.manga.model.PublishStatus
import app.tanuki.core.domain.manga.model.ReadingMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The plain-value views Swift renders: they must say what the domain state says. */
class MangaContentProjectionTest {
    private val mangaId = Uuid.random()
    private val manga =
        Manga(
            id = mangaId,
            source = Uuid.random(),
            title = Text("Absolute Regression"),
            author = Text("JP"),
            description = Text("Murim's skies"),
            cover = MangaCover(mangaId, Url("https://example.com/c.jpg")),
            url = Url("https://example.com/m"),
            dateAdded = Instant.DISTANT_PAST,
            readingMode = ReadingMode.WEBTOON,
            publishStatus = PublishStatus.ONGOING,
            readingStatus = null
        )

    private fun chapter(
        number: Double,
        progress: ReadingProgress? = null
    ) = Chapter(
        id = Uuid.random(),
        mangaId = mangaId,
        chapterNumber = ChapterNumber(number),
        name = null,
        scanlator = Text("Group"),
        sourceChapterKey = null,
        url = Url("https://example.com/ch/$number"),
        dateUpload = null,
        dateFetch = Instant.DISTANT_PAST,
        progress = progress
    )

    @Test
    fun `the header carries the manga in plain values`() {
        val header = content(listOf(chapter(1.0), chapter(2.0))).header

        assertEquals("Absolute Regression", header.title)
        assertEquals("https://example.com/c.jpg", header.coverUrl)
        assertEquals(2, header.chapterCount)
        assertEquals(true, header.inLibrary)
    }

    @Test
    fun `rows report read - started and download state`() {
        val read = chapter(1.0, ReadingProgress(hasBookmark = false, lastPageRead = 20u, alreadyRead = true))
        val started = chapter(2.0, ReadingProgress(hasBookmark = false, lastPageRead = 12u, alreadyRead = false))
        val downloading = chapter(10.5)
        val download =
            ChapterDownload(
                chapterId = downloading.id,
                mangaId = mangaId,
                state = DownloadState.Downloading,
                queuedAt = Instant.DISTANT_PAST,
                downloadedPages = 5,
                pageCount = 20
            )

        val rows = content(listOf(read, started, downloading), mapOf(downloading.id to download)).rows

        assertEquals(listOf(true, false, false), rows.map { it.isRead })
        assertEquals(listOf(null, 12, null), rows.map { it.lastPageRead })
        assertEquals("10.5", rows[2].numberLabel)
        assertEquals(DownloadState.Downloading, rows[2].download)
        assertEquals(0.25f, rows[2].downloadProgress)
        assertNull(rows[0].download)
    }

    private fun content(
        chapters: List<Chapter>,
        downloads: Map<Uuid, ChapterDownload> = emptyMap()
    ) = MangaState.Content(
        manga = manga,
        chapters = chapters,
        resumeChapter = chapters.firstOrNull(),
        chapterSortAscending = true,
        categories = emptyList(),
        scanlators = emptyList(),
        source = null,
        downloads = downloads
    )
}
