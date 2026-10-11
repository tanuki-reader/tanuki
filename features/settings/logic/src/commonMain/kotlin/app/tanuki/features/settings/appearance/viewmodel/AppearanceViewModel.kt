package app.tanuki.features.settings.appearance.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.settings.model.LibraryGridSize
import app.tanuki.core.domain.settings.model.ThemePreference
import app.tanuki.core.domain.settings.usecase.ObserveLibraryGridSize
import app.tanuki.core.domain.settings.usecase.ObserveThemePreference
import app.tanuki.core.domain.settings.usecase.SetLibraryGridSize
import app.tanuki.core.domain.settings.usecase.SetThemePreference
import app.tanuki.core.presentation.StateViewModel
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** What the Appearance screen shows: the theme and the library's cover size. */
data class AppearanceState(
    val theme: ThemePreference = ThemePreference.System,
    val gridSize: LibraryGridSize = LibraryGridSize.Medium
)

/**
 * Presentation logic for the Appearance sub-screen: the theme and the library cover size. Both are
 * stored settings, so each control writes through and the new value flows back into [uiState].
 *
 * Compose-free (per CLAUDE.md → MVVM); Metro-injected via [ViewModelKey] + [ContributesIntoMap].
 */
@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
class AppearanceViewModel(
    observeThemePreference: ObserveThemePreference,
    private val setThemePreference: SetThemePreference,
    observeLibraryGridSize: ObserveLibraryGridSize,
    private val setLibraryGridSize: SetLibraryGridSize,
    dispatcher: DispatcherProvider,
    logger: Logger
) : StateViewModel<AppearanceState>(dispatcher, logger, AppearanceState()) {
    // The user's latest picks, shown at once while DataStore writes them. Reading only the store would
    // show the old value until it has written and re-emitted, and a segmented control bound to state
    // snaps back for those frames (the flicker). Only this screen sets them, so a pick always matches
    // what the store will hold.
    private val chosenTheme = MutableStateFlow<ThemePreference?>(null)
    private val chosenGridSize = MutableStateFlow<LibraryGridSize?>(null)

    override val state: Flow<AppearanceState> =
        combine(
            observeThemePreference(),
            observeLibraryGridSize(),
            chosenTheme,
            chosenGridSize
        ) { theme, gridSize, chosenTheme, chosenGridSize ->
            AppearanceState(theme = chosenTheme ?: theme, gridSize = chosenGridSize ?: gridSize)
        }

    fun onThemeChange(preference: ThemePreference) {
        chosenTheme.value = preference
        viewModelScope.launch { setThemePreference(preference) }
    }

    fun onGridSizeChange(size: LibraryGridSize) {
        chosenGridSize.value = size
        viewModelScope.launch { setLibraryGridSize(size) }
    }
}
