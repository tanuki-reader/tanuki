package app.tanuki.core.domain.chapter.usecase

import app.tanuki.core.domain.chapter.model.Chapter
import app.tanuki.core.domain.chapter.model.ChapterNumber
import app.tanuki.core.domain.chapter.model.PageImage
import app.tanuki.core.domain.chapter.model.ReaderPage
import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.common.model.Url
import app.tanuki.core.domain.common.service.uuidV7
import app.tanuki.core.domain.download.usecase.FakeChapterDownloadStore
import app.tanuki.core.domain.download.usecase.FakeChapterRepository
import app.tanuki.core.domain.download.usecase.FakeMangaRepository
import app.tanuki.core.domain.download.usecase.FakeSourceLoader
import app.tanuki.core.domain.download.usecase.FakeSourceRepository
import app.tanuki.core.domain.download.usecase.GetLocalChapterPages
import app.tanuki.core.domain.manga.model.Manga
import app.tanuki.core.domain.manga.model.MangaCover
import app.tanuki.core.domain.manga.model.PublishStatus
import app.tanuki.core.domain.manga.model.ReadingMode
import app.tanuki.core.domain.manga.model.ReadingStatus
import app.tanuki.core.domain.source.model.Page
import app.tanuki.core.domain.source.model.Source
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Tests the read path's one decision: a downloaded chapter is read from disk, anything else from its
 * source.
 *
 * Both halves run for real — [GetLocalChapterPages] over an in-memory store and [GetChapterPages] over a
 * fake source loader — because the interesting question is which of the two answers, and doubling either
 * would be assuming the answer. The fakes are the download tests' own (`DownloadWorld`), so "downloaded"
 * here means what it means to the engine: a committed manifest, not a row.
 */
class GetReaderPagesTest {
    private val store = FakeChapterDownloadStore()
    private val chapters = FakeChapterRepository(listOf(CHAPTER))
    private val loader = FakeSourceLoader(pagesPerChapter = 3)

    private val getReaderPages: GetReaderPages =
        GetReaderPages.Default(
            getLocalChapterPages = GetLocalChapterPages.Default(chapters, store),
            getChapterPages =
                GetChapterPages.Default(
                    chapterRepository = chapters,
                    mangaRepository = FakeMangaRepository(MANGA),
                    sourceRepository = FakeSourceRepository(SOURCE),
                    sourceLoader = loader,
                ),
        )

    @Test
    fun `a downloaded chapter is read from disk and the source is never asked`() =
        runTest {
            download(pages = 3)

            val pages = getReaderPages(CHAPTER.id)

            assertEquals(3, pages.size)
            assertTrue(pages.all { it.image is PageImage.Local }, "got $pages")
            // The moment offline reading has to work is the moment asking the source cannot, so there is
            // no round-trip here to fail — not even a hopeful one.
            assertTrue(loader.asked.isEmpty(), "a downloaded chapter must not touch the source")
        }

    @Test
    fun `a chapter that was never downloaded comes from its source`() =
        runTest {
            val pages = getReaderPages(CHAPTER.id)

            assertEquals(3, pages.size)
            assertTrue(pages.all { it.image is PageImage.Remote }, "got $pages")
            assertEquals(listOf("md-chapter-1"), loader.asked)
        }

    @Test
    fun `a half-downloaded chapter comes from its source`() =
        runTest {
            // Pages on disk but no committed manifest — an interrupted download, which is resumable and
            // is emphatically not something the reader may try to render.
            store.openPart(MANGA.id, CHAPTER.id).writePage(0, "jpg", ByteArray(4))

            assertTrue(getReaderPages(CHAPTER.id).all { it.image is PageImage.Remote })
        }

    @Test
    fun `a chapter whose files vanished falls back to the source rather than failing`() =
        runTest {
            download(pages = 2)
            // What clearing app storage looks like: the row still says downloaded, the bytes are gone.
            store.delete(MANGA.id, CHAPTER.id)

            assertEquals(3, getReaderPages(CHAPTER.id).size)
        }

    @Test
    fun `a page the source published no URL for is dropped`() =
        runTest {
            loader.scripted["md-chapter-1"] =
                listOf(
                    Page(index = 0u, imageUrl = Url("https://cdn.example/0.jpg"), url = null),
                    Page(index = 1u, imageUrl = null, url = null),
                )

            // A page with nothing to fetch is not a page; dropping it here is what spares every layer
            // below from carrying a "might have no URL" case.
            assertEquals(1, getReaderPages(CHAPTER.id).size)
        }

    @Test
    fun `a source that only publishes a page URL is read from that`() =
        runTest {
            loader.scripted["md-chapter-1"] =
                listOf(Page(index = 0u, imageUrl = null, url = Url("https://example.org/read/1")))

            assertEquals(
                listOf(ReaderPage(PageImage.Remote(Url("https://example.org/read/1")))),
                getReaderPages(CHAPTER.id),
            )
        }

    @Test
    fun `the headers a hotlink-guarded source needs survive onto the page`() =
        runTest {
            val headers = mapOf("Referer" to "https://mangafire.to/")
            loader.scripted["md-chapter-1"] =
                listOf(Page(index = 0u, imageUrl = Url("https://cdn.example/0.jpg"), url = null, headers = headers))

            assertEquals(headers, (getReaderPages(CHAPTER.id).single().image as PageImage.Remote).headers)
        }

    @Test
    fun `a source failure propagates rather than reading as a chapter with no pages`() =
        runTest {
            loader.failing += "md-chapter-1"

            assertFailsWith<IllegalStateException> { getReaderPages(CHAPTER.id) }
        }

    @Test
    fun `a committed directory with no pages in it is not a download`() =
        runTest {
            // The engine refuses to commit an empty chapter, so this is a corrupted directory. Reading it
            // as a download would show a blank reader; treating it as absent shows the chapter.
            store.commit(store.openPart(MANGA.id, CHAPTER.id), expectedPageCount = 0)

            assertEquals(3, getReaderPages(CHAPTER.id).size)
        }

    /** Commits [pages] pages for the chapter, the way the download engine would. */
    private suspend fun download(pages: Int) {
        val part = store.openPart(MANGA.id, CHAPTER.id)
        repeat(pages) { index -> part.writePage(index, "jpg", ByteArray(8)) }
        store.commit(part, expectedPageCount = pages)
    }

    private companion object {
        val SOURCE =
            Source(
                id = uuidV7(),
                sourceId = "mangadex",
                lang = Text("en"),
                name = Text("MangaDex"),
                url = Url("https://mangadex.org"),
                version = 2,
            )

        val MANGA =
            Manga(
                id = uuidV7(),
                source = SOURCE.id,
                title = Text("One Piece"),
                author = null,
                description = null,
                cover = MangaCover(mangaId = uuidV7(), url = null, lastModified = Clock.System.now()),
                sourceMangaKey = Text("op-id"),
                url = Url("https://mangadex.org/title/op-id"),
                dateAdded = null,
                readingMode = ReadingMode.LEFT_TO_RIGHT,
                publishStatus = PublishStatus.ONGOING,
                readingStatus = null,
            )

        val CHAPTER: Chapter =
            Chapter(
                id = uuidV7(),
                mangaId = MANGA.id,
                chapterNumber = ChapterNumber.orNull(1.0),
                name = Text("Romance Dawn"),
                scanlator = null,
                sourceChapterKey = Text("md-chapter-1"),
                url = MANGA.url,
                dateUpload = null,
                dateFetch = Instant.fromEpochMilliseconds(1_000),
                progress = null,
            )
    }
}
