package app.tanuki.core.domain.chapter.usecase

import app.tanuki.core.domain.chapter.model.Chapter
import app.tanuki.core.domain.chapter.model.ChapterNumber
import app.tanuki.core.domain.chapter.model.ReadingProgress
import app.tanuki.core.domain.chapter.repository.ChapterRepository
import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.common.model.Url
import app.tanuki.core.domain.common.service.uuidV7
import app.tanuki.core.domain.manga.model.Manga
import app.tanuki.core.domain.manga.model.MangaCover
import app.tanuki.core.domain.manga.model.PublishStatus
import app.tanuki.core.domain.manga.model.ReadingMode
import app.tanuki.core.domain.manga.model.ReadingStatus
import app.tanuki.core.domain.manga.repository.MangaRepository
import app.tanuki.core.domain.source.SourceLoader
import app.tanuki.core.domain.source.model.ChapterListing
import app.tanuki.core.domain.source.model.MangaListing
import app.tanuki.core.domain.source.model.MangaPage
import app.tanuki.core.domain.source.model.Page
import app.tanuki.core.domain.source.model.Source
import app.tanuki.core.domain.source.model.SourceListing
import app.tanuki.core.domain.source.model.SourceMeta
import app.tanuki.core.domain.source.repository.SourceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Tests [GetChapterPages] against in-memory fakes. What matters is the id translation: the reader hands
 * it a local chapter [Uuid], and it must address the source by the *source-native* pair (source id +
 * chapter key). The failure cases matter too — a resolution gap must throw rather than masquerade as a
 * chapter with no pages.
 */
class GetChapterPagesTest {
    @Test
    fun `resolves the local chapter to source-native ids and returns the source's pages`() =
        runTest {
            val loader = PagesSourceLoader(pages = listOf(page(0), page(1), page(2)))
            val getPages =
                GetChapterPages.Default(
                    chapterRepository = PagesChapterRepo(CHAPTER),
                    mangaRepository = PagesMangaRepo(MANGA),
                    sourceRepository = PagesSourceRepo(SOURCE),
                    sourceLoader = loader
                )

            val pages = getPages(CHAPTER.id)

            assertEquals(3, pages.size)
            // Addressed by the source's native id and the chapter's own key — never the local Uuids.
            assertEquals("mangadex", loader.askedSourceId)
            assertEquals("md-chapter-1", loader.askedChapterKey)
        }

    @Test
    fun `an empty page list is a valid answer and not an error`() =
        runTest {
            val getPages =
                GetChapterPages.Default(
                    chapterRepository = PagesChapterRepo(CHAPTER),
                    mangaRepository = PagesMangaRepo(MANGA),
                    sourceRepository = PagesSourceRepo(SOURCE),
                    sourceLoader = PagesSourceLoader(pages = emptyList())
                )

            assertEquals(emptyList(), getPages(CHAPTER.id))
        }

    @Test
    fun `throws when the chapter does not exist`() =
        runTest {
            val getPages =
                GetChapterPages.Default(
                    chapterRepository = PagesChapterRepo(existing = null),
                    mangaRepository = PagesMangaRepo(MANGA),
                    sourceRepository = PagesSourceRepo(SOURCE),
                    sourceLoader = PagesSourceLoader(pages = emptyList())
                )

            assertFailsWith<IllegalStateException> { getPages(CHAPTER.id) }
        }

    @Test
    fun `throws when the chapter has no source key`() =
        runTest {
            val getPages =
                GetChapterPages.Default(
                    chapterRepository = PagesChapterRepo(CHAPTER.copy(sourceChapterKey = null)),
                    mangaRepository = PagesMangaRepo(MANGA),
                    sourceRepository = PagesSourceRepo(SOURCE),
                    sourceLoader = PagesSourceLoader(pages = emptyList())
                )

            assertFailsWith<IllegalStateException> { getPages(CHAPTER.id) }
        }

    @Test
    fun `throws when the source cannot be resolved`() =
        runTest {
            val getPages =
                GetChapterPages.Default(
                    chapterRepository = PagesChapterRepo(CHAPTER),
                    mangaRepository = PagesMangaRepo(MANGA),
                    sourceRepository = PagesSourceRepo(source = null),
                    sourceLoader = PagesSourceLoader(pages = emptyList())
                )

            assertFailsWith<IllegalStateException> { getPages(CHAPTER.id) }
        }

    private fun page(index: Int) =
        Page(index = index.toUInt(), imageUrl = Url("https://cdn.mangadex.org/$index.png"), url = null)

    private companion object {
        val SOURCE =
            Source(
                id = uuidV7(),
                sourceId = "mangadex",
                lang = Text("en"),
                name = Text("MangaDex"),
                url = Url("https://mangadex.org"),
                version = 2
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
                readingStatus = null
            )

        val CHAPTER =
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
                progress = null
            )
    }
}

private class PagesChapterRepo(
    private val existing: Chapter?
) : ChapterRepository {
    override suspend fun getChapter(chapterId: Uuid): Chapter? = existing

    override fun observeChapters(mangaId: Uuid): Flow<List<Chapter>> = flowOf(emptyList())

    override fun observeBookmarkedChapters(mangaId: Uuid): Flow<List<Chapter>> = flowOf(emptyList())

    override suspend fun getChapters(mangaId: Uuid): List<Chapter> = error("unused")

    override suspend fun updateProgress(
        chapterId: Uuid,
        progress: ReadingProgress
    ) = error("unused")

    override suspend fun upsertChapter(chapter: Chapter) = error("unused")

    override suspend fun upsertChapters(chapters: List<Chapter>) = error("unused")

    override suspend fun deleteChapter(chapterId: Uuid) = error("unused")

    override suspend fun deleteChapters(chapterIds: List<Uuid>) = error("unused")
}

private class PagesMangaRepo(
    private val manga: Manga?
) : MangaRepository {
    override fun observeManga(mangaId: Uuid): Flow<Manga?> = flowOf(manga)

    override fun observeLibrary(): Flow<List<Manga>> = flowOf(emptyList())

    override fun observeMangaBySource(sourceId: Uuid): Flow<List<Manga>> = flowOf(emptyList())

    override suspend fun getMangaIdBySourceAndKey(
        sourceId: Uuid,
        sourceMangaKey: String
    ): Uuid? = error("unused")

    override suspend fun upsertManga(manga: Manga) = error("unused")

    override suspend fun deleteManga(mangaId: Uuid) = error("unused")

    override suspend fun setFilteredScanlators(
        mangaId: Uuid,
        scanlators: Set<String>
    ) = error("unused")

    override suspend fun setReadingStatus(
        mangaId: Uuid,
        status: ReadingStatus
    ) = error("unused")

    override suspend fun setReadingMode(
        mangaId: Uuid,
        mode: ReadingMode
    ) = error("unused")

    override suspend fun addToLibrary(mangaId: Uuid) = error("unused")

    override suspend fun removeFromLibrary(mangaId: Uuid) = error("unused")
}

private class PagesSourceRepo(
    private val source: Source?
) : SourceRepository {
    override suspend fun getSource(id: Uuid): Source? = source

    override suspend fun getSourceByNativeId(sourceId: String): Source? = source

    override fun observeSources(): Flow<List<Source>> = flowOf(emptyList())

    override suspend fun addSource(source: Source) = error("unused")

    override suspend fun deleteSource(source: Source) = error("unused")
}

private class PagesSourceLoader(
    private val pages: List<Page>
) : SourceLoader {
    var askedSourceId: String? = null
    var askedChapterKey: String? = null

    override suspend fun fetchChapterPages(
        sourceId: String,
        chapterId: String
    ): List<Page> {
        askedSourceId = sourceId
        askedChapterKey = chapterId
        return pages
    }

    override suspend fun loadSource(id: String): Source = error("unused")

    override fun observeInstalledSources(): Flow<List<SourceMeta>> = error("unused")

    override suspend fun fetchMangaList(
        sourceId: String,
        listing: SourceListing?,
        query: String?,
        page: Int
    ): MangaPage = error("unused")

    override suspend fun fetchMangaDetails(
        sourceId: String,
        mangaKey: String
    ): MangaListing = error("unused")

    override suspend fun fetchChapterList(
        sourceId: String,
        mangaKey: String
    ): List<ChapterListing> = error("unused")
}