package app.tanuki.features.settings.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tanuki.core.domain.category.usecase.ObserveCategories
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.settings.usecase.ObserveThemePreference
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

/**
 * Presentation logic for the Settings **index** screen (the tab root). Compose-free (per CLAUDE.md →
 * MVVM). It only reads state — the value previews shown as each row's subtitle — because the index has
 * no actions of its own; each row navigates to a focused sub-screen ([app.tanuki.core.navigation]
 * `SettingsAppearance` / `SettingsCategories`) that owns the actual controls and its own ViewModel.
 *
 * Metro-injected and registered into the app's ViewModel multibinding via [ViewModelKey] +
 * [ContributesIntoMap], so `metroViewModel<SettingsViewModel>()` resolves it in the UI layer.
 */
@Inject
@ContributesIntoMap(AppScope::class)
@ViewModelKey
class SettingsViewModel(
    observeThemePreference: ObserveThemePreference,
    observeCategories: ObserveCategories,
    dispatcher: DispatcherProvider
) : ViewModel() {
    val uiState: StateFlow<SettingsState> =
        combine(
            observeThemePreference(),
            observeCategories()
        ) { theme, categories ->
            SettingsState(theme = theme, categoryCount = categories.size)
        }.flowOn(dispatcher.default)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = SettingsState()
            )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}