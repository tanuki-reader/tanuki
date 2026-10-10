package app.tanuki.features.settings.downloads.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tanuki.core.domain.category.usecase.ObserveCategories
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.download.model.DownloadStorageUsage
import app.tanuki.core.domain.download.usecase.DeleteAllDownloads
import app.tanuki.core.domain.download.usecase.ObserveDownloadSettings
import app.tanuki.core.domain.download.usecase.ObserveDownloadStorageUsage
import app.tanuki.core.domain.download.usecase.SetAutoDownload
import app.tanuki.core.domain.download.usecase.SetDeleteAfterReading
import app.tanuki.core.domain.download.usecase.SetDownloadOnWifiOnly
import app.tanuki.core.domain.settings.model.AutoDownloadMode
import app.tanuki.core.domain.settings.model.DeleteAfterReading
import app.tanuki.core.navigation.Downloads
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/**
 * Presentation logic for the download settings screen. Compose-free (per CLAUDE.md → MVVM).
 *
 * Every control writes straight through to the stored setting and takes effect from the *next* emission
 * of the flow it came from — there is no local mirror of a switch, so a write that fails leaves the
 * control showing what is actually stored rather than what the user hoped. That is the whole reason the
 * screen has no "save" button: each row is its own commit.
 *
 * The storage measurement is deliberately kept out of the main `combine` and defaulted to `null`. It
 * walks the downloads tree, which is slow enough to see, and letting it gate the settings rows would
 * mean the whole screen waited on a number none of them need.
 */
@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
class DownloadSettingsViewModel(
    observeDownloadSettings: ObserveDownloadSettings,
    observeCategories: ObserveCategories,
    observeDownloadStorageUsage: ObserveDownloadStorageUsage,
    private val setAutoDownload: SetAutoDownload,
    private val setDownloadOnWifiOnly: SetDownloadOnWifiOnly,
    private val setDeleteAfterReading: SetDeleteAfterReading,
    private val deleteAllDownloads: DeleteAllDownloads,
    dispatcher: DispatcherProvider,
    logger: Logger,
) : StateViewModel<DownloadSettingsState>(dispatcher, logger, DownloadSettingsState()) {
    private val busy = MutableStateFlow(false)
    private val confirmingDeleteAll = MutableStateFlow(false)

    override val state: Flow<DownloadSettingsState> =
        combine(
            observeDownloadSettings(),
            observeCategories(),
            // `null` first, so the settings rows render immediately and the storage section shows its
            // own spinner rather than holding the whole screen behind a directory walk.
            observeDownloadStorageUsage().map<DownloadStorageUsage, DownloadStorageUsage?> { it }.onStart { emit(null) },
            busy,
            confirmingDeleteAll,
        ) { settings, categories, usage, busy, confirming ->
            DownloadSettingsState(
                settings = settings,
                categories = categories,
                usage = usage,
                busy = busy,
                confirmingDeleteAll = confirming,
            )
        }

    /**
     * Pick the auto-download rule.
     *
     * The category selection is carried through unchanged, so switching to `Off` and back does not lose
     * it — the stored selection is what the user last chose, not what the current mode happens to use.
     */
    fun onAutoDownloadModeChange(mode: AutoDownloadMode) {
        val categories = uiState.value.settings.autoDownloadCategories
        viewModelScope.launch {
            tryIo("Could not change the auto-download mode") { setAutoDownload(mode, categories) }
        }
    }

    /** Add or remove one category from the auto-download selection. */
    fun onAutoDownloadCategoryToggle(categoryId: Uuid) {
        val settings = uiState.value.settings
        val categories =
            if (categoryId in settings.autoDownloadCategories) {
                settings.autoDownloadCategories - categoryId
            } else {
                settings.autoDownloadCategories + categoryId
            }
        viewModelScope.launch {
            tryIo("Could not change the auto-download categories") {
                setAutoDownload(settings.autoDownloadMode, categories)
            }
        }
    }

    /** Hold the queue for an unmetered connection, or let it run on anything. */
    fun onWifiOnlyChange(wifiOnly: Boolean) {
        viewModelScope.launch {
            tryIo("Could not change the Wi-Fi setting") { setDownloadOnWifiOnly(wifiOnly) }
        }
    }

    /** Choose how much read-chapter slack to keep. Never retroactive — see [SetDeleteAfterReading]. */
    fun onDeleteAfterReadingChange(setting: DeleteAfterReading) {
        viewModelScope.launch {
            tryIo("Could not change the delete-after-reading setting") { setDeleteAfterReading(setting) }
        }
    }

    /** "Open queue" was tapped: show the download queue. */
    fun onOpenQueue() {
        navigateTo(Downloads)
    }

    /** "Delete all downloads" was tapped: ask for confirmation first. */
    fun onDeleteAllRequested() {
        confirmingDeleteAll.value = true
    }

    /** The delete-all confirmation was dismissed. */
    fun onDeleteAllDismissed() {
        confirmingDeleteAll.value = false
    }

    /**
     * Remove every downloaded chapter — the confirmed half of the destructive action. Closes the
     * confirmation.
     *
     * Under [DownloadSettingsState.busy] because it walks every record and every directory: a second tap
     * during the first would issue a duplicate sweep across a store that is already emptying.
     */
    fun onDeleteAllDownloads() {
        confirmingDeleteAll.value = false
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            try {
                tryIo("Could not delete the downloads") { deleteAllDownloads() }
            } finally {
                busy.value = false
            }
        }
    }
}