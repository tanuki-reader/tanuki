package app.tanuki.core.domain.metadata.usecase

import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.common.model.Url
import app.tanuki.core.domain.download.usecase.FakeMangaRepository
import app.tanuki.core.domain.manga.model.Manga
import app.tanuki.core.domain.manga.model.MangaCover
import app.tanuki.core.domain.manga.model.PublishStatus
import app.tanuki.core.domain.manga.model.ReadingMode
import app.tanuki.core.domain.metadata.model.MatchedBy
import app.tanuki.core.domain.metadata.model.MetadataTag
import app.tanuki.core.domain.metadata.model.SeriesMetadata
import app.tanuki.core.domain.metadata.model.TrackerLink
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** What the user sets wins: their cover over the metadata's, their reading mode over any suggestion. */
class ApplyMangaMetadataTest {
    private val mangaId = Uuid.random()
    private val manga =
        Manga(
            id = mangaId, source = Uuid.random(), title = Text("Solo Leveling"), author = null, description = null,
            cover = MangaCover(mangaId, Url("https://source/cover.jpg"), mapOf("Referer" to "https://source/")),
            url = Url("https://source/solo"), dateAdded = null, readingMode = ReadingMode.LEFT_TO_RIGHT,
            publishStatus = PublishStatus.COMPLETED, readingStatus = null
        )
    private val mangaRepository = FakeMangaRepository(manga)
    private val metadataRepository = MemoryMetadataRepository()
    private val apply = ApplyMangaMetadata.Default(mangaRepository, metadataRepository)

    private suspend fun stored() = checkNotNull(mangaRepository.observeManga(mangaId).first())

    private suspend fun link(
        useSourceInfo: Boolean = false,
        coverChoice: String? = null
    ) {
        metadataRepository.upsertLink(TrackerLink(mangaId, 1L, null, null, MatchedBy.Source, useSourceInfo, coverChoice))
        metadataRepository.upsertMetadata(metadata(country = "KR"))
    }

    @Test
    fun `the metadata cover shows without the source's headers - the source's stays underneath`() = runTest {
        link()

        apply(mangaId)

        val cover = stored().cover
        assertEquals("https://anilist/cover.jpg", cover.url?.value)
        assertEquals(emptyMap(), cover.headers)
        assertEquals("https://source/cover.jpg" to mapOf("Referer" to "https://source/"), cover.sourceUrl?.value to cover.sourceHeaders)
    }

    @Test
    fun `the user's pick beats the metadata cover`() = runTest {
        link(coverChoice = "https://mangadex/vol3.jpg")

        apply(mangaId)

        assertEquals("https://mangadex/vol3.jpg", stored().cover.url?.value)
    }

    @Test
    fun `preferring the source's info brings the source's cover back`() = runTest {
        link()
        apply(mangaId)
        link(useSourceInfo = true)

        apply(mangaId)

        assertEquals(manga.cover, stored().cover)
    }

    @Test
    fun `a suggested mode is applied - a chosen one never is`() = runTest {
        link()
        apply(mangaId)
        assertEquals(ReadingMode.WEBTOON, stored().readingMode)

        mangaRepository.setReadingMode(mangaId, ReadingMode.RIGHT_TO_LEFT)
        apply(mangaId)

        assertEquals(ReadingMode.RIGHT_TO_LEFT, stored().readingMode)
    }

    @Test
    fun `reading mode follows origin then the long-strip tag`() {
        assertEquals(ReadingMode.RIGHT_TO_LEFT, suggestedReadingMode(metadata(country = "JP")))
        assertEquals(ReadingMode.WEBTOON, suggestedReadingMode(metadata(country = "KR")))
        assertEquals(ReadingMode.WEBTOON, suggestedReadingMode(metadata(country = "CN")))
        assertEquals(ReadingMode.WEBTOON, suggestedReadingMode(metadata(country = "JP", tags = listOf("Long Strip"))))
        assertEquals(null, suggestedReadingMode(metadata(country = null, tags = listOf("Full Color"))))
    }

    private fun metadata(
        country: String?,
        tags: List<String> = emptyList()
    ) = SeriesMetadata(
        anilistId = 1, malId = null, title = "Solo Leveling", synopsis = null, coverUrl = "https://anilist/cover.jpg",
        bannerUrl = null, score = null, popularity = null, genres = emptyList(),
        tags = tags.map { MetadataTag(it, 90, false) }, status = PublishStatus.COMPLETED, year = null,
        characters = emptyList(), reviews = emptyList(), recommendations = emptyList(), siteUrl = "",
        fetchedAt = Clock.System.now(), countryOfOrigin = country
    )
}
