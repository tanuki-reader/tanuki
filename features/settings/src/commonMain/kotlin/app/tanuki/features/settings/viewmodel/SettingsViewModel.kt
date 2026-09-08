package app.tanuki.features.settings.viewmodel

import androidx.lifecycle.ViewModel
import app.tanuki.core.domain.category.usecase.ObserveCategories
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.download.usecase.ObserveDownloadQueueCount
import app.tanuki.core.domain.settings.usecase.ObserveCrashReportingEnabled
import app.tanuki.core.domain.settings.usecase.ObserveThemePreference
import app.tanuki.core.domain.source.usecase.GetInstalledSources
import app.tanuki.core.presentation.StateViewModel
import app.tanuki.core.presentation.TanukiViewModel
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

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
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
class SettingsViewModel(
    observeThemePreference: ObserveThemePreference,
    observeCrashReportingEnabled: ObserveCrashReportingEnabled,
    observeCategories: ObserveCategories,
    getInstalledSources: GetInstalledSources,
    observeDownloadQueueCount: ObserveDownloadQueueCount,
    dispatcher: DispatcherProvider,
    logger: Logger
) : StateViewModel<SettingsState>(dispatcher, logger, SettingsState()) {
    override val state: Flow<SettingsState> =
        combine(
            observeThemePreference(),
            observeCategories(),
            getInstalledSources(),
            observeCrashReportingEnabled(),
            observeDownloadQueueCount()
        ) { theme, categories, sources, crashReporting, queued ->
            SettingsState(
                theme = theme,
                categoryCount = categories.size,
                sourceCount = sources.size,
                crashReportingEnabled = crashReporting,
                downloadQueueCount = queued
            )
        }
}