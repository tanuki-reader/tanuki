package app.tanuki.features.browse.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.source.SourceLoader
import app.tanuki.core.navigation.NavEvent
import app.tanuki.core.navigation.SourceBrowse
import app.tanuki.features.browse.viewmodel.BrowseCatalogState.Content
import app.tanuki.features.browse.viewmodel.BrowseCatalogState.Loading
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Presentation logic for the Browse tab root — lists installed sources and routes a tap to that
 * source's browse screen. Compose-free (CLAUDE.md → MVVM); Metro-injected into the ViewModel
 * multibinding so `metroViewModel<BrowseCatalogViewModel>()` resolves it.
 *
 * Enumerating sources means reading each installed bundle's metadata via [SourceLoader.listSources];
 * done once on init, off the main thread. A source that fails to decode is skipped by the loader, so
 * one bad bundle can't blank the list.
 */
@Inject
@ContributesIntoMap(AppScope::class)
@ViewModelKey
class BrowseCatalogViewModel(
    private val sourceLoader: SourceLoader,
    private val dispatcher: DispatcherProvider
) : ViewModel() {
    val uiState: StateFlow<BrowseCatalogState>
        field = MutableStateFlow<BrowseCatalogState>(Loading)

    private val _navEvents = Channel<NavEvent>(Channel.BUFFERED)
    val navEvents: Flow<NavEvent> = _navEvents.receiveAsFlow()

    init {
        load()
    }

    /** A source row was tapped — open its browse screen (pushed onto the Browser tab's stack). */
    fun onSourceClick(sourceId: String) {
        _navEvents.trySend(NavEvent.NavTo(SourceBrowse(sourceId)))
    }

    private fun load() {
        viewModelScope.launch {
            // listSources never throws for a single bad bundle (the loader skips it), but guard the whole
            // call anyway so an unexpected failure yields an empty list, not a stuck spinner.
            val sources = withContext(dispatcher.default) {
                runCatching { sourceLoader.listSources() }.getOrDefault(emptyList())
            }
            uiState.value = Content(sources.sortedBy { it.name.value.lowercase() })
        }
    }
}
