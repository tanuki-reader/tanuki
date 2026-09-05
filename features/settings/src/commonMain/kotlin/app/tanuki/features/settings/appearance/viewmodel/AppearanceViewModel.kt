package app.tanuki.features.settings.appearance.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.settings.model.ThemePreference
import app.tanuki.core.domain.settings.usecase.ObserveThemePreference
import app.tanuki.core.domain.settings.usecase.SetThemePreference
import app.tanuki.core.presentation.TanukiViewModel
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Presentation logic for the Appearance sub-screen. Owns the theme control; other look-and-feel options
 * (dynamic color, timestamp format, cover style…) will slot in here as the screen grows, without touching
 * the settings index or the other sub-screens.
 *
 * Compose-free (per CLAUDE.md → MVVM); Metro-injected via [ViewModelKey] + [ContributesIntoMap].
 */
@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
class AppearanceViewModel(
    observeThemePreference: ObserveThemePreference,
    private val setThemePreference: SetThemePreference,
    dispatcher: DispatcherProvider,
    logger: Logger
) : TanukiViewModel(dispatcher, logger) {
    val theme: StateFlow<ThemePreference> =
        observeThemePreference().asState(ThemePreference.System)

    /** Persist the chosen theme; the new value flows back into [theme] via [ObserveThemePreference]. */
    fun onThemeChange(preference: ThemePreference) {
        viewModelScope.launch { setThemePreference(preference) }
    }
}