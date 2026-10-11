package app.tanuki.features.source

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.Segment
import app.tanuki.assets.designSystem.component.TanukiDetailTopBar
import app.tanuki.assets.designSystem.component.TanukiEmptyState
import app.tanuki.assets.designSystem.component.TanukiLoadingIndicator
import app.tanuki.assets.designSystem.component.TanukiSegmentedControl
import app.tanuki.core.domain.source.model.SourceListing
import app.tanuki.core.navigation.Destination
import app.tanuki.core.navigation.NavEvent
import app.tanuki.features.source.composable.SourceGrid
import app.tanuki.features.source.resources.Res
import app.tanuki.features.source.resources.listing_latest
import app.tanuki.features.source.resources.listing_new
import app.tanuki.features.source.resources.listing_popular
import app.tanuki.features.source.resources.source_load_error
import app.tanuki.features.source.resources.source_results_empty
import app.tanuki.features.source.resources.source_retry
import app.tanuki.features.source.resources.source_search_empty
import app.tanuki.features.source.resources.source_search_hint
import app.tanuki.features.source.viewmodel.Append
import app.tanuki.features.source.viewmodel.SourceBrowseState.Browsing
import app.tanuki.features.source.viewmodel.SourceBrowseState.Error
import app.tanuki.features.source.viewmodel.SourceBrowseState.Loading
import app.tanuki.features.source.viewmodel.SourceBrowseState.Results
import app.tanuki.features.source.viewmodel.SourceBrowseState.Searching
import app.tanuki.features.source.viewmodel.SourceBrowseViewModel
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import org.jetbrains.compose.resources.stringResource

/**
 * Stateful entry point for one source's browse leaf. Resolves [SourceBrowseViewModel] assisted with the
 * [sourceId] the route carried, collects state, and forwards navigation: a [NavEvent.NavTo] (open a
 * tapped manga's detail, after it's cached) to [onNavigate], a [NavEvent.NavBack] to [onBack].
 */
@Composable
fun SourceRoute(
    sourceId: String,
    listing: String?,
    query: String?,
    onNavigate: (Destination) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel =
        assistedMetroViewModel<SourceBrowseViewModel, SourceBrowseViewModel.Factory> {
            create(sourceId, listing?.let(SourceListing::fromWireName), query)
        }

    LaunchedEffect(viewModel) {
        viewModel.navEvents.collect { event ->
            when (event) {
                is NavEvent.NavTo -> onNavigate(event.destination)
                NavEvent.NavBack -> onBack()
            }
        }
    }

    SourceScreen(
        modifier = modifier,
        fallbackTitle = sourceId,
        viewModel = viewModel,
        // Opened from the Source home's search: start on the search field.
        startSearching = query != null,
        onBack = onBack
    )
}

@Composable
private fun SourceScreen(
    fallbackTitle: String,
    viewModel: SourceBrowseViewModel,
    startSearching: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Bound to the VM's live query, not Searching.query — the latter only advances once a debounced
    // fetch lands, which would make the field lag a whole round trip behind the user's typing.
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val spacing = TanukiTheme.spacing
    // The bar and tab row read from `chrome`, not from `state`: they describe the source and outlive every
    // page load, so they stay composed while the content below cycles through Loading. Deriving them from
    // the loaded state instead put them inside the `Results` branch, where a tab switch unmounted them for
    // a frame — the flicker Home never had, because its segmented control sits outside `when (state)`.
    val chrome by viewModel.chrome.collectAsStateWithLifecycle()
    // Null until metadata lands; only then is there a real name to title the bar and search with.
    val title = chrome.title ?: fallbackTitle
    // Search now lives inside the top bar (same toggle as the Library), not a persistent pill below it.
    var searchActive by rememberSaveable { mutableStateOf(startSearching) }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
    ) {
        TanukiDetailTopBar(
            title = title,
            onBack = onBack,
            collapsed = true,
            // Only offer search once the source is resolved (its name is the placeholder subject).
            searchActive = searchActive && chrome.title != null,
            searchQuery = searchQuery,
            onSearchOpen = if (chrome.title != null) ({ searchActive = true }) else null,
            onSearchClose = {
                searchActive = false
                viewModel.onQueryChange("")
            },
            onSearchQueryChange = viewModel::onQueryChange,
            searchPlaceholder = stringResource(Res.string.source_search_hint, title)
        )

        // Tabs belong to browsing only: a search replaces the listing rather than filtering it, so there
        // is no active tab to highlight while the field is open. Driven by `searchActive` (a UI toggle)
        // rather than by the fetched state, so the row appears and disappears with the tap that caused it.
        if (chrome.listings.size >= 2 && !searchActive) {
            TanukiSegmentedControl(
                segments = chrome.listings.map { Segment(it, listingLabel(it)) },
                selected = chrome.selected ?: chrome.listings.first(),
                onSelect = viewModel::onListingSelect,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(TanukiTheme.spacing.gutter)
            )
        }

        // One fixed content slot under the chrome. Every state renders *inside* it, so switching tabs
        // swaps only what's in this box — the bar and tabs above never move or re-measure.
        Box(Modifier.weight(1f)) {
            when (val current = state) {
                Loading -> {
                    Box(Modifier.fillMaxSize(), Alignment.Center) { TanukiLoadingIndicator() }
                }

                Error -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(spacing.gutter),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        TanukiEmptyState(message = stringResource(Res.string.source_load_error))
                        Text(
                            text = stringResource(Res.string.source_retry),
                            style = TanukiTheme.typography.body,
                            color = TanukiTheme.colors.accent,
                            textAlign = TextAlign.Center,
                            modifier =
                                Modifier
                                    .padding(top = spacing.lg)
                                    .clickable(onClick = viewModel::onRetry)
                        )
                    }
                }

                is Results -> {
                    if (current.items.isEmpty() && current.append != Append.Loading) {
                        // A search that matched nothing names the query; an empty listing can't.
                        val message =
                            when (current) {
                                is Searching -> stringResource(Res.string.source_search_empty, current.query)
                                is Browsing -> stringResource(Res.string.source_results_empty)
                            }
                        Box(Modifier.fillMaxSize(), Alignment.Center) {
                            TanukiEmptyState(message = message)
                        }
                    } else {
                        SourceGrid(
                            items = current.items,
                            append = current.append,
                            appendRetryLabel = stringResource(Res.string.source_retry),
                            onLoadMore = viewModel::onLoadMore,
                            onRetryAppend = viewModel::onLoadMore,
                            onMangaClick = viewModel::onMangaClick
                        )
                    }
                }
            }
        }
    }
}

/**
 * The tab label for a contract listing. The label is the **app's**, not the source's — listings are a
 * closed set declared by the ABI, which is exactly what lets them be translated; a source-supplied name
 * could only ever arrive in one language.
 */
@Composable
internal fun listingLabel(listing: SourceListing): String =
    stringResource(
        when (listing) {
            SourceListing.POPULAR -> Res.string.listing_popular
            SourceListing.LATEST -> Res.string.listing_latest
            SourceListing.NEW -> Res.string.listing_new
        }
    )
