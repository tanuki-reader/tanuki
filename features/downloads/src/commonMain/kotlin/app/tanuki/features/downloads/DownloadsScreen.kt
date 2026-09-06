package app.tanuki.features.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.DownloadIcon
import app.tanuki.assets.designSystem.component.PauseIcon
import app.tanuki.assets.designSystem.component.PlayIcon
import app.tanuki.assets.designSystem.component.TanukiButtonVariant
import app.tanuki.assets.designSystem.component.TanukiDetailTopBar
import app.tanuki.assets.designSystem.component.TanukiDialog
import app.tanuki.assets.designSystem.component.TanukiEmptyState
import app.tanuki.assets.designSystem.component.TanukiIconButton
import app.tanuki.assets.designSystem.component.TanukiListRowDefaults
import app.tanuki.assets.designSystem.component.TanukiLoadingIndicator
import app.tanuki.assets.designSystem.component.TanukiRowDivider
import app.tanuki.assets.designSystem.component.TrashIcon
import app.tanuki.core.navigation.Destination
import app.tanuki.core.navigation.NavEvent
import app.tanuki.features.downloads.composable.components.DownloadQueueRow
import app.tanuki.features.downloads.composable.components.DownloadSeriesHeader
import app.tanuki.features.downloads.composable.components.QueueGlyphSize
import app.tanuki.features.downloads.resources.Res
import app.tanuki.features.downloads.resources.downloads_cancel_all
import app.tanuki.features.downloads.resources.downloads_cancel_all_confirm
import app.tanuki.features.downloads.resources.downloads_cancel_all_dismiss
import app.tanuki.features.downloads.resources.downloads_cancel_all_message
import app.tanuki.features.downloads.resources.downloads_cancel_all_title
import app.tanuki.features.downloads.resources.downloads_empty
import app.tanuki.features.downloads.resources.downloads_pause
import app.tanuki.features.downloads.resources.downloads_paused_banner
import app.tanuki.features.downloads.resources.downloads_queue_count
import app.tanuki.features.downloads.resources.downloads_resume
import app.tanuki.features.downloads.resources.downloads_title
import app.tanuki.features.downloads.viewmodel.DownloadsState
import app.tanuki.features.downloads.viewmodel.DownloadsViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlin.uuid.Uuid
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** The top bar's action glyphs, one size down from their default so two fit beside the title. */
private val ActionGlyphSize = 20.dp

/** How far the whole list fades while a queue-wide action is running. */
private const val BUSY_ALPHA = 0.5f

/** The empty state's glyph, sized to the illustrative scale [TanukiEmptyState] uses by default. */
private val EmptyGlyphSize = 48.dp

/** Stable key for the status line, so it isn't confused with a series row. */
private const val STATUS_LINE_KEY = "status-line"

/** The pause glyph beside the status line — caption-sized, so it sits on the text's own line. */
private val StatusGlyphSize = 14.dp

/**
 * Stateful entry point for the download queue — a back-navigating leaf pushed onto whichever tab the user
 * opened it from (Settings' index, or a series' download menu).
 *
 * @param onNavigate push the [Destination] the ViewModel asks for (a series, from a group header).
 * @param onBack pop back to wherever this was opened from.
 * @param viewModel the ViewModel driving the queue.
 */
@Composable
fun DownloadsRoute(
    onNavigate: (Destination) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DownloadsViewModel = metroViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.navEvents.collect { event ->
            when (event) {
                is NavEvent.NavTo -> onNavigate(event.destination)
                NavEvent.NavBack -> onBack()
            }
        }
    }

    DownloadsScreen(
        state = state,
        onBack = onBack,
        onTogglePaused = viewModel::onTogglePaused,
        onCancelAll = viewModel::onCancelAll,
        onOpenManga = viewModel::onOpenManga,
        onCancelSeries = viewModel::onCancelSeries,
        onCancelChapter = viewModel::onCancelChapter,
        onRetryChapter = viewModel::onRetryChapter,
        modifier = modifier
    )
}

/**
 * The queue, grouped by series.
 *
 * The screen is deliberately thin. Every control maps to exactly one domain call, and none of them edits
 * the list — the queue's own flow moves the rows, so what is shown can never disagree with what the
 * engine will do next. The only thing owned here is the confirmation for "cancel all", because a dialog
 * is a presentation decision.
 *
 * Only that one destructive action confirms. Cancelling one chapter, or one series, does not: those cost
 * the user a re-tap of a button they already know how to find, whereas emptying the whole queue is not
 * something they can reconstruct.
 */
@Composable
internal fun DownloadsScreen(
    state: DownloadsState,
    onBack: () -> Unit,
    onTogglePaused: () -> Unit,
    onCancelAll: () -> Unit,
    onOpenManga: (Uuid) -> Unit,
    onCancelSeries: (Uuid) -> Unit,
    onCancelChapter: (Uuid) -> Unit,
    onRetryChapter: (Uuid) -> Unit,
    modifier: Modifier = Modifier
) {
    var confirmingCancelAll by remember { mutableStateOf(false) }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
    ) {
        TanukiDetailTopBar(
            title = stringResource(Res.string.downloads_title),
            onBack = onBack,
            actions = {
                if (!state.isEmpty) {
                    val pauseDescription =
                        stringResource(if (state.paused) Res.string.downloads_resume else Res.string.downloads_pause)
                    TanukiIconButton(
                        onClick = onTogglePaused,
                        // Accented while paused: the toggle is the only thing on screen that explains why
                        // a queue full of rows isn't moving, so it has to read as *on*, not as available.
                        active = state.paused,
                        modifier = Modifier.semantics { contentDescription = pauseDescription }
                    ) { tint ->
                        if (state.paused) {
                            PlayIcon(tint = tint, size = ActionGlyphSize)
                        } else {
                            PauseIcon(tint = tint, size = ActionGlyphSize)
                        }
                    }

                    val cancelAllDescription = stringResource(Res.string.downloads_cancel_all)
                    TanukiIconButton(
                        onClick = { confirmingCancelAll = true },
                        modifier = Modifier.semantics { contentDescription = cancelAllDescription }
                    ) { tint ->
                        TrashIcon(tint = tint, modifier = Modifier.size(ActionGlyphSize))
                    }
                }
            }
        )

        when {
            state.loading -> CenteredBox { TanukiLoadingIndicator() }

            state.isEmpty ->
                CenteredBox {
                    TanukiEmptyState(
                        message = stringResource(Res.string.downloads_empty),
                        icon = { tint -> DownloadIcon(tint = tint, size = EmptyGlyphSize) }
                    )
                }

            else ->
                QueueList(
                    state = state,
                    onOpenManga = onOpenManga,
                    onCancelSeries = onCancelSeries,
                    onCancelChapter = onCancelChapter,
                    onRetryChapter = onRetryChapter
                )
        }
    }

    if (confirmingCancelAll) {
        TanukiDialog(
            title = stringResource(Res.string.downloads_cancel_all_title),
            onDismiss = { confirmingCancelAll = false },
            confirmText = stringResource(Res.string.downloads_cancel_all_confirm),
            onConfirm = {
                confirmingCancelAll = false
                onCancelAll()
            },
            dismissText = stringResource(Res.string.downloads_cancel_all_dismiss),
            confirmVariant = TanukiButtonVariant.Danger
        ) {
            Text(
                text = stringResource(Res.string.downloads_cancel_all_message),
                style = TanukiTheme.typography.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * The grouped queue.
 *
 * A flat [LazyColumn] with headers interleaved rather than nested lists: the queue is one scroll of
 * heterogeneous rows, and only a flat list can recycle across group boundaries — which matters here
 * because the whole list re-emits every time a page lands.
 *
 * Chapter ids key the rows, so a chapter that finishes and drops out animates away rather than making
 * every row below it re-bind to a different chapter.
 */
@Composable
private fun QueueList(
    state: DownloadsState,
    onOpenManga: (Uuid) -> Unit,
    onCancelSeries: (Uuid) -> Unit,
    onCancelChapter: (Uuid) -> Unit,
    onRetryChapter: (Uuid) -> Unit
) {
    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                // Dimmed, not disabled: a sweep across a long queue takes a moment, and the rows leaving
                // one by one underneath are the feedback that it is working.
                .alpha(if (state.busy) BUSY_ALPHA else 1f),
        // As content padding rather than a modifier, so the last row scrolls clear of the system bar
        // instead of the list ending above it.
        contentPadding = WindowInsets.navigationBars.asPaddingValues()
    ) {
        // One status line that always says something: how much is queued, or — when that has stopped
        // mattering because the user paused it — why nothing is moving. Pausing leaves every row looking
        // exactly as it did, so without this the screen and a stalled queue are the same picture.
        item(key = STATUS_LINE_KEY) { StatusLine(state) }

        state.series.forEachIndexed { index, series ->
            // A full-bleed rule between groups, against the inset one *within* a group below: the two
            // read as different weights of separation, which is what says "new series" rather than
            // "next chapter" without a second heading style.
            if (index > 0) {
                item(key = "divider-${series.manga.id}") { TanukiRowDivider() }
            }
            item(key = "series-${series.manga.id}") {
                DownloadSeriesHeader(
                    series = series,
                    onOpen = { onOpenManga(series.manga.id) },
                    onCancel = { onCancelSeries(series.manga.id) }
                )
            }
            itemsIndexed(
                items = series.chapters,
                key = { _, queued -> queued.chapter.id.toString() }
            ) { position, queued ->
                if (position > 0) {
                    // Inset to start under the rows' titles rather than their status glyphs — the
                    // grouped-list convention the Settings index uses, with the row's own leading width.
                    TanukiRowDivider(startInset = TanukiListRowDefaults.dividerInset(QueueGlyphSize))
                }
                DownloadQueueRow(
                    queued = queued,
                    onCancel = { onCancelChapter(queued.chapter.id) },
                    onRetry = { onRetryChapter(queued.chapter.id) }
                )
            }
        }
    }
}

/**
 * The line above the queue: what is in it, or why nothing is moving.
 *
 * The paused state is carried by a **glyph and the words**, not by a hue. Tinting the sentence with the
 * accent was the first instinct and it fails twice: at caption size the accent clears only 3.35:1 against
 * the light dialect's paper canvas — under the 4.5:1 small-text floor — and a colour alone tells a
 * colour-blind reader nothing. The pause glyph repeats the top bar's own toggle, so the two agree, and
 * the text sits at full `onSurface` weight, which is what actually makes it read as the more important of
 * the two messages.
 */
@Composable
private fun StatusLine(state: DownloadsState) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = TanukiTheme.spacing.gutter, vertical = TanukiTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (state.paused) {
            PauseIcon(tint = TanukiTheme.colors.accent, size = StatusGlyphSize)
            Text(
                text = stringResource(Res.string.downloads_paused_banner),
                style = TanukiTheme.typography.caption,
                color = MaterialTheme.colorScheme.onSurface
            )
        } else {
            Text(
                text =
                    pluralStringResource(
                        Res.plurals.downloads_queue_count,
                        state.chapterCount,
                        state.chapterCount
                    ),
                style = TanukiTheme.typography.caption,
                // `onSurfaceVariant`, not `textFaint`: the faint token is 2.5:1 on the light canvas,
                // which is below the 3:1 floor for even secondary text. This is the same token the
                // list rows' own subtitles use, so the whole screen's second tier matches.
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Centres the loading and empty states in what is left below the app bar. */
@Composable
private fun CenteredBox(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}