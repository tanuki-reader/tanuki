package app.tanuki.core.domain.source.usecase

import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.common.service.LogPriority
import app.tanuki.core.domain.common.service.RecordingLogger
import app.tanuki.core.domain.source.SourceLoader
import app.tanuki.core.domain.source.model.MangaListing
import app.tanuki.core.domain.source.model.MangaPage
import app.tanuki.core.domain.source.model.Page
import app.tanuki.core.domain.source.model.Source
import app.tanuki.core.domain.source.model.SourceListing
import app.tanuki.core.domain.source.model.SourceMeta
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The one source these tests enumerate; file-level so [StubSourceLoader] can play a sequence with it. */
private val META = SourceMeta(id = "mangadex", name = Text("MangaDex"), lang = Text("en"), version = 1)

/**
 * Tests [GetInstalledSources] and [GetSourceMeta]. Two behaviours worth pinning: the failure policy —
 * enumerating bundles is best-effort, so a failed read is an *empty* source list, never an error the
 * Home screen has to render and never a stuck spinner — and that the enumeration is a live stream, so a
 * source installed or removed while a screen is open reaches it.
 */
class GetInstalledSourcesTest {

    @Test
    fun `returns the runtime's installed sources`() = runTest {
        val sources = GetInstalledSources.Default(StubSourceLoader(listOf(META)), RecordingLogger())().first()

        assertEquals(listOf(META), sources)
    }

    @Test
    fun `follows the runtime as sources are installed and removed`() = runTest {
        val loader = StubSourceLoader()

        val emissions = GetInstalledSources.Default(loader, RecordingLogger())().take(3).toList()

        assertEquals(listOf(emptyList(), listOf(META), emptyList()), emissions)
    }

    @Test
    fun `swallows an enumeration failure as an empty list but logs it`() = runTest {
        val logger = RecordingLogger()

        val sources =
            GetInstalledSources.Default(StubSourceLoader(failure = IllegalStateException("bad bundle")), logger)()
                .first()

        assertEquals(emptyList(), sources)
        assertEquals(1, logger.entries.size)
        assertEquals(LogPriority.WARN, logger.entries.single().priority)
    }

    @Test
    fun `resolves one source by its source-native id`() = runTest {
        val getMeta = GetSourceMeta.Default(GetInstalledSources.Default(StubSourceLoader(listOf(META)), RecordingLogger()))

        assertEquals(META, getMeta("mangadex"))
    }

    @Test
    fun `yields null for a source that is not installed`() = runTest {
        val getMeta = GetSourceMeta.Default(GetInstalledSources.Default(StubSourceLoader(listOf(META)), RecordingLogger()))

        assertNull(getMeta("comick"))
    }
}

/**
 * A [SourceLoader] whose enumeration emits [metas] or fails with [failure]; other calls are unused.
 *
 * With neither argument it plays a three-step install/uninstall sequence — nothing, then [META], then
 * nothing again — which is what a screen sees when a source is installed and later removed underneath it.
 */
private class StubSourceLoader(
    private val metas: List<SourceMeta>? = null,
    private val failure: Throwable? = null
) : SourceLoader {
    override fun observeInstalledSources(): Flow<List<SourceMeta>> =
        when {
            failure != null -> flow { throw failure }
            metas != null -> flowOf(metas)
            else -> flowOf(emptyList(), listOf(META), emptyList())
        }

    override suspend fun loadSource(id: String): Source = error("unused")
    override suspend fun fetchMangaList(sourceId: String, listing: SourceListing?, query: String?, page: Int): MangaPage =
        error("unused")
    override suspend fun fetchMangaDetails(sourceId: String, mangaKey: String): MangaListing = error("unused")
    override suspend fun fetchChapterList(sourceId: String, mangaKey: String) = error("unused")
    override suspend fun fetchChapterPages(sourceId: String, chapterId: String): List<Page> = error("unused")
}