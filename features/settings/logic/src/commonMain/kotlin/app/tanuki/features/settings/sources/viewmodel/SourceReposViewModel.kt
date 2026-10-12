package app.tanuki.features.settings.sources.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.source.model.RepoPreview
import app.tanuki.core.domain.source.model.SourceRepo
import app.tanuki.core.domain.source.model.SourceRepoError
import app.tanuki.core.domain.source.model.SourceRepoException
import app.tanuki.core.domain.source.usecase.AddSourceRepo
import app.tanuki.core.domain.source.usecase.ObserveSourceRepos
import app.tanuki.core.domain.source.usecase.PreviewSourceRepo
import app.tanuki.core.domain.source.usecase.RefreshSourceRepos
import app.tanuki.core.domain.source.usecase.RemoveSourceRepo
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
 * Presentation logic for the repos half of the Sources screen (design system: `AddSourceRepo`): add a repo
 * by URL, remove one, refresh them all.
 *
 * Adding is two steps on purpose. [onUrlSubmitted] only *previews* the repo — its name and the
 * fingerprint of the key it would pin — and [onPreviewConfirmed] adds it. The key is the root of trust
 * for everything the repo will ever install, so the user sees it before it is pinned.
 *
 * The typed URL stays in each platform's UI (as in Categories); this class only holds what the dialogs
 * need to show: which one is open, whether a fetch is in flight, and what went wrong.
 */
@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
class SourceReposViewModel(
    observeSourceRepos: ObserveSourceRepos,
    private val previewSourceRepo: PreviewSourceRepo,
    private val addSourceRepo: AddSourceRepo,
    private val removeSourceRepo: RemoveSourceRepo,
    private val refreshSourceRepos: RefreshSourceRepos,
    dispatcher: DispatcherProvider,
    logger: Logger
) : StateViewModel<SourceReposState>(dispatcher, logger, SourceReposState()) {
    private val dialog = MutableStateFlow(Dialog())

    /** What the repo being added would pin; kept here, since the item in state carries no key. */
    private var preview: RepoPreview? = null

    private data class Dialog(
        val adding: Boolean = false,
        val busy: Boolean = false,
        val preview: SourceRepoItem? = null,
        val pendingRemoval: SourceRepoItem? = null,
        val error: SourceRepoError? = null,
        val refreshing: Boolean = false,
        val refreshError: SourceRepoError? = null
    )

    override val state: Flow<SourceReposState> =
        combine(observeSourceRepos(), dialog) { repos, dialog ->
            SourceReposState(
                loading = false,
                repos = repos.map(SourceRepo::toItem),
                adding = dialog.adding,
                busy = dialog.busy,
                preview = dialog.preview,
                pendingRemoval = dialog.pendingRemoval,
                error = dialog.error,
                refreshing = dialog.refreshing,
                refreshError = dialog.refreshError
            )
        }

    /** The add button: open the URL dialog. */
    fun onAddRequested() {
        dialog.value = Dialog(adding = true)
    }

    /** The URL dialog was submitted: fetch the repo's name and key, and ask the user to confirm them. */
    fun onUrlSubmitted(url: String) =
        work(onFailure = { current, error -> current.copy(error = error) }) {
            val fetched = previewSourceRepo(url)
            preview = fetched
            dialog.update { it.copy(adding = false, preview = fetched.toItem()) }
        }

    /** The fingerprint was confirmed: pin the key and add the repo. */
    fun onPreviewConfirmed() {
        val pending = preview ?: return
        work(onFailure = { _, error -> Dialog(error = error) }) {
            addSourceRepo(pending)
            preview = null
            dialog.value = Dialog()
        }
    }

    /** A repo's remove action: ask for confirmation. */
    fun onRemoveRequested(repo: SourceRepoItem) {
        dialog.value = Dialog(pendingRemoval = repo)
    }

    /** The removal was confirmed. Its installed sources stay installed; they just stop being offered. */
    fun onRemoveConfirmed() {
        val repo = dialog.value.pendingRemoval ?: return
        dialog.value = Dialog()
        viewModelScope.launch { runCatchingCancellable { removeSourceRepo(repo.baseUrl) } }
    }

    /**
     * Re-fetch every repo's index. A failing repo keeps its last good index. A trust failure (key changed,
     * signature refused) is already a banner on that repo's row; anything else is reported once.
     */
    fun onRefresh() {
        if (dialog.value.refreshing) return
        dialog.update { it.copy(refreshing = true, refreshError = null) }
        viewModelScope.launch {
            val failure = runCatchingCancellable { refreshSourceRepos() }.exceptionOrNull()
            val reason = (failure as? SourceRepoException)?.error ?: failure?.let { SourceRepoError.Unreachable }
            if (failure != null) logFailure("Source repo refresh failed", failure)
            val shown = reason?.takeUnless { it == SourceRepoError.KeyChanged || it == SourceRepoError.BadSignature }
            dialog.update { it.copy(refreshing = false, refreshError = shown) }
        }
    }

    /** Any dialog (or the error) was dismissed. */
    fun onDialogDismissed() {
        preview = null
        dialog.value = Dialog()
    }

    /** Runs [block] with [Dialog.busy] set, mapping a failure into the dialog state with [onFailure]. */
    private fun work(
        onFailure: (Dialog, SourceRepoError) -> Dialog,
        block: suspend () -> Unit
    ) {
        if (dialog.value.busy) return
        dialog.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            runCatchingCancellable { block() }
                .onFailure { error ->
                    val reason = (error as? SourceRepoException)?.error ?: SourceRepoError.Unreachable
                    logFailure("Source repo action failed", error)
                    dialog.update { onFailure(it, reason) }
                }
            dialog.update { it.copy(busy = false) }
        }
    }
}

/**
 * @property loading `true` until the first emission.
 * @property repos the added repos, in the order they were added.
 * @property adding whether the URL dialog is open.
 * @property busy a preview, add or refresh is in flight; the dialog's confirm shows progress.
 * @property preview the repo awaiting fingerprint confirmation, or `null`.
 * @property pendingRemoval the repo awaiting removal confirmation, or `null`.
 * @property error what the last add ran into. Shown in the URL dialog while [adding], else on its own.
 * @property refreshing a refresh is in flight (the pull-to-refresh indicator).
 * @property refreshError why the last refresh failed, when it isn't already a banner on a repo.
 */
data class SourceReposState(
    val loading: Boolean = true,
    val repos: List<SourceRepoItem> = emptyList(),
    val adding: Boolean = false,
    val busy: Boolean = false,
    val preview: SourceRepoItem? = null,
    val pendingRemoval: SourceRepoItem? = null,
    val error: SourceRepoError? = null,
    val refreshing: Boolean = false,
    val refreshError: SourceRepoError? = null
)

/**
 * One repo: a row in the Repos group, or the summary the add confirmation shows.
 *
 * @property host the base URL without its scheme.
 * @property fingerprint the key's fingerprint: two lines of four groups of four hex digits.
 * @property sourceCount how many sources it offers; known for a preview, `null` for an added repo.
 * @property problem why its last refresh was refused ([SourceRepoError.KeyChanged] or
 *   [SourceRepoError.BadSignature]), shown as a `danger` banner on the row; `null` when it's fine.
 */
data class SourceRepoItem(
    val baseUrl: String,
    val name: String,
    val host: String,
    val fingerprint: String,
    val sourceCount: Int? = null,
    val problem: SourceRepoError? = null
)

private fun SourceRepo.toItem() = SourceRepoItem(baseUrl, name, baseUrl.host(), fingerprint, problem = problem)

private fun RepoPreview.toItem() = SourceRepoItem(baseUrl, name, baseUrl.host(), fingerprint, sourceCount = sourceCount)

private fun String.host() = substringAfter("://").removeSuffix("/")
