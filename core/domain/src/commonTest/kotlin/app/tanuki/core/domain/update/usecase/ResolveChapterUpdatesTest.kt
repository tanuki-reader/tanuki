package app.tanuki.core.domain.update.usecase

import app.tanuki.core.domain.chapter.model.Chapter
import app.tanuki.core.domain.chapter.model.ChapterNumber
import app.tanuki.core.domain.chapter.model.ReadingProgress
import app.tanuki.core.domain.chapter.repository.ChapterRepository
import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.common.model.Url
import app.tanuki.core.domain.common.service.uuidV7
import app.tanuki.core.domain.library.model.LibraryManga
import app.tanuki.core.domain.manga.model.Manga
import app.tanuki.core.domain.manga.model.MangaCover
import app.tanuki.core.domain.manga.model.PublishStatus
import app.tanuki.core.domain.manga.model.ReadingMode
import app.tanuki.core.domain.manga.model.ReadingStatus
import app.tanuki.core.domain.manga.repository.MangaRepository
import app.tanuki.core.domain.update.model.ChapterUpdate
import app.tanuki.core.domain.update.model.LibraryUpdateTarget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Tests [ResolveChapterUpdates] — the one place that decides what still counts as a pending update.
 * Every rule here is a rule the notification inherits too, so the cases are about what must *never* be
 * offered: a chapter the user already opened, one from a group they hid, and anything belonging to a
 * series they no longer follow.
 */
class ResolveChapterUpdatesTest {
    @Test
    fun `resolves a pending update to its chapter and series`() = runTest {
        val chapter = chapter(number = 2.0)
        val resolve = resolve(chapters = listOf(chapter))

        val resolved = resolve(listOf(update(chapter))).single()

        assertEquals(chapter.id, resolved.chapter.id)
        assertEquals(MANGA.id, resolved.manga.id)
        assertEquals(DISCOVERED_AT, resolved.discoveredAt)
    }

    @Test
    fun `a chapter the user has opened is no longer pending`() = runTest {
        val opened =
            chapter(number = 2.0)
                .copy(progress = ReadingProgress(hasBookmark = false, lastPageRead = 3u, alreadyRead = false))
        val resolve = resolve(chapters = listOf(opened))

        // A progress row exists the moment the reader opens a chapter, so the list clears as they read
        // — no dismissal state of its own.
        assertTrue(resolve(listOf(update(opened))).isEmpty())
    }

    @Test
    fun `a chapter from a scanlation group the user hid is never offered`() = runTest {
        val hidden = chapter(number = 2.0, scanlator = "Mangastream")
        val shown = chapter(number = 2.0, scanlator = "TCB Scans")
        val resolve =
            resolve(
                manga = MANGA.copy(filteredScanlators = setOf("Mangastream")),
                chapters = listOf(hidden, shown),
            )

        val resolved = resolve(listOf(update(hidden), update(shown)))

        // The same filter the chapter list and reader apply: a group the user hid on a series must not
        // reach the Home list or the notification either.
        assertEquals(listOf(shown.id), resolved.map { it.chapter.id })
    }

    @Test
    fun `a chapter credited to no group survives any filter`() = runTest {
        val unattributed = chapter(number = 2.0, scanlator = null)
        val resolve =
            resolve(manga = MANGA.copy(filteredScanlators = setOf("Mangastream")), chapters = listOf(unattributed))

        assertEquals(1, resolve(listOf(update(unattributed))).size, "there is no group to filter it by")
    }

    @Test
    fun `a series the user stopped following has no pending updates`() = runTest {
        val chapter = chapter(number = 2.0)
        val resolve = resolve(manga = MANGA.copy(dateAdded = null), chapters = listOf(chapter))

        assertTrue(resolve(listOf(update(chapter))).isEmpty())
    }

    @Test
    fun `a chapter the source has since pruned is skipped`() = runTest {
        val pruned = chapter(number = 2.0)
        val resolve = resolve(chapters = emptyList())

        assertTrue(resolve(listOf(update(pruned))).isEmpty())
    }

    @Test
    fun `updates are newest first and highest chapter first within one discovery`() = runTest {
        val older = chapter(number = 5.0)
        val newerLow = chapter(number = 6.0)
        val newerHigh = chapter(number = 7.0)
        val resolve = resolve(chapters = listOf(older, newerLow, newerHigh))
        val later = DISCOVERED_AT + 1.hours

        val resolved =
            resolve(
                listOf(
                    update(older, at = DISCOVERED_AT),
                    update(newerLow, at = later),
                    update(newerHigh, at = later),
                ),
            )

        assertEquals(
            listOf(7.0, 6.0, 5.0),
            resolved.map { it.chapter.chapterNumber?.value },
        )
    }

    @Test
    fun `the observed and one-shot forms agree`() = runTest {
        val hidden = chapter(number = 2.0, scanlator = "Mangastream")
        val shown = chapter(number = 3.0)
        val read = chapter(number = 4.0).copy(progress = ReadingProgress(false, 1u, false))
        val resolve =
            resolve(
                manga = MANGA.copy(filteredScanlators = setOf("Mangastream")),
                chapters = listOf(hidden, shown, read),
            )
        val updates = listOf(update(hidden), update(shown), update(read))

        // NotifyNewChapters takes the one-shot and Home takes the observed one; if these ever diverge the
        // notification announces a chapter the list refuses to show.
        assertEquals(
            resolve(updates).map { it.chapter.id },
            resolve.observe(updates).first().map { it.chapter.id },
        )
    }

    // --- helpers ---------------------------------------------------------------------------------

    private fun resolve(
        manga: Manga = MANGA,
        chapters: List<Chapter>
    ) = ResolveChapterUpdates(
        mangaRepository = FakeMangaRepository(manga),
        chapterRepository = FakeChapterRepository(chapters),
    )

    private fun update(
        chapter: Chapter,
        at: Instant = DISCOVERED_AT
    ) = ChapterUpdate(chapterId = chapter.id, mangaId = MANGA.id, discoveredAt = at)

    private fun chapter(
        number: Double,
        scanlator: String? = null
    ) = Chapter(
        id = uuidV7(),
        mangaId = MANGA.id,
        chapterNumber = ChapterNumber.orNull(number),
        name = null,
        scanlator = scanlator?.let(::Text),
        sourceChapterKey = Text("md-$number"),
        url = MANGA.url,
        dateUpload = null,
        dateFetch = DISCOVERED_AT,
        progress = null,
    )

    private companion object {
        val DISCOVERED_AT = Instant.fromEpochMilliseconds(1_700_000_000_000)

        val MANGA = Manga(
            id = uuidV7(),
            source = uuidV7(),
            title = Text("One Piece"),
            author = null,
            description = null,
            cover = MangaCover(mangaId = uuidV7(), url = null),
            sourceMangaKey = Text("op-id"),
            url = Url("https://mangadex.org/title/op-id"),
            dateAdded = Instant.fromEpochMilliseconds(1_000),
            readingMode = ReadingMode.LEFT_TO_RIGHT,
            publishStatus = PublishStatus.ONGOING,
            readingStatus = ReadingStatus.READING,
        )
    }
}

/** A [MangaRepository] observing one canned series. */
private class FakeMangaRepository(private val manga: Manga) : MangaRepository {
    override fun observeManga(mangaId: Uuid): Flow<Manga?> = flowOf(manga.takeIf { it.id == mangaId })

    override suspend fun getMangaByIds(mangaIds: Collection<Uuid>): List<Manga> =
        listOf(manga).filter { it.id in mangaIds }

    override fun observeMangaByIds(mangaIds: Collection<Uuid>): Flow<List<Manga>> =
        flowOf(listOf(manga).filter { it.id in mangaIds })

    override fun observeLibrary(): Flow<List<LibraryManga>> = flowOf(emptyList())
    override suspend fun getLibraryUpdateTargets(): List<LibraryUpdateTarget> = error("unused")

    override suspend fun getMangaIdsBySource(sourceId: Uuid): List<Uuid> = emptyList()
    override suspend fun getMangaIdBySourceAndKey(sourceId: Uuid, sourceMangaKey: String): Uuid? = error("unused")
    override suspend fun upsertManga(manga: Manga) = error("unused")

    override suspend fun setSourceDetails(
        mangaId: Uuid,
        title: Text,
        author: Text?,
        description: Text?,
        publishStatus: PublishStatus,
        cover: MangaCover
    ) = error("unused")

    override suspend fun setCoverHeaders(mangaId: Uuid, headers: Map<String, String>) = error("unused")

    override suspend fun recordChapterSync(
        mangaId: Uuid,
        at: Instant,
        nextUpdateAt: Instant,
        fetchInterval: Int
    ) = error("unused")

    override suspend fun markDetailsFetched(mangaId: Uuid, at: Instant) = error("unused")

    override suspend fun deleteManga(mangaId: Uuid) = error("unused")
    override suspend fun setFilteredScanlators(mangaId: Uuid, scanlators: Set<String>) = error("unused")
    override suspend fun setReadingStatus(mangaId: Uuid, status: ReadingStatus) = error("unused")
    override suspend fun setReadingMode(mangaId: Uuid, mode: ReadingMode) = error("unused")
    override suspend fun addToLibrary(mangaId: Uuid) = error("unused")
    override suspend fun removeFromLibrary(mangaId: Uuid) = error("unused")
}

/** A [ChapterRepository] observing one canned chapter list. */
private class FakeChapterRepository(private val chapters: List<Chapter>) : ChapterRepository {
    override fun observeChapters(mangaId: Uuid): Flow<List<Chapter>> = flowOf(chapters)

    override fun observeBookmarkedChapters(mangaId: Uuid): Flow<List<Chapter>> = flowOf(emptyList())
    override suspend fun getChapters(mangaId: Uuid): List<Chapter> = chapters
    override suspend fun getChapter(chapterId: Uuid): Chapter? = chapters.firstOrNull { it.id == chapterId }

    override suspend fun getChaptersByIds(chapterIds: Collection<Uuid>): List<Chapter> =
        chapters.filter { it.id in chapterIds }

    override fun observeChaptersByIds(chapterIds: Collection<Uuid>): Flow<List<Chapter>> =
        flowOf(chapters.filter { it.id in chapterIds })
    override suspend fun updateProgress(chapterId: Uuid, progress: ReadingProgress) = error("unused")
    override suspend fun updateProgress(progress: Map<Uuid, ReadingProgress>) = error("unused")
    override suspend fun upsertChapter(chapter: Chapter) = error("unused")
    override suspend fun upsertChapters(chapters: List<Chapter>) = error("unused")
    override suspend fun deleteChapter(chapterId: Uuid) = error("unused")
    override suspend fun deleteChapters(chapterIds: List<Uuid>) = error("unused")
}