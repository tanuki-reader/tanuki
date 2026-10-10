package app.tanuki.features.settings.viewmodel

import androidx.lifecycle.ViewModel
import app.tanuki.core.domain.category.usecase.ObserveCategories
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.download.usecase.ObserveDownloadQueueCount
import app.tanuki.core.domain.settings.usecase.ObserveCrashReportingEnabled
import app.tanuki.core.domain.settings.usecase.ObserveThemePreference
import app.tanuki.core.domain.source.usecase.GetInstalledSources
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

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
