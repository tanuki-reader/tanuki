package app.tanuki.features.source.viewmodel

import androidx.lifecycle.viewModelScope
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.common.service.warn
import app.tanuki.core.domain.manga.usecase.CacheSourceManga
import app.tanuki.core.domain.source.model.MangaListing
import app.tanuki.core.domain.source.model.SourceListing
import app.tanuki.core.domain.source.model.SourceMeta
import app.tanuki.core.domain.source.usecase.BrowseSource
import app.tanuki.core.domain.source.usecase.GetSourceHome
import app.tanuki.core.domain.source.usecase.GetSourceMeta
import app.tanuki.core.domain.source.usecase.ObserveLibraryKeys
import app.tanuki.core.navigation.MangaDetail
import app.tanuki.core.navigation.SourceCatalogGrid
import app.tanuki.core.presentation.StateViewModel
import app.tanuki.core.presentation.runCatchingCancellable
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * A source's home (the Source home redesign): rails for each listing the source declares, then a
 * preview of its default catalog. "See all" and a submitted search open [SourceCatalogGrid], the paged grid.
 *
 * The rails load once, when the screen is created, into an input the state is derived from — not on
 * every subscription, so coming back from a manga doesn't refetch the source. Each rail loads and
 * retries on its own.
 */
@AssistedInject
class SourceHomeViewModel(
    @Assisted private val sourceId: String,
    private val getSourceMeta: GetSourceMeta,
    private val getSourceHome: GetSourceHome,
    private val browseSource: BrowseSource,
    private val cacheSourceManga: CacheSourceManga,
    observeLibraryKeys: ObserveLibraryKeys,
    dispatcher: DispatcherProvider,
    logger: Logger
) : StateViewModel<SourceHomeState>(dispatcher, logger, SourceHomeState.Loading) {
    /** `null` until resolved; a failed resolve ends in [SourceHomeState.Error]. */
    private val meta = MutableStateFlow<SourceMeta?>(null)
    private val failed = MutableStateFlow(false)

    /** Each rail's first page by listing (`null` key = the default catalog); absent = still loading. */
    private val pages = MutableStateFlow<Map<SourceListing?, List<MangaListing>?>>(emptyMap())

    private val searchQuery = MutableStateFlow("")

    init {
        viewModelScope.launch { load() }
    }

    override val state: Flow<SourceHomeState> =
        combine(
            meta,
            failed,
            pages,
            searchQuery,
            observeLibraryKeys(sourceId).onStart { emit(emptySet()) }
        ) { meta, failed, pages, query, libraryKeys ->
            when {
                failed -> SourceHomeState.Error
                meta == null -> SourceHomeState.Loading
                else -> {
                    val now = Clock.System.now()
                    SourceHomeState.Content(
                        title = meta.name.value,
                        lang = meta.lang.value,
                        searchQuery = query,
                        rails =
                            (meta.listings + null).map { listing ->
                                val entries = pages[listing]
                                when {
                                    listing !in pages -> SourceRail(listing, RailStatus.Loading, emptyList())
                                    entries == null -> SourceRail(listing, RailStatus.Failed, emptyList())
                                    else ->
                                        SourceRail(
                                            listing = listing,
                                            status = RailStatus.Loaded,
                                            items = entries.map { it.toItem(it.sourceMangaKey in libraryKeys, now) }
                                        )
                                }
                            }
                    )
                }
            }
        }

    fun onSearchQueryChange(query: String) {
        searchQuery.value = query
    }

    /** The search was submitted — run it on the full catalog. A blank query does nothing. */
    fun onSearchSubmit() {
        val query = searchQuery.value.trim()
        if (query.isNotEmpty()) navigateTo(SourceCatalogGrid(sourceId, query = query))
    }

    /** Android's search button: the catalog, opened on its search field. */
    fun onSearchClick() {
        navigateTo(SourceCatalogGrid(sourceId, query = ""))
    }

    /** A rail's "See all": that listing's full, paged catalog. */
    fun onSeeAll(listing: SourceListing?) {
        navigateTo(SourceCatalogGrid(sourceId, listing = listing?.wireName))
    }

    /** A series was tapped — cache it (the detail screen reads it locally) and open it. */
    fun onMangaClick(key: String) {
        val listing = pages.value.values.firstNotNullOfOrNull { page -> page?.firstOrNull { it.sourceMangaKey == key } } ?: return
        viewModelScope.launch {
            tryIo("Couldn't open '$key' from source '$sourceId'") { cacheSourceManga(sourceId, listing) }
                .onSuccess { navigateTo(MangaDetail(it)) }
        }
    }

    /** Retry one failed rail. */
    fun onRetryRail(listing: SourceListing?) {
        pages.update { it - listing }
        viewModelScope.launch {
            val entries = runCatchingCancellable { browseSource(sourceId, listing).entries }.getOrNull()
            pages.update { it + (listing to entries) }
        }
    }

    /** Retry the whole screen, after [SourceHomeState.Error]. */
    fun onRetry() {
        failed.value = false
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val resolved = runCatchingCancellable { getSourceMeta(sourceId) }.getOrNull()
        if (resolved == null) {
            failed.value = true
            return
        }
        meta.value = resolved
        val rails = getSourceHome(sourceId, resolved.listings)
        pages.value = rails.associate { it.listing to it.entries }
        rails.filter { it.entries == null }.forEach {
            logger.warn(tag, "Rail '${it.listing?.wireName ?: "default"}' failed for source '$sourceId'")
        }
    }

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    fun interface Factory : ManualViewModelAssistedFactory {
        fun create(sourceId: String): SourceHomeViewModel
    }
}
