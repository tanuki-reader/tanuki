package app.tanuki.core.data.repository.manga

import app.tanuki.core.domain.manga.model.PublishStatus
import app.tanuki.core.domain.manga.model.ReadingMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/**
 * `cover_override` is what every list shows, but the source's cover must survive under it: a whole-row
 * write of the domain object puts the source's URL and headers back in their own columns, never the
 * override's.
 */
class CoverOverrideMappingTest {
    private val entity =
        MangaEntity(
            id = Uuid.random(), source = Uuid.random(), title = "Solo Leveling", author = null, description = null,
            coverUrl = "https://source/cover.jpg", coverHeaders = encodeHeaders(mapOf("Referer" to "https://source/")),
            sourceMangaKey = "solo", url = "https://source/solo", readingMode = ReadingMode.WEBTOON,
            publishStatus = PublishStatus.COMPLETED, filteredScanlators = null,
            coverOverride = "https://anilist/cover.jpg",
        )

    @Test
    fun `an override is shown without the source's headers and round trips`() {
        val manga = entity.toDomain(null, null, null, 0u, false)

        assertEquals("https://anilist/cover.jpg", manga.cover.url?.value)
        assertEquals(emptyMap(), manga.cover.headers)
        assertEquals(entity, manga.toEntity())
    }

    @Test
    fun `no override shows the source's cover with its headers`() {
        val manga = entity.copy(coverOverride = null).toDomain(null, null, null, 0u, false)

        assertEquals("https://source/cover.jpg", manga.cover.url?.value)
        assertEquals(mapOf("Referer" to "https://source/"), manga.cover.headers)
        assertEquals(entity.copy(coverOverride = null), manga.toEntity())
    }
}
