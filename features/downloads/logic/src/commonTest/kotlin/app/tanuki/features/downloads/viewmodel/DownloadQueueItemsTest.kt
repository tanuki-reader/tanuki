package app.tanuki.features.downloads.viewmodel

import app.tanuki.core.domain.chapter.model.Chapter
import app.tanuki.core.domain.chapter.model.ChapterNumber
import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.common.model.Url
import app.tanuki.core.domain.download.model.ChapterDownload
import app.tanuki.core.domain.download.model.DownloadError
import app.tanuki.core.domain.download.model.DownloadQueueSeries
import app.tanuki.core.domain.download.model.DownloadState
import app.tanuki.core.domain.download.model.QueuedChapter
import app.tanuki.core.domain.manga.model.Manga
import app.tanuki.core.domain.manga.model.MangaCover
import app.tanuki.core.domain.manga.model.PublishStatus
import app.tanuki.core.domain.manga.model.ReadingMode
import app.tanuki.core.domain.manga.model.ReadingStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The queue reaches Swift as plain values; this pins what each domain field turns into. */
class DownloadQueueItemsTest {
    @Test
    fun `a series maps to plain values with its chapters labels and progress and failures`() {
        val mangaId = Uuid.random()
        val manga =
            Manga(
                id = mangaId,
                source = Uuid.random(),
                title = Text("Berserk"),
                author = null,
                description = null,
                cover = MangaCover(mangaId, Url("https://example.com/c.jpg"), mapOf("Referer" to "x")),
                url = Url("https://example.com/berserk"),
                dateAdded = null,
                readingMode = ReadingMode.RIGHT_TO_LEFT,
                publishStatus = PublishStatus.ONGOING,
                readingStatus = ReadingStatus.READING,
            )
        val downloading = queued(mangaId, 10.5, null, DownloadState.Downloading, pages = 7 to 20)
        val waiting = queued(mangaId, null, "Extra", DownloadState.Queued)
        val failed = queued(mangaId, 12.0, null, DownloadState.Failed, error = DownloadError.NeedsVerification)

        val item = DownloadQueueSeries(manga, listOf(downloading, waiting, failed)).toItem()

        assertEquals("Berserk", item.title)
        assertEquals("https://example.com/c.jpg", item.coverUrl)
        assertEquals(mapOf("Referer" to "x"), item.coverHeaders)
        assertEquals(1, item.failedCount)
        assertEquals(listOf(downloading, waiting, failed).map { it.chapter.id }, item.chapters.map { it.chapterId })

        val (first, second, third) = item.chapters
        assertEquals("10.5", first.numberLabel)
        assertEquals(0.35f, first.progress)
        assertEquals(7, first.downloadedPages)
        assertEquals(20, first.pageCount)

        assertNull(second.numberLabel)
        assertEquals("Extra", second.name)
        assertNull(second.progress)
        assertNull(second.pageCount)

        assertEquals("12", third.numberLabel)
        assertEquals(true, third.failed)
        assertEquals(DownloadError.NeedsVerification, third.error)
    }
}

private fun queued(
    mangaId: Uuid,
    number: Double?,
    name: String?,
    state: DownloadState,
    pages: Pair<Int, Int>? = null,
    error: DownloadError? = null,
): QueuedChapter {
    val chapter =
        Chapter(
            id = Uuid.random(),
            mangaId = mangaId,
            chapterNumber = number?.let(::ChapterNumber),
            name = name?.let(::Text),
            scanlator = null,
            sourceChapterKey = null,
            url = Url("https://example.com/$number"),
            dateUpload = null,
            dateFetch = Instant.fromEpochSeconds(0),
            progress = null,
        )
    val download =
        ChapterDownload(
            chapterId = chapter.id,
            mangaId = mangaId,
            state = state,
            queuedAt = Instant.fromEpochSeconds(0),
            downloadedPages = pages?.first ?: 0,
            pageCount = pages?.second,
            error = error,
        )
    return QueuedChapter(chapter, download)
}
