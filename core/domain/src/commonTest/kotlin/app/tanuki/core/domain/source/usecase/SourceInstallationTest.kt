package app.tanuki.core.domain.source.usecase

import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.common.service.LogPriority
import app.tanuki.core.domain.common.service.RecordingLogger
import app.tanuki.core.domain.source.SourceBundleStore
import app.tanuki.core.domain.source.SourceCatalog
import app.tanuki.core.domain.source.SourceLoader
import app.tanuki.core.domain.source.model.ChapterListing
import app.tanuki.core.domain.source.model.InstallProgress
import app.tanuki.core.domain.source.model.MangaListing
import app.tanuki.core.domain.source.model.MangaPage
import app.tanuki.core.domain.source.model.Page
import app.tanuki.core.domain.source.model.RemoteSource
import app.tanuki.core.domain.source.model.RepoPreview
import app.tanuki.core.domain.source.model.Source
import app.tanuki.core.domain.source.model.SourceCompatibility
import app.tanuki.core.domain.source.model.SourceListing
import app.tanuki.core.domain.source.model.SourceMeta
import app.tanuki.core.domain.source.model.SourceRepo
import app.tanuki.core.domain.source.model.SourceRepoError
import app.tanuki.core.domain.source.model.SourceRepoException
import app.tanuki.core.domain.source.repository.SourceRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

private const val MANGADEX = "mangadex"
private const val COMICK = "comick"

private val MANGADEX_META =
    SourceMeta(id = MANGADEX, name = Text("MangaDex"), lang = Text("en"), version = 1)

private fun remote(
    id: String,
    name: String = id,
    version: Int = 2,
    repoName: String = "Tanuki Sources",
    compatibility: SourceCompatibility = SourceCompatibility.Compatible
) = RemoteSource(
    id = id,
    name = name,
    lang = "en",
    version = version,
    nsfw = false,
    iconUrl = null,
    repoUrl = "https://example.org/$repoName/",
    repoName = repoName,
    compatibility = compatibility
)

/**
 * Tests the source install/uninstall lifecycle: [UninstallSource], [InstallSource], and the
 * [ObserveSourceCatalog] stream the management screen renders.
 *
 * That an uninstall **keeps** the persisted source row — the whole reason removing a source is safe to
 * offer, since deleting it would cascade the user's library away — is guaranteed structurally rather
 * than here: [UninstallSource] has no [SourceRepository] dependency to delete through.
 */
class SourceInstallationTest {

    @Test
    fun `uninstalling removes the bundle`() = runTest {
        val store = FakeBundleStore(installed = setOf(MANGADEX))

        UninstallSource.Default(store)(MANGADEX)

        assertEquals(emptySet(), store.installed)
    }

    @Test
    fun `installing goes through the catalog`() = runTest {
        val catalog = FakeCatalog()

        val failure = InstallSource.Default(catalog, RecordingLogger())(MANGADEX)

        assertNull(failure)
        assertEquals(listOf(MANGADEX), catalog.installCalls)
    }

    @Test
    fun `a failed install names the step that failed and logs it`() = runTest {
        val logger = RecordingLogger()
        val catalog = FakeCatalog(failure = SourceRepoException(SourceRepoError.Mismatch))

        val failure = InstallSource.Default(catalog, logger)(MANGADEX)

        assertEquals(SourceRepoError.Mismatch, failure)
        assertEquals(LogPriority.WARN, logger.entries.single().priority)
    }

    @Test
    fun `an installed source is described by its bundle and names the repo offering it`() = runTest {
        val entry = observeCatalog(installed = listOf(MANGADEX_META), available = listOf(remote(MANGADEX))).first().single()

        assertTrue(entry.installed)
        assertEquals("MangaDex", entry.name.value)
        assertEquals(1, entry.version, "the installed version, not the one on offer")
        assertEquals("Tanuki Sources", entry.repoName)
    }

    @Test
    fun `an installable source is described by its repo's index`() = runTest {
        val entry =
            observeCatalog(available = listOf(remote(COMICK, name = "Comick", compatibility = SourceCompatibility.NeedsAppUpdate)))
                .first()
                .single()

        assertFalse(entry.installed)
        assertEquals("Comick", entry.name.value)
        assertEquals("en", entry.lang?.value)
        assertEquals(2, entry.version)
        assertEquals(SourceCompatibility.NeedsAppUpdate, entry.compatibility)
    }

    @Test
    fun `an installed source is never also offered as available`() = runTest {
        val catalog = observeCatalog(installed = listOf(MANGADEX_META), available = listOf(remote(MANGADEX), remote(COMICK))).first()

        assertEquals(listOf(COMICK, MANGADEX), catalog.map { it.sourceId })
        assertEquals(listOf(false, true), catalog.map { it.installed })
    }

    @Test
    fun `when two repos offer a source the runnable one wins`() = runTest {
        val entry =
            observeCatalog(
                available = listOf(
                    remote(COMICK, repoName = "Old", compatibility = SourceCompatibility.Outdated),
                    remote(COMICK, repoName = "Current")
                )
            ).first().single()

        assertEquals("Current", entry.repoName)
        assertEquals(SourceCompatibility.Compatible, entry.compatibility)
    }

    @Test
    fun `an unreadable catalog still lists what is installed`() = runTest {
        val logger = RecordingLogger()

        val catalog =
            ObserveSourceCatalog.Default(
                getInstalledSources = GetInstalledSources.Default(EnumeratingSourceLoader(listOf(MANGADEX_META)), logger),
                catalog = FakeCatalog(availableFailure = IllegalStateException("corrupt cache")),
                logger = logger
            )().first()

        assertEquals(listOf(MANGADEX), catalog.map { it.sourceId })
        assertEquals(LogPriority.WARN, logger.entries.single().priority)
    }

    private fun observeCatalog(
        installed: List<SourceMeta> = emptyList(),
        available: List<RemoteSource> = emptyList()
    ) = ObserveSourceCatalog.Default(
        getInstalledSources = GetInstalledSources.Default(EnumeratingSourceLoader(installed), RecordingLogger()),
        catalog = FakeCatalog(available = available),
        logger = RecordingLogger()
    )()
}

/** In-memory [SourceBundleStore]; only the calls this lifecycle makes are implemented. */
private class FakeBundleStore(
    installed: Set<String> = emptySet()
) : SourceBundleStore {
    var installed: Set<String> = installed
        private set

    override suspend fun removeBundle(sourceId: String) {
        installed = installed - sourceId
    }

    override suspend fun installedIds(): List<String> = installed.toList()

    override suspend fun isInstalled(sourceId: String): Boolean = sourceId in installed

    override fun observeInstalledIds(): Flow<List<String>> = flowOf(installed.toList())

    override suspend fun readBundle(sourceId: String): ByteArray? = error("unused")

    override suspend fun writeBundle(
        sourceId: String,
        bytes: ByteArray
    ) = error("unused")
}

/**
 * A [SourceCatalog] that records what it was asked to install. [failure] fails the install itself;
 * [availableFailure] fails the listing, standing in for a catalog that can't be read.
 */
private class FakeCatalog(
    private val available: List<RemoteSource> = emptyList(),
    private val failure: Throwable? = null,
    private val availableFailure: Throwable? = null
) : SourceCatalog {
    val installCalls = mutableListOf<String>()

    override fun observeAvailable(): Flow<List<RemoteSource>> =
        flow {
            availableFailure?.let { throw it }
            emit(available)
        }

    override suspend fun install(
        sourceId: String,
        onProgress: (InstallProgress) -> Unit
    ) {
        failure?.let { throw it }
        installCalls += sourceId
    }

    override fun observeRepos(): Flow<List<SourceRepo>> = error("unused")

    override suspend fun previewRepo(url: String): RepoPreview = error("unused")

    override suspend fun addRepo(preview: RepoPreview) = error("unused")

    override suspend fun removeRepo(baseUrl: String) = error("unused")

    override suspend fun refresh() = error("unused")
}

/** A [SourceLoader] that only enumerates; the fetch calls are never reached here. */
private class EnumeratingSourceLoader(
    private val metas: List<SourceMeta>
) : SourceLoader {
    override fun observeInstalledSources(): Flow<List<SourceMeta>> = flowOf(metas)

    override suspend fun loadSource(id: String): Source = error("unused")

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

    override suspend fun fetchChapterPages(
        sourceId: String,
        chapterId: String
    ): List<Page> = error("unused")
}
