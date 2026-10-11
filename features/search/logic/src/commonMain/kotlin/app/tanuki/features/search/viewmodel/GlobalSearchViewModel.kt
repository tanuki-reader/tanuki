package app.tanuki.features.search.viewmodel

import androidx.lifecycle.viewModelScope
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.library.model.LibraryFilter
import app.tanuki.core.domain.library.model.LibraryManga
import app.tanuki.core.domain.library.model.LibrarySort
import app.tanuki.core.domain.library.usecase.ObserveLibrary
import app.tanuki.core.domain.manga.usecase.CacheSourceManga
import app.tanuki.core.domain.settings.usecase.ObserveShowSensitiveContent
import app.tanuki.core.domain.source.model.MangaListing
import app.tanuki.core.domain.source.model.SourceMeta
import app.tanuki.core.domain.source.usecase.ObserveLibraryKeys
import app.tanuki.core.domain.source.usecase.ObserveVisibleSources
import app.tanuki.core.domain.source.usecase.SearchSources
import app.tanuki.core.domain.source.usecase.SourceSearch
import app.tanuki.core.navigation.MangaDetail
import app.tanuki.core.navigation.SourceCatalogGrid
import app.tanuki.core.presentation.StateViewModel
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/**
 * Global search: one query across the library and every installed source.
 *
 * - **Library** matches update as the user types (a local filter).
 * - **Sources** are searched only when the query is submitted: a request per source per keystroke would
 *   hammer every site (and its Cloudflare front). Each source's result lands in its own rail as it
 *   answers ([SearchSources]).
 *
 * Results are held here ([runs]), not in the derived pipeline, so going back from a series to this screen
 * shows them again rather than searching anew.
 *
 * A submitted search follows the visible sources ([ObserveVisibleSources]) live: a source installed or
 * allowed by the sensitive toggle afterwards gets its rail and is searched on its own, one hidden or
 * uninstalled loses its rail, and a source already searched keeps its answer. Flipping the toggle searches
 * every source again: sources leave adult titles out when the request is made, so every answer fetched
 * under the old setting is stale.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@AssistedInject
class GlobalSearchViewModel(
    @Assisted initialQuery: String?,
    private val observeVisibleSources: ObserveVisibleSources,
    private val observeShowSensitiveContent: ObserveShowSensitiveContent,
    private val searchSources: SearchSources,
    private val cacheSourceManga: CacheSourceManga,
    observeLibrary: ObserveLibrary,
    observeLibraryKeys: ObserveLibraryKeys,
    dispatcher: DispatcherProvider,
    logger: Logger
) : StateViewModel<GlobalSearchState>(dispatcher, logger, GlobalSearchState()) {
    /** A submitted search: its query and the sources it shows, in source-list order. */
    private class Search(
        val query: String,
        val sources: List<SourceMeta>
    )

    private val query = MutableStateFlow(initialQuery.orEmpty())
    private val searched = MutableStateFlow<Search?>(null)

    /** Each source's answer for [searched]; a source missing from it is still loading. */
    private val runs = MutableStateFlow<Map<String, SourceSearch>>(emptyMap())
    private var searchJob: Job? = null

    /** Replaced whenever [runs] is reset, so a retry still in flight can tell its answer is stale. */
    private var batch = Any()

    init {
        if (!initialQuery.isNullOrBlank()) onSubmit()
    }

    private val libraryHits: Flow<List<LibraryHit>> =
        observeLibrary(query, flowOf(LibrarySort.Default), flowOf(LibraryFilter.None))
            .combine(query) { library, typed ->
                if (typed.isBlank()) emptyList() else library.map { it.toHit() }
            }

    private val libraryKeys: Flow<Map<String, Set<String>>> =
        searched.map { search -> search?.sources.orEmpty().map { it.id } }.distinctUntilChanged().flatMapLatest { ids ->
            if (ids.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(ids.map { id -> observeLibraryKeys(id).onStart { emit(emptySet()) }.map { id to it } }) { it.toMap() }
            }
        }

    override val state: Flow<GlobalSearchState> =
        combine(query, libraryHits, searched, runs, libraryKeys) { typed, hits, search, runs, keys ->
            GlobalSearchState(
                query = typed,
                library = hits,
                rails = search?.sources.orEmpty().map { it.toRail(runs[it.id], keys[it.id].orEmpty()) }
            )
        }

    fun onQueryChange(text: String) {
        query.value = text
    }

    /** Searches every visible source for the typed query (blank does nothing). */
    fun onSubmit() {
        val text = query.value.trim()
        if (text.isEmpty()) return
        searchJob?.cancel()
        // ponytail: holds the toggle and source-list subscriptions until the next submit; they only emit
        // on an install or toggle change, so they cost nothing at rest.
        searchJob =
            viewModelScope.launch {
                // A toggle change drops every answer and the searches still running for the old setting.
                observeShowSensitiveContent().distinctUntilChanged().collectLatest {
                    batch = Any()
                    runs.value = emptyMap()
                    val searchedIds = mutableSetOf<String>()
                    coroutineScope {
                        observeVisibleSources().collect { sources ->
                            searched.value = Search(text, sources)
                            val fresh = sources.map { it.id }.filterNot { it in searchedIds }
                            if (fresh.isEmpty()) return@collect
                            searchedIds += fresh
                            launch { searchSources(text, fresh).collect { (id, result) -> runs.update { it + (id to result) } } }
                        }
                    }
                }
            }
    }

    /** Searches one source again, for the rail that failed. */
    fun onRetry(sourceId: String) {
        val search = searched.value ?: return
        val retried = batch
        runs.update { it - sourceId }
        viewModelScope.launch {
            searchSources(search.query, listOf(sourceId)).collect { (id, result) ->
                // A newer search or a toggle change replaced this one while it ran: its answer is stale.
                if (batch === retried) runs.update { it + (id to result) }
            }
        }
    }

    fun onResultClick(
        sourceId: String,
        key: String
    ) {
        val listing = (runs.value[sourceId] as? SourceSearch.Loaded)?.entries?.firstOrNull { it.sourceMangaKey == key } ?: return
        viewModelScope.launch {
            tryIo("Couldn't open '$key' from source '$sourceId'") { cacheSourceManga(sourceId, listing) }
                .onSuccess { navigateTo(MangaDetail(it)) }
        }
    }

    fun onLibraryClick(mangaId: Uuid) = navigateTo(MangaDetail(mangaId))

    /** That source's own catalog, searching the same query — its full, paged results. */
    fun onSeeAll(sourceId: String) {
        val search = searched.value ?: return
        navigateTo(SourceCatalogGrid(sourceId, query = search.query))
    }

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    fun interface Factory : ManualViewModelAssistedFactory {
        fun create(initialQuery: String?): GlobalSearchViewModel
    }
}

private fun LibraryManga.toHit() =
    LibraryHit(id, title.value, cover.url?.value, cover.headers)

private fun SourceMeta.toRail(
    result: SourceSearch?,
    libraryKeys: Set<String>
): SearchRail {
    val entries = (result as? SourceSearch.Loaded)?.entries.orEmpty()
    val status =
        when {
            result == null || result == SourceSearch.Loading -> SearchStatus.Loading
            result == SourceSearch.Failed -> SearchStatus.Failed
            entries.isEmpty() -> SearchStatus.Empty
            else -> SearchStatus.Loaded
        }
    return SearchRail(id, name.value, lang.value, status, entries.map { it.toItem(it.sourceMangaKey in libraryKeys) })
}

private fun MangaListing.toItem(inLibrary: Boolean) =
    SearchItem(sourceMangaKey, title, coverUrl?.value, coverHeaders, inLibrary)
