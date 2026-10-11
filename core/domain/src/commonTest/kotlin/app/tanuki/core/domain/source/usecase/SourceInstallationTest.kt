package app.tanuki.core.domain.source.usecase

import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.common.model.Url
import app.tanuki.core.domain.common.service.LogPriority
import app.tanuki.core.domain.common.service.RecordingLogger
import app.tanuki.core.domain.library.model.LibraryPreferences
import app.tanuki.core.domain.settings.model.AutoDownloadMode
import app.tanuki.core.domain.settings.model.DeleteAfterReading
import app.tanuki.core.domain.settings.model.LibraryGridSize
import app.tanuki.core.domain.settings.model.LibraryUpdateInterval
import app.tanuki.core.domain.settings.model.ThemePreference
import app.tanuki.core.domain.settings.repository.SettingsRepository
import app.tanuki.core.domain.source.SourceBundleStore
import app.tanuki.core.domain.source.SourceCatalog
import app.tanuki.core.domain.source.SourceLoader
import app.tanuki.core.domain.source.model.ChapterListing
import app.tanuki.core.domain.source.model.MangaListing
import app.tanuki.core.domain.source.model.MangaPage
import app.tanuki.core.domain.source.model.Page
import app.tanuki.core.domain.source.model.Source
import app.tanuki.core.domain.source.model.SourceListing
import app.tanuki.core.domain.source.model.SourceMeta
import app.tanuki.core.domain.source.repository.SourceRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

private const val MANGADEX = "mangadex"
private const val COMICK = "comick"

private val MANGADEX_META =
    SourceMeta(id = MANGADEX, name = Text("MangaDex"), lang = Text("en"), version = 1)

private val MANGADEX_ROW =
    Source(
        id = Uuid.random(),
        sourceId = MANGADEX,
        lang = Text("en"),
        name = Text("MangaDex"),
        url = Url("https://mangadex.org"),
        iconUrl = Url("https://mangadex.org/icon.png")
    )

/**
 * Tests the source install/uninstall lifecycle: [UninstallSource], [InstallSource], and the
 * [ObserveSourceCatalog] stream the management screen renders.
 *
 * That an uninstall **keeps** the persisted source row — the whole reason removing a source is safe to
 * offer, since deleting it would cascade the user's library away — is guaranteed structurally rather
 * than here: [UninstallSource] has no [SourceRepository] dependency to delete through. What is tested is
 * everything that isn't free:
 *  - an uninstall is recorded, so it survives the launch-time seeding rather than being silently undone;
 *  - installing again clears that record, so the same source isn't treated as removed forever;
 *  - a source with no bundle can still be described, from the row the uninstall preserved, so the screen
 *    offers "MangaDex" back rather than a bare slug.
 */
class SourceInstallationTest {

    @Test
    fun `uninstalling removes the bundle and records the removal`() = runTest {
        val store = FakeBundleStore(installed = setOf(MANGADEX))
        val settings = FakeSettingsRepository()

        UninstallSource.Default(store, settings)(MANGADEX)

        assertEquals(emptySet(), store.installed)
        assertEquals(setOf(MANGADEX), settings.removedSourceIds)
    }

    @Test
    fun `uninstalling a source that isn't installed still records the intent`() = runTest {
        val settings = FakeSettingsRepository()

        UninstallSource.Default(FakeBundleStore(), settings)(MANGADEX)

        assertEquals(setOf(MANGADEX), settings.removedSourceIds)
    }

    @Test
    fun `installing writes the bundle and clears the removal`() = runTest {
        val catalog = FakeCatalog(available = listOf(MANGADEX))
        val settings = FakeSettingsRepository(removed = mutableSetOf(MANGADEX))

        val installed = InstallSource.Default(catalog, settings, RecordingLogger())(MANGADEX)

        assertTrue(installed)
        assertEquals(listOf(MANGADEX), catalog.installCalls)
        assertEquals(emptySet(), settings.removedSourceIds)
    }

    @Test
    fun `a failed install reports failure and leaves the removal recorded`() = runTest {
        val settings = FakeSettingsRepository(removed = mutableSetOf(MANGADEX))
        val logger = RecordingLogger()

        val installed =
            InstallSource.Default(
                FakeCatalog(failure = IllegalStateException("no bundle")),
                settings,
                logger
            )(MANGADEX)

        assertFalse(installed)
        assertEquals(setOf(MANGADEX), settings.removedSourceIds)
        assertEquals(LogPriority.WARN, logger.entries.single().priority)
    }

    @Test
    fun `the catalog describes an installed source from its bundle metadata`() = runTest {
        val catalog =
            observeCatalog(
                installed = listOf(MANGADEX_META),
                persisted = emptyList(),
                available = listOf(MANGADEX)
            ).first()

        val entry = catalog.single()
        assertTrue(entry.installed)
        assertEquals(MANGADEX, entry.sourceId)
        assertEquals("MangaDex", entry.name.value)
        assertEquals("en", entry.lang?.value)
    }

    @Test
    fun `an uninstalled source is described from the row the uninstall preserved`() = runTest {
        val catalog =
            observeCatalog(
                installed = emptyList(),
                persisted = listOf(MANGADEX_ROW),
                available = listOf(MANGADEX)
            ).first()

        val entry = catalog.single()
        assertFalse(entry.installed)
        assertEquals("MangaDex", entry.name.value)
        assertEquals("en", entry.lang?.value)
        assertEquals("https://mangadex.org/icon.png", entry.iconUrl?.value)
    }

    @Test
    fun `a source that has never been installed falls back to its id`() = runTest {
        val catalog =
            observeCatalog(
                installed = emptyList(),
                persisted = emptyList(),
                available = listOf(COMICK)
            ).first()

        val entry = catalog.single()
        assertEquals(COMICK, entry.name.value)
        assertNull(entry.lang)
        assertFalse(entry.installed)
    }

    @Test
    fun `an installed source is never also offered as available`() = runTest {
        val catalog =
            observeCatalog(
                installed = listOf(MANGADEX_META),
                persisted = listOf(MANGADEX_ROW),
                available = listOf(MANGADEX, COMICK)
            ).first()

        assertEquals(listOf(COMICK, MANGADEX), catalog.map { it.sourceId })
        assertEquals(listOf(false, true), catalog.map { it.installed })
    }

    @Test
    fun `an unreadable catalog still lists what is installed`() = runTest {
        val logger = RecordingLogger()

        val catalog =
            ObserveSourceCatalog.Default(
                getInstalledSources = GetInstalledSources.Default(EnumeratingSourceLoader(listOf(MANGADEX_META)), logger),
                observeSources = ObserveSources.Default(FakeSourceRepository()),
                catalog = FakeCatalog(availableFailure = IllegalStateException("offline")),
                logger = logger
            )().first()

        assertEquals(listOf(MANGADEX), catalog.map { it.sourceId })
        assertEquals(LogPriority.WARN, logger.entries.single().priority)
    }

    /** Builds the use case under test from the three things it joins, so each case reads as its inputs. */
    private fun observeCatalog(
        installed: List<SourceMeta>,
        persisted: List<Source>,
        available: List<String>
    ) = ObserveSourceCatalog.Default(
        getInstalledSources = GetInstalledSources.Default(EnumeratingSourceLoader(installed), RecordingLogger()),
        observeSources = ObserveSources.Default(FakeSourceRepository(persisted)),
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
 * [availableFailure] fails the enumeration, standing in for a catalog that can't be reached.
 */
private class FakeCatalog(
    private val available: List<String> = emptyList(),
    private val failure: Throwable? = null,
    private val availableFailure: Throwable? = null
) : SourceCatalog {
    val installCalls = mutableListOf<String>()

    override suspend fun availableIds(): List<String> = availableFailure?.let { throw it } ?: available

    override suspend fun install(sourceId: String) {
        failure?.let { throw it }
        installCalls += sourceId
    }
}

/** Records the removal set; the settings this lifecycle doesn't touch are unused. */
private class FakeSettingsRepository(
    removed: MutableSet<String> = mutableSetOf()
) : SettingsRepository {
    var removedSourceIds: Set<String> = removed
        private set

    override suspend fun getRemovedSourceIds(): Set<String> = removedSourceIds

    override suspend fun setSourceRemoved(
        sourceId: String,
        removed: Boolean
    ) {
        removedSourceIds = if (removed) removedSourceIds + sourceId else removedSourceIds - sourceId
    }

    override fun observeThemePreference(): Flow<ThemePreference> = error("unused")

    override suspend fun setThemePreference(preference: ThemePreference) = error("unused")

    override fun observeCrashReportingEnabled(): Flow<Boolean> = error("unused")

    override suspend fun setCrashReportingEnabled(enabled: Boolean) = error("unused")

    override suspend fun getOrCreateInstallId(): String = error("unused")

    override suspend fun resetInstallId(): String = error("unused")

    override fun observeLibraryUpdateInterval(): Flow<LibraryUpdateInterval> = error("unused")

    override suspend fun setLibraryUpdateInterval(interval: LibraryUpdateInterval) = error("unused")

    override suspend fun getLastLibraryUpdate(): Instant? = error("unused")

    override suspend fun setLastLibraryUpdate(at: Instant) = error("unused")

    override fun observeDownloadsPaused(): Flow<Boolean> = error("unused")

    override suspend fun setDownloadsPaused(paused: Boolean) = error("unused")

    override fun observeAutoDownloadMode(): Flow<AutoDownloadMode> = error("unused")

    override fun observeAutoDownloadCategories(): Flow<Set<Uuid>> = error("unused")

    override suspend fun setAutoDownload(
        mode: AutoDownloadMode,
        categories: Set<Uuid>
    ) = error("unused")

    override fun observeDownloadOnWifiOnly(): Flow<Boolean> = error("unused")

    override suspend fun setDownloadOnWifiOnly(wifiOnly: Boolean) = error("unused")

    override fun observeDeleteAfterReading(): Flow<DeleteAfterReading> = error("unused")


    override fun observeLibraryPreferences(): Flow<LibraryPreferences> = error("unused")



    override fun observeLibraryGridSize(): Flow<LibraryGridSize> = error("unused")



    override suspend fun setLibraryGridSize(size: LibraryGridSize) = error("unused")


    override suspend fun updateLibraryPreferences(

        transform: (LibraryPreferences) -> LibraryPreferences

    ) = error("unused")

    override suspend fun setDeleteAfterReading(setting: DeleteAfterReading) = error("unused")
}

/** A [SourceRepository] over a fixed set of rows — what survives an uninstall. */
private class FakeSourceRepository(
    private val sources: List<Source> = emptyList()
) : SourceRepository {
    override fun observeSources(): Flow<List<Source>> = flowOf(sources)

    override suspend fun getSource(id: Uuid): Source? = sources.firstOrNull { it.id == id }

    override suspend fun getSourceByNativeId(sourceId: String): Source? = sources.firstOrNull { it.sourceId == sourceId }

    override suspend fun addSource(source: Source) = error("unused")

    override suspend fun deleteSource(source: Source) = error("unused")
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
