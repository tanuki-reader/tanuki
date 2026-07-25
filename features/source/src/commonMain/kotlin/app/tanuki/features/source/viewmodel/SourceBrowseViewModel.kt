package app.tanuki.features.source.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.common.service.warn
import app.tanuki.core.domain.manga.usecase.CacheSourceManga
import app.tanuki.core.domain.source.model.MangaListing
import app.tanuki.core.domain.source.model.SourceListing
import app.tanuki.core.domain.source.model.SourceMeta
import app.tanuki.core.domain.source.usecase.BrowseSource
import app.tanuki.core.domain.source.usecase.GetSourceMeta
import app.tanuki.core.navigation.MangaDetail
import app.tanuki.core.navigation.NavEvent
import app.tanuki.features.source.viewmodel.SourceBrowseState.Browsing
import app.tanuki.features.source.viewmodel.SourceBrowseState.Error
import app.tanuki.features.source.viewmodel.SourceBrowseState.Loading
import app.tanuki.features.source.viewmodel.SourceBrowseState.Results
import app.tanuki.features.source.viewmodel.SourceBrowseState.Searching
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Presentation logic for one source's browse screen. Assisted-injected with the source-native
 * [sourceId] the route carries (mirrors [app.tanuki.features.manga.viewmodel.MangaViewModel]); the UI
 * resolves it with `assistedMetroViewModel { create(sourceId) }`, scoped to the destination's back
 * stack entry.
 *
 * Two independent pipelines in [init], each with its own lifetime:
 *  1. **Metadata** — resolves [SourceMeta] into [meta], re-run on every [retries] emission. A source
 *     that can't describe itself has no tabs and no title → hard [Error] with a retry.
 *  2. **First page** — [meta] × [selectedListing] × debounced [searchQuery] × [retries], so the grid
 *     re-derives from its inputs the way the other screens' `combine` pipelines do. [collectLatest]
 *     cancels a superseded load when the tab or search changes, so only the newest query's results land.
 *
 * Keeping them separate is why a retry re-triggers both loads without tearing down and rebuilding the
 * tab/search collector — and why the effective listing is resolved in the collector rather than read
 * from fields written earlier by the other coroutine.
 *
 * The screen's chrome (title + tab row) is a **third**, purely derived state ([chrome]) rather than part
 * of [uiState]: it belongs to the source, not to a page load, so it must not blink out of existence
 * while one is in flight.
 *
 * That second pipeline is also what makes the [page] cursor safe. Every change of tab or query re-enters
 * [loadFirstPage], which resets [page] to 1 before fetching — so the cursor can never outlive the
 * (tab, query) pair it counts pages for, and switching tabs cannot leave the previous tab's index behind
 * for the next load-more to continue from.
 *
 * **Load-more** is the deliberate exception: an explicit [onLoadMore] the grid calls near its tail,
 * appending rather than replacing, guarded so overlapping scroll callbacks can't double-fetch. Paging
 * is a cursor over a suspend, failable fetch — not state derivable from a flow — so [page] stays a
 * plain field, safe because every mutation happens on the ViewModel's main-dispatched scope. It is the
 * one path the first-page pipeline can't supersede on its own, so [loadFirstPage] cancels it explicitly
 * (see [cancelLoadMore]) rather than letting a stale page append onto the grid that replaced it.
 *
 * Browsing and searching are separate states ([Browsing] / [Searching]) rather than one state carrying a
 * query, because a source can't search *within* a listing — [BrowseSource] owns that precedence, and the
 * state mirrors its outcome so the UI never has to infer which mode it's in from a blank string.
 */
@OptIn(FlowPreview::class)
@AssistedInject
class SourceBrowseViewModel(
    @Assisted private val sourceId: String,
    private val getSourceMeta: GetSourceMeta,
    private val browseSource: BrowseSource,
    private val cacheSourceManga: CacheSourceManga,
    private val dispatcher: DispatcherProvider,
    private val logger: Logger,
) : ViewModel() {
    val uiState: StateFlow<SourceBrowseState>
        field = MutableStateFlow<SourceBrowseState>(Loading)

    private val _navEvents = Channel<NavEvent>(Channel.BUFFERED)
    val navEvents: Flow<NavEvent> = _navEvents.receiveAsFlow()

    /** The source's metadata; `null` until resolved, which gates the first-page pipeline below. */
    private val meta = MutableStateFlow<SourceMeta?>(null)

    /**
     * Bumped by [onRetry] to re-run the metadata pipeline. A trigger flow rather than a restartable
     * `Job` so retrying doesn't also cancel and rebuild the unrelated first-page collector.
     */
    private val retries = MutableStateFlow(0)

    /**
     * The tab the user explicitly picked, or `null` while they haven't picked one — in which case the
     * source's first declared listing is used. Tracking "untouched" separately (rather than seeding this
     * from the resolved metadata) keeps the default correct without one pipeline writing another's input,
     * mirroring `HomeViewModel.expandedLangs`.
     */
    private val selectedListing = MutableStateFlow<SourceListing?>(null)

    /**
     * The search text, debounced to avoid a flood of first-page reloads. The UI binds to this and
     * calls [onQueryChange] to update it.
     */
    val searchQuery: StateFlow<String>
        field = MutableStateFlow("")

    /**
     * The screen's persistent chrome (title + tab row), derived from metadata × the picked tab — never
     * from a load. Split out of [uiState] so the bar and tabs render from state that doesn't cycle: they
     * describe the *source*, which doesn't change when a page is refetched. Two consequences: the UI can
     * compose them outside the `when (state)` so they can't unmount mid-load, and the tab thumb moves the
     * instant it's tapped rather than a network round trip later.
     */
    val chrome: StateFlow<SourceChrome> =
        combine(meta, selectedListing) { source, picked ->
            SourceChrome(
                title = source?.name?.value,
                listings = source?.listings.orEmpty(),
                selected = picked ?: source?.listings?.firstOrNull()
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, SourceChrome())

    /**
     * 1-based index of the last page loaded, reset by [loadFirstPage] on every tab/query change. Whether
     * a next page exists is *not* tracked here — it's already in [Results.append] (`End` = the source
     * reported no more), and one encoding is enough.
     */
    private var page = 1

    /**
     * The in-flight load-more, so a first-page reload can cancel it. Doubles as the re-entrancy guard —
     * [Results.append] being `Loading` already rejects overlapping calls, so a separate boolean would be
     * a second encoding of the same fact.
     */
    private var loadMoreJob: Job? = null

    init {
        // Metadata: re-resolved on each retry. Nothing else depends on this coroutine's ordering — the
        // pipeline below waits on `meta` itself rather than on this having run first.
        retries
            .onEach {
                // No withContext here or below: SourceLoader dispatches its own engine work, so wrapping
                // would only add a hop before it switched again.
                val resolved = getSourceMeta(sourceId)
                if (resolved == null) uiState.value = Error else meta.value = resolved
            }.launchIn(viewModelScope)

        // First page: derived from metadata × tab × debounced search, reloading on any change. [retries]
        // is an input too — the first page can fail while metadata succeeded, and re-resolving metadata
        // then yields an equal [SourceMeta] that the StateFlow conflates away, so a retry would emit
        // nothing here and the screen would sit on Loading forever.
        viewModelScope.launch {
            combine(
                meta.filterNotNull(),
                selectedListing,
                searchQuery.debounce(SEARCH_DEBOUNCE_MS),
                retries
            ) { source, listing, text, _ -> (listing ?: source.listings.firstOrNull()) to text }
                .collectLatest { (listing, text) -> loadFirstPage(listing, text) }
        }
    }

    /** A listing tab was tapped. Switching tabs clears any search so the tab's own catalog shows. */
    fun onListingSelect(listing: SourceListing) {
        // Compared against the *effective* tab, so re-tapping the default one (still `null` in
        // [selectedListing] until now) doesn't trigger a pointless reload of what's already shown.
        if (chrome.value.selected == listing) return
        selectedListing.value = listing
    }

    /** The search text changed; the debounced pipeline reloads the first page. */
    fun onQueryChange(text: String) {
        searchQuery.value = text
    }

    /**
     * Retry after a failed initial load. Both pipelines take [retries] as an input, so this re-resolves
     * the metadata *and* reloads the first page — either can be what failed — without cancelling or
     * rebuilding the tab/search collector.
     */
    fun onRetry() {
        uiState.value = Loading
        retries.value += 1
    }

    /**
     * The grid scrolled near its end — load the next page if one exists and none is already loading.
     * Appends to the current results rather than replacing them.
     */
    fun onLoadMore() {
        val results = uiState.value as? Results ?: return
        // Idle = more pages and none in flight; Error = the last attempt failed and may be retried.
        if (results.append == Append.End || results.append == Append.Loading) return
        uiState.value = results.withResults(results.items, Append.Loading)
        // Which of the two grids is paging decides what the next page is *of* — the state carries one or
        // the other, never both, so there's no precedence to re-derive here.
        val listing = if (results is Browsing) chrome.value.selected else null
        val query = (results as? Searching)?.query
        loadMoreJob =
            viewModelScope.launch {
                val result = runCatchingCancellable { browseSource(sourceId, listing, query, page + 1) }
                val current = uiState.value as? Results ?: return@launch
                result
                    .onSuccess { pageResult ->
                        page += 1
                        uiState.value =
                            current.withResults(
                                // Dedupe on append: listings like "Popular"/"Latest" reorder server-side
                                // while paging, so a title from page N routinely reappears on page N+1 —
                                // and duplicate keys are a hard crash in the grid's lazy layout. Keeping
                                // the first occurrence also stops already-visible tiles from jumping.
                                items =
                                    (current.items + pageResult.entries)
                                        .distinctBy(MangaListing::sourceMangaKey),
                                append = if (pageResult.hasNextPage) Append.Idle else Append.End
                            )
                    }.onFailure {
                        logger.warn(TAG, "Load-more (page ${page + 1}) failed for source '$sourceId'", it)
                        uiState.value = current.withResults(current.items, Append.Error)
                    }
            }
    }

    /**
     * A cover was tapped — cache the listing to a persisted manga, then open its detail screen. Wrapped
     * in `io` (unlike the browse calls above) because this one *does* write: the repository's suspend
     * methods don't dispatch, only its Flows do — same as [MangaViewModel]'s library actions.
     */
    fun onMangaClick(listing: MangaListing) {
        viewModelScope.launch {
            val mangaId = withContext(dispatcher.io) { cacheSourceManga(sourceId, listing) }
            _navEvents.trySend(NavEvent.NavTo(MangaDetail(mangaId)))
        }
    }

    /**
     * Loads (or reloads) the first page and replaces the grid with it. The effective [listing] is
     * resolved by the caller from the metadata that gated the pipeline, so this makes no ordering
     * assumption about the two coroutines in [init].
     */
    private suspend fun loadFirstPage(
        listing: SourceListing?,
        text: String
    ) {
        cancelLoadMore()
        // Every entry here is a genuine change of (tab, query) or an explicit retry, so the grid on
        // screen is about to be replaced wholesale — show the loader for the *content region* rather
        // than leaving the previous tab's covers up with no sign that anything happened. Safe to blank
        // now that the title and tabs live in [chrome] and render outside this state.
        uiState.value = Loading
        // Reset the cursor *here*, not in the success branch: it belongs to this (tab, query) pair, and
        // this function runs exactly once per distinct pair. Resetting on success only would let a failed
        // tab switch leave the previous tab's index behind for the next load-more, which would then skip
        // pages. Same reason a retry is safe — it re-enters here and starts from page 1.
        page = 1
        val result = runCatchingCancellable { browseSource(sourceId, listing, text, page = 1) }
        result
            .onSuccess { pageResult ->
                // A single page can repeat a title too; the grid's keys must be unique whatever the
                // source returns.
                val items = pageResult.entries.distinctBy(MangaListing::sourceMangaKey)
                val append = if (pageResult.hasNextPage) Append.Idle else Append.End
                uiState.value =
                    if (text.isBlank()) {
                        Browsing(items = items, append = append)
                    } else {
                        // A query replaced the listing, so this is the tab-less search screen — an empty
                        // result here means "nothing matched", not "this listing is empty".
                        Searching(query = text, items = items, append = append)
                    }
            }.onFailure {
                logger.warn(TAG, "First-page load failed for source '$sourceId' (query='$text')", it)
                uiState.value = Error
            }
    }

    /**
     * Cancels an in-flight load-more, so a page requested for the *previous* tab or search can't land on
     * the grid that replaced it — appending stale results and advancing [page] past a page never loaded.
     * No footer cleanup needed: [loadFirstPage] blanks straight to [Loading] afterwards, taking the
     * spinner row with it.
     */
    private fun cancelLoadMore() {
        loadMoreJob?.cancel()
        loadMoreJob = null
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
        const val TAG = "SourceBrowse"

        /** Debounce so a burst of keystrokes issues one search, not one per character. */
        const val SEARCH_DEBOUNCE_MS = 320L
    }
}

/**
 * [runCatching] that lets a [CancellationException] through instead of turning it into a `Failure`.
 *
 * Plain `runCatching` catches everything, cancellation included — so a load cancelled by a tab switch or
 * a superseded search would fall into the `onFailure` branch and paint an error over state that has
 * already moved on. Re-throwing keeps cancellation doing what it's supposed to: unwind silently.
 */
private inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    runCatching(block).onFailure { if (it is CancellationException) throw it }