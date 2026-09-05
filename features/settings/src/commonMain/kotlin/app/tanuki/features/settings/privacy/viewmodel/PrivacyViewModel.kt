package app.tanuki.features.settings.privacy.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.settings.usecase.ObserveCrashReportingEnabled
import app.tanuki.core.domain.settings.usecase.SetCrashReportingEnabled
import app.tanuki.core.presentation.TanukiViewModel
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Presentation logic for the Privacy sub-screen. Owns the crash-reporting opt-in today; anything else
 * the app might send off the device belongs here too, so there is one place a user can answer "what
 * leaves my phone?".
 *
 * It only *persists* the choice. Starting and stopping the reporter is driven by whoever collects
 * [ObserveCrashReportingEnabled] at the app root, so the stored preference stays the single source of
 * truth — which is also why the switch renders from the observed value rather than from local state.
 *
 * Compose-free (per CLAUDE.md → MVVM); Metro-injected via [ViewModelKey] + [ContributesIntoMap].
 */
@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
class PrivacyViewModel(
    observeCrashReportingEnabled: ObserveCrashReportingEnabled,
    private val setCrashReportingEnabled: SetCrashReportingEnabled,
    dispatcher: DispatcherProvider,
    logger: Logger
) : TanukiViewModel(dispatcher, logger) {
    // Off until the stored value arrives: the switch must never show "on" before the preference has
    // actually been read.
    val crashReportingEnabled: StateFlow<Boolean> =
        observeCrashReportingEnabled().asState(false)

    /** Persist the choice; the new value flows back into [crashReportingEnabled]. */
    fun onCrashReportingChange(enabled: Boolean) {
        viewModelScope.launch { setCrashReportingEnabled(enabled) }
    }
}