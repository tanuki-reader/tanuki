package app.tanuki.features.settings.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tanuki.core.domain.category.usecase.ObserveCategories
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.download.usecase.ObserveDownloadQueueCount
import app.tanuki.core.domain.settings.usecase.ObserveCrashReportingEnabled
import app.tanuki.core.domain.settings.usecase.ObserveShowSensitiveContent
import app.tanuki.core.domain.settings.usecase.SetShowSensitiveContent
import app.tanuki.core.domain.settings.usecase.ObserveThemePreference
import app.tanuki.core.domain.source.model.CatalogSource
import app.tanuki.core.domain.source.usecase.ObserveSourceCatalog
import app.tanuki.core.navigation.SettingsAppearance
import app.tanuki.core.navigation.SettingsCategories
import app.tanuki.core.navigation.SettingsDownloads
import app.tanuki.core.navigation.SettingsPrivacy
import app.tanuki.core.navigation.SettingsSources
import app.tanuki.core.presentation.StateViewModel
import app.tanuki.core.presentation.TanukiViewModel
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

/**
 * Presentation logic for the Settings **index** screen (the tab root). Compose-free (per CLAUDE.md →
 * MVVM). It reads the value previews shown as each row's subtitle, and its one action, [onEntryClick],
 * opens a row's sub-screen: each row navigates to a focused sub-screen ([app.tanuki.core.navigation]
 * `SettingsAppearance` / `SettingsCategories`) that owns the actual controls and its own ViewModel.
 *
 * Metro-injected and registered into the app's ViewModel multibinding via [ViewModelKey] +
 * [ContributesIntoMap], so `metroViewModel<SettingsViewModel>()` resolves it in the UI layer.
 */
@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
class SettingsViewModel(
    observeThemePreference: ObserveThemePreference,
    observeCrashReportingEnabled: ObserveCrashReportingEnabled,
    observeCategories: ObserveCategories,
    observeSourceCatalog: ObserveSourceCatalog,
    observeDownloadQueueCount: ObserveDownloadQueueCount,
    observeShowSensitiveContent: ObserveShowSensitiveContent,
    private val setShowSensitiveContent: SetShowSensitiveContent,
    dispatcher: DispatcherProvider,
    logger: Logger
) : StateViewModel<SettingsState>(dispatcher, logger, SettingsState()) {
    /** The user's latest pick, shown at once while DataStore writes it (no snap-back on the switch). */
    private val chosenShowSensitive = MutableStateFlow<Boolean?>(null)

    override val state: Flow<SettingsState> =
        combine(
            combine(observeThemePreference(), observeCategories(), ::Pair),
            // Enumerating installed sources instantiates each one, which takes seconds on a cold start. Start
            // empty so the switches and values above don't sit on their defaults (looking wrong, and
            // tappable) while it runs; only the Sources row fills in later.
            observeSourceCatalog().onStart { emit(emptyList()) },
            observeCrashReportingEnabled(),
            observeDownloadQueueCount(),
            combine(observeShowSensitiveContent(), chosenShowSensitive) { stored, chosen -> chosen ?: stored }
        ) { (theme, categories), sources, crashReporting, queued, showSensitive ->
            SettingsState(
                theme = theme,
                categoryCount = categories.size,
                sourceCount = sources.count(CatalogSource::installed),
                sourceUpdateCount = sources.count { it.updateVersion != null },
                crashReportingEnabled = crashReporting,
                downloadQueueCount = queued,
                showSensitive = showSensitive
            )
        }

    /** Allow or hide adult content: adult-only sources, and adult titles inside mixed ones. */
    fun onShowSensitiveChange(show: Boolean) {
        chosenShowSensitive.value = show
        viewModelScope.launch { setShowSensitiveContent(show) }
    }

    /** A row was tapped: open its sub-screen, pushed onto the Settings tab's stack. */
    fun onEntryClick(entry: SettingsEntry) {
        navigateTo(
            when (entry) {
                SettingsEntry.Appearance -> SettingsAppearance
                SettingsEntry.Privacy -> SettingsPrivacy
                SettingsEntry.Categories -> SettingsCategories
                SettingsEntry.Sources -> SettingsSources
                SettingsEntry.Downloads -> SettingsDownloads
            }
        )
    }
}
