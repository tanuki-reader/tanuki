package app.tanuki.features.manga.viewmodel

import app.tanuki.core.domain.bookmark.model.PageBookmark
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
import app.tanuki.core.domain.metadata.model.MatchedBy
import app.tanuki.core.domain.metadata.model.MetadataRecommendation
import app.tanuki.core.domain.metadata.model.MetadataTag
import app.tanuki.core.domain.metadata.model.SeriesMetadata
import app.tanuki.core.domain.metadata.model.TrackerLink
import app.tanuki.core.domain.metadata.usecase.MangaMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
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

    @Test
    fun `rows mark bookmarked chapters and the bookmarked filter opens the first bookmarked page`() {
        val one = chapter(1.0)
        val two = chapter(2.0)
        val marks = listOf(bookmark(two.id, page = 9), bookmark(two.id, page = 4))

        val plain = content(listOf(one, two)).copy(bookmarks = marks)
        val filtered = plain.copy(bookmarkedOnly = true)

        assertEquals(listOf(0, 2), plain.rows.map { it.bookmarkCount })
        assertTrue(plain.canFilterChapters, "a series with bookmarks offers the filter even with one group")
        assertFalse(plain.isChapterFilterActive)
        assertNull(plain.openPageOf(two.id), "outside the filter a tap resumes as usual")
        assertTrue(filtered.isChapterFilterActive)
        assertEquals(4, filtered.openPageOf(two.id))
        assertFalse(content(listOf(one)).canFilterChapters)
    }

    @Test
    fun `matched metadata takes over the header until the user prefers the source`() {
        val metadata =
            SeriesMetadata(
                anilistId = 1, malId = null, title = "Absolute Regression (EN)", synopsis = "From AniList",
                coverUrl = "https://anilist/cover.jpg", bannerUrl = null, score = 84, popularity = 1,
                genres = listOf("Action"), tags = emptyList(), status = PublishStatus.COMPLETED, year = 2020,
                characters = emptyList(), reviews = emptyList(), recommendations = emptyList(),
                siteUrl = "https://anilist.co/manga/1", fetchedAt = Instant.DISTANT_PAST
            )
        val link = TrackerLink(mangaId, anilistId = 1, malId = null, mangaDexId = null, matchedBy = MatchedBy.Source)

        val enriched = content(emptyList()).copy(metadata = MangaMetadata(link, metadata))
        assertEquals("Absolute Regression (EN)", enriched.header.title)
        assertEquals("From AniList", enriched.header.synopsis)
        assertEquals("https://anilist/cover.jpg", enriched.header.coverUrl)
        assertEquals(PublishStatus.COMPLETED, enriched.header.publishStatus)
        assertEquals(listOf("Action"), enriched.header.genres)
        assertEquals("8.4", enriched.scoreLabel)

        val sourcePreferred = content(emptyList()).copy(metadata = MangaMetadata(link.copy(useSourceInfo = true), metadata))
        assertEquals("Absolute Regression", sourcePreferred.header.title)
        assertEquals("Murim's skies", sourcePreferred.header.synopsis)

        val picked = content(emptyList()).copy(metadata = MangaMetadata(link.copy(coverChoice = "https://md/vol3.jpg"), metadata))
        assertEquals("https://md/vol3.jpg", picked.header.coverUrl)
    }

    @Test
    fun `genres and tags merge into one deduplicated list - spoilers kept but off the row`() {
        val metadata =
            series(
                genres = listOf("Action", "Fantasy"),
                tags = listOf(
                    MetadataTag("action", rank = 99, spoiler = false),
                    MetadataTag("Martial Arts", rank = 90, spoiler = false),
                    MetadataTag("Time Travel", rank = 95, spoiler = true),
                    MetadataTag("Revenge", rank = 70, spoiler = false)
                )
            )

        val content = content(emptyList()).copy(metadata = MangaMetadata(link(), metadata))

        assertEquals(listOf("Action", "Fantasy", "Time Travel", "Martial Arts", "Revenge"), content.tags.map { it.name })
        assertEquals(listOf("Action", "Fantasy", "Martial Arts"), content.tagRow.visible)
        assertEquals(2, content.tagRow.overflow)
    }

    @Test
    fun `recommendations sit above the chapters until the series is in the library`() {
        val withRecs = series(recommendations = listOf(MetadataRecommendation(2, null, "Bleach", null, "https://anilist.co/manga/2")))

        val browsing = content(emptyList(), inLibrary = false).copy(metadata = MangaMetadata(link(), withRecs))
        val owned = content(emptyList(), inLibrary = true).copy(metadata = MangaMetadata(link(), withRecs))
        val nothing = content(emptyList()).copy(metadata = MangaMetadata(link(), series()))

        assertEquals(RecommendationsPlacement.AboveChapters, browsing.recommendationsPlacement)
        assertEquals(RecommendationsPlacement.AboutOnly, owned.recommendationsPlacement)
        assertEquals(RecommendationsPlacement.None, nothing.recommendationsPlacement)
    }

    @Test
    fun `the hero line drops an unknown status and an absent rating`() {
        val plain = content(listOf(chapter(1.0))).copy(manga = manga.copy(publishStatus = PublishStatus.UNKNOWN))
        assertEquals(HeroLine(status = null, chapterCount = 1, rating = null), plain.heroLine)

        val rated = content(listOf(chapter(1.0))).copy(metadata = MangaMetadata(link(), series(score = 82)))
        assertEquals("8.2", rated.heroLine.rating)
    }

    private fun link() = TrackerLink(mangaId, anilistId = 1, malId = null, mangaDexId = null, matchedBy = MatchedBy.Source)

    private fun series(
        score: Int? = null,
        genres: List<String> = emptyList(),
        tags: List<MetadataTag> = emptyList(),
        recommendations: List<MetadataRecommendation> = emptyList()
    ) = SeriesMetadata(
        anilistId = 1, malId = null, title = "T", synopsis = null, coverUrl = null, bannerUrl = null, score = score,
        popularity = null, genres = genres, tags = tags, status = PublishStatus.ONGOING, year = null,
        characters = emptyList(), reviews = emptyList(), recommendations = recommendations,
        siteUrl = "https://anilist.co/manga/1", fetchedAt = Instant.DISTANT_PAST
    )

    private fun content(
        chapters: List<Chapter>,
        downloads: Map<Uuid, ChapterDownload> = emptyMap(),
        inLibrary: Boolean = true
    ) = MangaState.Content(
        manga = if (inLibrary) manga else manga.copy(dateAdded = null),
        chapters = chapters,
        resumeChapter = chapters.firstOrNull(),
        chapterSortAscending = true,
        categories = emptyList(),
        scanlators = emptyList(),
        source = null,
        downloads = downloads
    )

    private fun bookmark(
        chapterId: Uuid,
        page: Int
    ) = PageBookmark(Uuid.random(), mangaId, chapterId, page, Instant.DISTANT_PAST, image = null)
}
