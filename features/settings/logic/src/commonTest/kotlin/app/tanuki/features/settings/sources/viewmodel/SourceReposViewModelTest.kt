package app.tanuki.features.settings.sources.viewmodel

import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.common.service.LogPriority
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.source.SourceCatalog
import app.tanuki.core.domain.source.model.InstallProgress
import app.tanuki.core.domain.source.model.RemoteSource
import app.tanuki.core.domain.source.model.RepoPreview
import app.tanuki.core.domain.source.model.SourceRepo
import app.tanuki.core.domain.source.model.SourceRepoError
import app.tanuki.core.domain.source.model.SourceRepoException
import app.tanuki.core.domain.source.usecase.AddSourceRepo
import app.tanuki.core.domain.source.usecase.ObserveSourceRepos
import app.tanuki.core.domain.source.usecase.PreviewSourceRepo
import app.tanuki.core.domain.source.usecase.RefreshSourceRepos
import app.tanuki.core.domain.source.usecase.RemoveSourceRepo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val PREVIEW =
    RepoPreview(
        baseUrl = "https://tanuki-reader.github.io/tanuki-sources/",
        name = "Tanuki Sources",
        fingerprint = "4F2A 9C1E 77B0 D3A5\n0E6B 91C4 2D8F 6A13",
        sourceCount = 5,
        publicKey = "key",
        indexJson = "{}"
    )

/** Pins the add flow (preview, then confirm), and which failures become a dialog. */
@OptIn(ExperimentalCoroutinesApi::class)
class SourceReposViewModelTest {
    private val mainDispatcher = StandardTestDispatcher()
    private val catalog = FakeCatalog()
    private val viewModel =
        SourceReposViewModel(
            observeSourceRepos = ObserveSourceRepos.Default(catalog),
            previewSourceRepo = PreviewSourceRepo.Default(catalog),
            addSourceRepo = AddSourceRepo.Default(catalog),
            removeSourceRepo = RemoveSourceRepo.Default(catalog),
            refreshSourceRepos = RefreshSourceRepos.Default(catalog),
            dispatcher = MainDispatchers,
            logger = SilentLogger
        )

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a repo is added only after its preview is confirmed`() = runTest {
        val job = collect()

        viewModel.onAddRequested()
        viewModel.onUrlSubmitted("tanuki-reader.github.io/tanuki-sources")
        runCurrent()
        val preview = viewModel.uiState.value.preview
        assertFalse(viewModel.uiState.value.adding)
        assertEquals(5, preview?.sourceCount)
        assertEquals("tanuki-reader.github.io/tanuki-sources", preview?.host)
        assertTrue(catalog.repos.value.isEmpty(), "nothing is pinned before the user confirms")

        viewModel.onPreviewConfirmed()
        runCurrent()
        assertNull(viewModel.uiState.value.preview)
        assertEquals(listOf("Tanuki Sources"), viewModel.uiState.value.repos.map { it.name })
        job.cancel()
    }

    @Test
    fun `a failed preview keeps the address dialog open with the reason`() = runTest {
        catalog.previewFailure = SourceRepoError.Unsigned
        val job = collect()

        viewModel.onAddRequested()
        viewModel.onUrlSubmitted("example.org")
        runCurrent()

        assertTrue(viewModel.uiState.value.adding)
        assertEquals(SourceRepoError.Unsigned, viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.busy)
        job.cancel()
    }

    @Test
    fun `a refresh refused for trust is the repo's banner and not a dialog`() = runTest {
        catalog.refreshFailure = SourceRepoError.KeyChanged
        val job = collect()

        viewModel.onRefresh()
        runCurrent()

        assertNull(viewModel.uiState.value.refreshError)
        assertFalse(viewModel.uiState.value.refreshing)
        job.cancel()
    }

    @Test
    fun `a refresh that can't reach a repo is reported`() = runTest {
        catalog.refreshFailure = SourceRepoError.Offline
        val job = collect()

        viewModel.onRefresh()
        runCurrent()

        assertEquals(SourceRepoError.Offline, viewModel.uiState.value.refreshError)
        job.cancel()
    }

    private fun TestScope.collect() = backgroundScope.launch { viewModel.uiState.collect {} }.also { runCurrent() }
}

private class FakeCatalog : SourceCatalog {
    val repos = MutableStateFlow(emptyList<SourceRepo>())
    var previewFailure: SourceRepoError? = null
    var refreshFailure: SourceRepoError? = null

    override fun observeRepos(): Flow<List<SourceRepo>> = repos

    override suspend fun previewRepo(url: String): RepoPreview = previewFailure?.let { throw SourceRepoException(it) } ?: PREVIEW

    override suspend fun addRepo(preview: RepoPreview) {
        repos.value += SourceRepo(preview.baseUrl, preview.name, preview.fingerprint)
    }

    override suspend fun removeRepo(baseUrl: String) {
        repos.value = repos.value.filterNot { it.baseUrl == baseUrl }
    }

    override suspend fun refresh() {
        refreshFailure?.let { throw SourceRepoException(it) }
    }

    override fun observeAvailable(): Flow<List<RemoteSource>> = error("unused")

    override suspend fun install(
        sourceId: String,
        onProgress: (InstallProgress) -> Unit
    ) = error("unused")
}

private object MainDispatchers : DispatcherProvider {
    override val default: CoroutineDispatcher get() = Dispatchers.Main
    override val io: CoroutineDispatcher get() = Dispatchers.Main
    override val main: CoroutineDispatcher get() = Dispatchers.Main
}

private object SilentLogger : Logger {
    override fun log(
        priority: LogPriority,
        tag: String?,
        message: String?,
        throwable: Throwable?,
        analyticsEvent: String?,
        addToBreadcrumbs: Boolean
    ) = Unit

    override fun addReceiver(receiver: Logger.LogReceiver) = Unit

    override fun removeReceiver(receiver: Logger.LogReceiver) = Unit
}
