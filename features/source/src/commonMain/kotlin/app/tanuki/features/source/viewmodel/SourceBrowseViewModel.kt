package app.tanuki.features.source.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.manga.usecase.CacheSourceManga
import app.tanuki.core.domain.source.SourceLoader
import app.tanuki.core.domain.source.model.MangaListing
import app.tanuki.core.navigation.MangaDetail
import app.tanuki.core.navigation.NavEvent
import app.tanuki.features.source.viewmodel.SourceBrowseState.Content
import app.tanuki.features.source.viewmodel.SourceBrowseState.Error
import app.tanuki.features.source.viewmodel.SourceBrowseState.Loading
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Presentation logic for one source's browse screen. Assisted-injected with the source-native
 * [sourceId] the route carries (mirrors [app.tanuki.features.manga.viewmodel.MangaViewModel]); the UI
 * resolves it with `assistedMetroViewModel { create(sourceId) }`, scoped to the destination's back
 * stack entry.
 *
 * Two loading paths, deliberately different:
 *  - **First page** — driven reactively by [selectedListing] × debounced [query]. [collectLatest]
 *    cancels an in-flight first-page load when the tab or search changes, so only the latest wins (no
 *    out-of-order results). Replaces the grid.
 *  - **Load-more** — an explicit [onLoadMore] the grid calls near its tail. Appends to the grid and is
 *    guarded so overlapping scroll callbacks can't double-fetch a page.
 *
 * Paging cursors ([page]/[hasNext]) are plain fields, safe because every mutation happens on the
 * ViewModel's main-dispatched scope. A search overrides the listing (the source can't combine them),
 * matching the guest's own precedence.
 */
@OptIn(FlowPreview::class)
@AssistedInject
class SourceBrowseViewModel(
    @Assisted private val sourceId: String,
    private val sourceLoader: SourceLoader,
    private val cacheSourceManga: CacheSourceManga,
    private val dispatcher: DispatcherProvider
) : ViewModel() {
    val uiState: StateFlow<SourceBrowseState>
        field = MutableStateFlow<SourceBrowseState>(Loading)

    private val _navEvents = Channel<NavEvent>(Channel.BUFFERED)
    val navEvents: Flow<NavEvent> = _navEvents.receiveAsFlow()

    private val selectedListing = MutableStateFlow<String?>(null)
    private val query = MutableStateFlow("")

    /**
     * The live search text, echoed back to the search field. Exposed separately from
     * [SourceBrowseState.Content.query] — which only advances once a *debounced* fetch lands — because
     * binding the field to that would make every keystroke wait out the debounce plus a network round
     * trip before it appeared (and never appear at all when the fetch failed).
     */
    val searchQuery: StateFlow<String> = query.asStateFlow()

    /** Human-readable source name, resolved once from metadata; falls back to the id until then. */
    private var sourceName: String = sourceId
    private var listings = emptyList<app.tanuki.core.domain.source.model.SourceListing>()

    /** 1-based index of the last page loaded; [hasNext] is what the source reported for it. */
    private var page = 1
    private var hasNext = false
    private var loadingMore = false

    /** The metadata-load + first-page pipeline job, cancelled and restarted by a retry. */
    private var initJob: Job? = null

    init {
        initialize()
    }

    /**
     * Loads the source's metadata, then starts the first-page pipeline. Restartable: [onRetry] calls it
     * again after an initial failure, cancelling any previous run so there's never a second collector.
     */
    private fun initialize() {
        initJob?.cancel()
        initJob = viewModelScope.launch {
            // Resolve metadata first so the tabs and title are known before the first grid load. A
            // failure here means the source can't even describe itself → hard Error with a retry.
            val meta = withContext(dispatcher.default) {
                runCatching { sourceLoader.listSources().firstOrNull { it.id == sourceId } }.getOrNull()
            }
            if (meta == null) {
                uiState.value = Error
                return@launch
            }
            sourceName = meta.name.value
            listings = meta.listings
            selectedListing.value = meta.listings.firstOrNull()?.key

            // First-page pipeline: reload whenever the tab or (debounced) search changes; collectLatest
            // drops a superseded load so only the newest query's results land.
            combine(selectedListing, query.debounce(SEARCH_DEBOUNCE_MS)) { listing, text ->
                listing to text
            }.collectLatest { (listing, text) ->
                loadFirstPage(listing, text)
            }
        }
    }

    /** A listing tab was tapped. Switching tabs clears any search so the tab's own catalog shows. */
    fun onListingSelect(key: String) {
        if (selectedListing.value == key && query.value.isBlank()) return
        query.value = ""
        selectedListing.value = key
    }

    /** The search text changed; the debounced pipeline reloads the first page. */
    fun onQueryChange(text: String) {
        query.value = text
    }

    /** Retry after a failed initial load — restarts metadata resolution and the first-page pipeline. */
    fun onRetry() {
        uiState.value = Loading
        initialize()
    }

    /**
     * The grid scrolled near its end — load the next page if one exists and none is already loading.
     * Appends to the current results rather than replacing them.
     */
    fun onLoadMore() {
        val content = uiState.value as? Content ?: return
        if (loadingMore || !hasNext || content.append == Append.End) return
        loadingMore = true
        uiState.value = content.copy(append = Append.Loading)
        viewModelScope.launch {
            val listingArg = if (content.query.isBlank()) content.selectedListingKey else null
            val result = withContext(dispatcher.default) {
                runCatching {
                    sourceLoader.fetchMangaList(sourceId, listingArg, content.query.ifBlank { null }, page + 1)
                }
            }
            val current = uiState.value as? Content
            if (current != null) {
                result.onSuccess { pageResult ->
                    page += 1
                    hasNext = pageResult.hasNextPage
                    uiState.value = current.copy(
                        items = current.items + pageResult.entries,
                        append = if (pageResult.hasNextPage) Append.Idle else Append.End
                    )
                }.onFailure {
                    uiState.value = current.copy(append = Append.Error)
                }
            }
            loadingMore = false
        }
    }

    /** A cover was tapped — cache the listing to a persisted manga, then open its detail screen. */
    fun onMangaClick(listing: MangaListing) {
        viewModelScope.launch {
            val mangaId = withContext(dispatcher.default) { cacheSourceManga(sourceId, listing) }
            _navEvents.trySend(NavEvent.NavTo(MangaDetail(mangaId)))
        }
    }

    private suspend fun loadFirstPage(listing: String?, text: String) {
        val listingArg = if (text.isBlank()) listing else null
        val result = withContext(dispatcher.default) {
            runCatching { sourceLoader.fetchMangaList(sourceId, listingArg, text.ifBlank { null }, page = 1) }
        }
        result.onSuccess { pageResult ->
            page = 1
            hasNext = pageResult.hasNextPage
            uiState.value = Content(
                sourceName = sourceName,
                listings = listings,
                selectedListingKey = listing,
                query = text,
                items = pageResult.entries,
                append = if (pageResult.hasNextPage) Append.Idle else Append.End
            )
        }.onFailure {
            // Keep an existing grid on a transient reload failure; only blank to Error on the very first.
            if (uiState.value !is Content) uiState.value = Error
        }
    }

    /**
     * Assisted factory for [SourceBrowseViewModel], binding the runtime [sourceId] the route supplies.
     * Registered into the app's factory multibinding, resolved with `assistedMetroViewModel`.
     */
    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    fun interface Factory : ManualViewModelAssistedFactory {
        fun create(sourceId: String): SourceBrowseViewModel
    }

    private companion object {
        /** Debounce so a burst of keystrokes issues one search, not one per character. */
        const val SEARCH_DEBOUNCE_MS = 320L
    }
}
