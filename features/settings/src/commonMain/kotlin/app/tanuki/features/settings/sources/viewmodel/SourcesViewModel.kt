package app.tanuki.features.settings.sources.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.download.model.SourceDownloadUsage
import app.tanuki.core.domain.download.usecase.DeleteSourceDownloads
import app.tanuki.core.domain.download.usecase.GetSourceDownloadUsage
import app.tanuki.core.domain.source.model.CatalogSource
import app.tanuki.core.domain.source.usecase.InstallSource
import app.tanuki.core.domain.source.usecase.ObserveSourceCatalog
import app.tanuki.core.domain.source.usecase.UninstallSource
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Presentation logic for the source-management sub-screen. Compose-free (per CLAUDE.md → MVVM) and
 * registered into the app's ViewModel multibinding, so `metroViewModel<SourcesViewModel>()` resolves it.
 *
 * Owns nothing about *what* installing or uninstalling means — [InstallSource] and [UninstallSource] do,
 * including the rule that an uninstall keeps the user's library. This class only tracks what the screen
 * needs on top of the domain's own stream: which rows have an action in flight, and which one just
 * failed. Neither belongs in the domain, and neither survives the screen.
 *
 * The list itself is never edited optimistically. Both actions leave the bundle store, whose stream
 * [ObserveSourceCatalog] is built on re-emits, moving the row between sections on its own — so what the
 * screen shows is always what is actually on disk, and a failed action needs no rollback.
 *
 * ### The downloads a removal is about
 *
 * Uninstalling a source keeps the library, and its downloaded chapters keep reading offline without it —
 * so deleting them is an *offer*, never a consequence. [onRemovalRequested] reads what that source has
 * downloaded so the dialog can ask a question with a real count and size behind it; the answer comes back
 * through [onUninstall]'s `deleteDownloads`. The read is a `suspend` call and the dialog opens
 * immediately, so [SourcesState.pendingUsage] is `null` for the frame or two before it lands — which is
 * exactly the state in which the offer is not shown yet.
 */
@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
class SourcesViewModel(
    observeSourceCatalog: ObserveSourceCatalog,
    private val installSource: InstallSource,
    private val uninstallSource: UninstallSource,
    private val getSourceDownloadUsage: GetSourceDownloadUsage,
    private val deleteSourceDownloads: DeleteSourceDownloads,
    dispatcher: DispatcherProvider,
    logger: Logger
) : StateViewModel<SourcesState>(dispatcher, logger, SourcesState()) {
    private val busyIds = MutableStateFlow<Set<String>>(emptySet())
    private val failedIds = MutableStateFlow<Set<String>>(emptySet())
    private val pendingUsage = MutableStateFlow<SourceDownloadUsage?>(null)

    override val state: Flow<SourcesState> =
        combine(
            observeSourceCatalog(),
            busyIds,
            failedIds,
            pendingUsage
        ) { catalog, busy, failed, usage ->
            val (installed, available) = catalog.partition(CatalogSource::installed)
            SourcesState(
                loading = false,
                installed = installed.map(CatalogSource::toItem),
                available = available.map(CatalogSource::toItem),
                busyIds = busy,
                failedIds = failed,
                pendingUsage = usage
            )
        }

    /** Install (or re-install) a source. Ignored while that source already has an action in flight. */
    fun onInstall(sourceId: String) = act(sourceId) { installSource(sourceId) }

    /**
     * The remove dialog is opening for [sourceId]: find out what it has downloaded, so the dialog can
     * offer to delete it.
     *
     * Cleared by [onRemovalDismissed] rather than left to go stale — a count belonging to the previous
     * source would otherwise be showing the instant the next dialog opened, which is the one moment this
     * number must not be wrong.
     */
    fun onRemovalRequested(sourceId: String) {
        pendingUsage.value = null
        viewModelScope.launch { pendingUsage.value = getSourceDownloadUsage(sourceId) }
    }

    /** The remove dialog closed, confirmed or not. */
    fun onRemovalDismissed() {
        pendingUsage.value = null
    }

    /**
     * Uninstall a source — the confirmed half of the delete flow; the screen owns the confirmation, since
     * a dialog is a presentation decision, not a domain one.
     *
     * @param deleteDownloads whether the user accepted the dialog's offer to remove what this source had
     *   downloaded. Deleted *before* the bundle: the files are found through the source's series, and a
     *   failure to remove the bundle should not leave the user's chapters gone anyway.
     */
    fun onUninstall(
        sourceId: String,
        deleteDownloads: Boolean
    ) = act(sourceId) {
        if (deleteDownloads) deleteSourceDownloads(sourceId)
        uninstallSource(sourceId)
        true
    }

    /**
     * Runs [action] for [sourceId] under the row's busy flag, recording a failure against that row.
     *
     * `finally` rather than a plain reset: an action that throws must still release the row, or it would
     * be stuck disabled with a spinner for as long as the screen lives — a dead row is worse than a
     * failed one.
     */
    private fun act(
        sourceId: String,
        action: suspend () -> Boolean
    ) {
        if (sourceId in busyIds.value) return
        viewModelScope.launch {
            busyIds.update { it + sourceId }
            failedIds.update { it - sourceId }
            try {
                if (!runCatching { action() }.getOrDefault(false)) {
                    failedIds.update { it + sourceId }
                }
            } finally {
                busyIds.update { it - sourceId }
            }
        }
    }
}

private fun CatalogSource.toItem(): SourceItem =
    SourceItem(
        sourceId = sourceId,
        name = name.value,
        lang = lang?.value,
        iconUrl = iconUrl?.value
    )
