package app.tanuki.features.settings.sources.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.download.model.SourceDownloadUsage
import app.tanuki.core.domain.download.usecase.DeleteSourceDownloads
import app.tanuki.core.domain.download.usecase.GetSourceDownloadUsage
import app.tanuki.core.domain.settings.usecase.ObserveShowSensitiveContent
import app.tanuki.core.domain.source.model.CatalogSource
import app.tanuki.core.domain.source.model.InstallProgress
import app.tanuki.core.domain.source.model.SourceRepoError
import app.tanuki.core.domain.source.usecase.InstallSource
import app.tanuki.core.domain.source.usecase.ObserveSourceCatalog
import app.tanuki.core.domain.source.usecase.ObserveSourceRepos
import app.tanuki.core.domain.source.usecase.UninstallSource
import app.tanuki.core.presentation.StateViewModel
import app.tanuki.core.presentation.runCatchingCancellable
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Presentation logic for the Sources screen. Compose-free (per CLAUDE.md → MVVM) and registered into the
 * app's ViewModel multibinding, so `metroViewModel<SourcesViewModel>()` resolves it. The repos part of the
 * same screen (add, remove, refresh) is [SourceReposViewModel]'s.
 *
 * Owns nothing about *what* installing or uninstalling means — [InstallSource] and [UninstallSource] do,
 * including the rule that an uninstall keeps the user's library. This class only tracks what the screen
 * needs on top of the domain's own stream: each row's install progress and failure, a removal in flight,
 * and the language filter. None of it belongs in the domain, and none of it survives the screen.
 *
 * The list itself is never edited optimistically. Both actions leave the bundle store, whose stream
 * [ObserveSourceCatalog] is built on re-emits, moving the row between sections on its own — so what the
 * screen shows is always what is actually on disk, and a failed action needs no rollback.
 *
 * ### The downloads a removal is about
 *
 * Uninstalling a source keeps the library, and its downloaded chapters keep reading offline without it —
 * so deleting them is an *offer*, never a consequence. [onRemovalRequested] reads what that source has
 * downloaded so the dialog can ask a question with a real count and size behind it; the answer is the
 * dialog's checkbox ([onRemovalDeleteDownloadsChange]), read when it is confirmed. The read is a
 * `suspend` call and the dialog opens immediately, so [SourcesState.pendingUsage] is `null` for the frame
 * or two before it lands — which is exactly the state in which the offer is not shown yet.
 */
@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
class SourcesViewModel(
    observeSourceCatalog: ObserveSourceCatalog,
    observeSourceRepos: ObserveSourceRepos,
    private val installSource: InstallSource,
    private val uninstallSource: UninstallSource,
    private val getSourceDownloadUsage: GetSourceDownloadUsage,
    private val deleteSourceDownloads: DeleteSourceDownloads,
    observeShowSensitiveContent: ObserveShowSensitiveContent,
    dispatcher: DispatcherProvider,
    logger: Logger
) : StateViewModel<SourcesState>(dispatcher, logger, SourcesState()) {
    /** Per-row work: installs in flight, how the last install failed, removals in flight or failed. */
    private val rows = MutableStateFlow(Rows())
    private val pendingUsage = MutableStateFlow<SourceDownloadUsage?>(null)

    /** The remove dialog: which source it asks about (`null` = closed), and its downloads checkbox. */
    private val removal = MutableStateFlow(Removal())
    private val selectedLanguage = MutableStateFlow<String?>(null)

    private data class Rows(
        val progress: Map<String, InstallProgress> = emptyMap(),
        val installFailures: Map<String, SourceRepoError> = emptyMap(),
        val removing: Set<String> = emptySet(),
        val removeFailed: Set<String> = emptySet()
    )

    private data class Removal(
        val sourceId: String? = null,
        val deleteDownloads: Boolean = false
    )

    override val state: Flow<SourcesState> =
        combine(
            combine(observeSourceCatalog(), observeShowSensitiveContent(), List<CatalogSource>::visibleWith),
            observeSourceRepos(),
            rows,
            combine(pendingUsage, removal, ::Pair),
            selectedLanguage
        ) { catalog, repos, rows, (usage, removal), language ->
            val languages = catalog.mapNotNull { it.lang?.value }.groupingBy { it }.eachCount()
            // A language that has gone (its last source removed) stops filtering instead of emptying the screen.
            val selected = language?.takeIf { it in languages }
            val (installedSources, availableSources) =
                catalog.filter { selected == null || it.lang?.value == selected }.partition(CatalogSource::installed)
            val installed = installedSources.map { it.toItem(rows) }
            val available = availableSources.map { it.toItem(rows) }
            SourcesState(
                loading = false,
                installed = installed,
                available = available,
                languages =
                    if (languages.size < 2) emptyList() else languages.map { (tag, count) -> LanguageOption(tag, count) },
                selectedLanguage = selected,
                showRepo = repos.size > 1,
                repoCount = repos.size,
                pendingUsage = usage,
                pendingRemoval = installed.firstOrNull { it.sourceId == removal.sourceId },
                removalDeletesDownloads = removal.deleteDownloads
            )
        }

    /** Filter both lists to one language, or `null` for all. */
    fun onLanguageSelected(tag: String?) {
        selectedLanguage.value = tag
    }

    /**
     * Install a source, or retry a failed install. Ignored while that source is already installing, so a
     * second tap can't queue a duplicate download.
     */
    fun onInstall(sourceId: String) {
        if (sourceId in rows.value.progress) return
        rows.update { it.copy(progress = it.progress + (sourceId to InstallProgress.Downloading(null)), installFailures = it.installFailures - sourceId) }
        viewModelScope.launch {
            val failure =
                runCatchingCancellable {
                    installSource(sourceId) { step -> rows.update { it.copy(progress = it.progress + (sourceId to step)) } }
                }.getOrElse { SourceRepoError.Unreachable }
            rows.update {
                it.copy(
                    progress = it.progress - sourceId,
                    installFailures = if (failure == null) it.installFailures else it.installFailures + (sourceId to failure)
                )
            }
        }
    }

    /**
     * A source's remove action was tapped: open the dialog for [sourceId], and find out what it has
     * downloaded so the dialog can offer to delete it. The checkbox starts unticked every time.
     *
     * Cleared by [onRemovalDismissed] rather than left to go stale — a count belonging to the previous
     * source would otherwise be showing the instant the next dialog opened, which is the one moment this
     * number must not be wrong.
     */
    fun onRemovalRequested(sourceId: String) {
        pendingUsage.value = null
        removal.value = Removal(sourceId)
        viewModelScope.launch { pendingUsage.value = getSourceDownloadUsage(sourceId) }
    }

    /** The dialog's "also delete downloads" option was toggled. */
    fun onRemovalDeleteDownloadsChange(deleteDownloads: Boolean) {
        removal.update { it.copy(deleteDownloads = deleteDownloads) }
    }

    /** The remove dialog was dismissed without removing. */
    fun onRemovalDismissed() {
        pendingUsage.value = null
        removal.value = Removal()
    }

    /**
     * The remove dialog was confirmed: uninstall its source, with or without its downloads. Downloads go
     * *before* the bundle: the files are found through the source's series, and a failure to remove the
     * bundle should not leave the user's chapters gone anyway.
     */
    fun onRemovalConfirmed() {
        val (sourceId, deleteDownloads) = removal.value
        onRemovalDismissed()
        if (sourceId == null || sourceId in rows.value.removing) return
        rows.update { it.copy(removing = it.removing + sourceId, removeFailed = it.removeFailed - sourceId) }
        viewModelScope.launch {
            val removed =
                runCatchingCancellable {
                    if (deleteDownloads) deleteSourceDownloads(sourceId)
                    uninstallSource(sourceId)
                }.isSuccess
            rows.update {
                it.copy(removing = it.removing - sourceId, removeFailed = if (removed) it.removeFailed else it.removeFailed + sourceId)
            }
        }
    }

    private fun CatalogSource.toItem(rows: Rows): SourceItem =
        SourceItem(
            sourceId = sourceId,
            name = name.value,
            lang = lang?.value,
            iconUrl = iconUrl?.value,
            version = version,
            repoName = repoName,
            nsfw = nsfw,
            compatibility = compatibility,
            progress = rows.progress[sourceId],
            installFailure = rows.installFailures[sourceId],
            removing = sourceId in rows.removing,
            removeFailed = sourceId in rows.removeFailed
        )
}


/**
 * The catalog as the screen shows it: adult-only sources only while sensitive content is allowed, so the
 * list follows the setting the same way Home's does.
 */
internal fun List<CatalogSource>.visibleWith(showSensitive: Boolean): List<CatalogSource> =
    if (showSensitive) this else filterNot(CatalogSource::nsfw)
