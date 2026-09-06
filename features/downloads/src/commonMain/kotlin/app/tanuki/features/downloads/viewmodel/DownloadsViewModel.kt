package app.tanuki.features.downloads.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.download.usecase.CancelAllDownloads
import app.tanuki.core.domain.download.usecase.CancelChapterDownloads
import app.tanuki.core.domain.download.usecase.EnqueueChapterDownloads
import app.tanuki.core.domain.download.usecase.ObserveDownloadQueue
import app.tanuki.core.domain.download.usecase.ObserveDownloadsPaused
import app.tanuki.core.domain.download.usecase.SetDownloadsPaused
import app.tanuki.core.navigation.MangaDetail
import app.tanuki.core.presentation.TanukiViewModel
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/**
 * Presentation logic for the download queue. Compose-free (per CLAUDE.md → MVVM) and registered into the
 * app's ViewModel multibinding, so `metroViewModel<DownloadsViewModel>()` resolves it.
 *
 * Every action here is a single domain call, and none of them touches the list: the queue's own flow is
 * what moves a row, so cancelling, retrying and pausing all take effect by changing the thing the screen
 * is already watching. There is no optimistic state to roll back, and no way for what is shown to
 * disagree with what the engine will actually do.
 *
 * The one piece of local state is [DownloadsState.busy], which exists only for the two queue-wide
 * actions: they walk every record, so a second tap during the first would issue a duplicate sweep across
 * a queue that is already emptying.
 */
@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
class DownloadsViewModel(
    observeDownloadQueue: ObserveDownloadQueue,
    observeDownloadsPaused: ObserveDownloadsPaused,
    private val setDownloadsPaused: SetDownloadsPaused,
    private val cancelChapterDownloads: CancelChapterDownloads,
    private val cancelAllDownloads: CancelAllDownloads,
    private val enqueueChapterDownloads: EnqueueChapterDownloads,
    dispatcher: DispatcherProvider,
    logger: Logger,
) : TanukiViewModel(dispatcher, logger) {
    private val busy = MutableStateFlow(false)

    val uiState: StateFlow<DownloadsState> =
        combine(
            observeDownloadQueue(),
            observeDownloadsPaused(),
            busy,
        ) { series, paused, busy ->
            DownloadsState(loading = false, series = series, paused = paused, busy = busy)
        }.asState(DownloadsState())

    /** Pause or resume the whole queue. Resuming starts the engine again (see [SetDownloadsPaused]). */
    fun onTogglePaused() {
        val paused = uiState.value.paused
        val what = if (paused) "Could not resume the queue" else "Could not pause the queue"
        viewModelScope.launch { tryIo(what) { setDownloadsPaused(!paused) } }
    }

    /** Take one chapter out of the queue — the row's trailing control. */
    fun onCancelChapter(chapterId: Uuid) {
        viewModelScope.launch { tryIo("Could not cancel a download") { cancelChapterDownloads(listOf(chapterId)) } }
    }

    /** Take every chapter of one series out of the queue — the group header's action. */
    fun onCancelSeries(mangaId: Uuid) {
        val chapterIds =
            uiState.value.series
                .firstOrNull { it.manga.id == mangaId }
                ?.chapters
                ?.map { it.chapter.id }
                .orEmpty()
        if (chapterIds.isEmpty()) return
        viewModelScope.launch { tryIo("Could not cancel a series' downloads") { cancelChapterDownloads(chapterIds) } }
    }

    /**
     * Try a failed chapter again.
     *
     * The same call as queueing it in the first place: [EnqueueChapterDownloads] already treats a failed
     * record as a retry, clearing the error and keeping the original stamp — so a chapter that failed at
     * position three goes back to position three rather than behind everything queued since.
     *
     * A `NeedsVerification` failure uses this path too. There is no separate "solve the challenge" call
     * to make: the challenge is raised by the request itself, and the app is on screen — which is the one
     * condition a challenge needs to be solvable at all (see `docs/decisions.md`, 2026-08-22). Retrying
     * from this screen is therefore literally what "verify to continue" means here.
     */
    fun onRetryChapter(chapterId: Uuid) {
        viewModelScope.launch { tryIo("Could not retry a download") { enqueueChapterDownloads(listOf(chapterId)) } }
    }

    /** Empty the queue — the confirmed half of the top bar's destructive action. */
    fun onCancelAll() = sweep("Could not clear the queue") { cancelAllDownloads() }

    /** Open a series from its group header. */
    fun onOpenManga(mangaId: Uuid) = navigateTo(MangaDetail(mangaId))

    /**
     * Runs a queue-wide action under [busy].
     *
     * `finally` rather than a plain reset: an action that throws must still release the flag, or the top
     * bar would stay disabled for as long as the screen lives — and a screen whose only controls are dead
     * is worse than one whose action failed.
     */
    private fun sweep(
        what: String,
        action: suspend () -> Unit,
    ) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            try {
                tryIo(what) { action() }
            } finally {
                busy.value = false
            }
        }
    }
}