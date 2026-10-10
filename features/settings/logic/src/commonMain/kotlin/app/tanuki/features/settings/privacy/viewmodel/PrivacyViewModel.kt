package app.tanuki.features.settings.privacy.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.settings.usecase.ObserveCrashReportingEnabled
import app.tanuki.core.domain.settings.usecase.ResetInstallId
import app.tanuki.core.domain.settings.usecase.SetCrashReportingEnabled
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

/**
 * Presentation logic for the Privacy sub-screen. Owns the crash-reporting opt-in today; anything else
 * the app might send off the device belongs here too, so there is one place a user can answer "what
 * leaves my phone?".
 *
 * It only *persists* the choice. Starting and stopping the reporter is driven by whoever collects
 * [ObserveCrashReportingEnabled] at the app root, so the stored preference stays the single source of
 * truth — which is also why the switch renders from the observed value rather than from local state.
 * Off until the stored value arrives ([PrivacyState]'s default): the switch must never show "on" before
 * the preference has actually been read.
 *
 * Compose-free (per CLAUDE.md → MVVM); Metro-injected via [ViewModelKey] + [ContributesIntoMap].
 */
@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
class PrivacyViewModel(
    observeCrashReportingEnabled: ObserveCrashReportingEnabled,
    private val setCrashReportingEnabled: SetCrashReportingEnabled,
    private val resetInstallId: ResetInstallId,
    dispatcher: DispatcherProvider,
    logger: Logger
) : StateViewModel<PrivacyState>(dispatcher, logger, PrivacyState()) {
    /**
     * `true` once the identifier was reset on this visit to the screen, so it can confirm the reset.
     * Screen-local on purpose: the confirmation is for the tap that just happened, not a stored fact.
     */
    private val identifierReset = MutableStateFlow(false)

    override val state: Flow<PrivacyState> =
        combine(observeCrashReportingEnabled(), identifierReset, ::PrivacyState)

    /** Persist the choice; the new value flows back into [PrivacyState.crashReportingEnabled]. */
    fun onCrashReportingChange(enabled: Boolean) {
        viewModelScope.launch { setCrashReportingEnabled(enabled) }
    }

    /** Replace the anonymous install id; see [ResetInstallId]. */
    fun onResetIdentifier() {
        viewModelScope.launch {
            tryIo("Resetting the install id failed") { resetInstallId() }
                .onSuccess { identifierReset.value = true }
        }
    }
}

/**
 * @property crashReportingEnabled the stored crash-reporting consent.
 * @property identifierReset whether the install id was reset on this visit, for the confirmation copy.
 */
data class PrivacyState(
    val crashReportingEnabled: Boolean = false,
    val identifierReset: Boolean = false
)
