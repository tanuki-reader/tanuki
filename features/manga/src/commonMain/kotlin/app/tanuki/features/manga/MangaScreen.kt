package app.tanuki.features.manga

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.TanukiDetailTopBar
import app.tanuki.assets.designSystem.component.TanukiLoadingIndicator
import app.tanuki.core.navigation.Destination
import app.tanuki.core.navigation.NavEvent
import app.tanuki.features.manga.composable.MangaContentView
import app.tanuki.features.manga.composable.components.ChapterFilterSheet
import app.tanuki.features.manga.composable.components.ChapterSelectionTopBar
import app.tanuki.features.manga.composable.components.MangaCategoriesSheet
import app.tanuki.features.manga.composable.components.MangaDownloadMenu
import app.tanuki.features.manga.composable.components.ReadingModeSheet
import app.tanuki.features.manga.composable.components.ReadingStatusSheet
import app.tanuki.features.manga.resources.Res
import app.tanuki.features.manga.resources.manga_not_found
import app.tanuki.features.manga.viewmodel.MangaState.ChapterSelection
import app.tanuki.features.manga.viewmodel.MangaState.Content
import app.tanuki.features.manga.viewmodel.MangaState.Loading
import app.tanuki.features.manga.viewmodel.MangaState.NotFound
import app.tanuki.features.manga.viewmodel.MangaViewModel
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid

/**
 * Stateful entry point for a manga's detail screen. Resolves the [MangaViewModel] from Metro — assisted
 * with the [mangaId] the route carried — collects its state, and forwards its screen-scoped [NavEvent]s:
 * a [NavEvent.NavTo] (open the reader) to [onNavigate], a [NavEvent.NavBack] (the back arrow) to [onBack].
 * This is the only place the feature touches navigation mechanics — the composable below stays pure UI.
 *
 * @param mangaId the manga to show — decoded from the route by the host `NavHost`.
 * @param onNavigate invoked with the [Destination] a [NavEvent.NavTo] targets (e.g., the reader).
 * @param onBack invoked when navigation should pop back to the previous screen.
 */
@Composable
fun MangaRoute(
    mangaId: Uuid,
    onNavigate: (Destination) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel = assistedMetroViewModel<MangaViewModel, MangaViewModel.Factory> { create(mangaId) }

    LaunchedEffect(viewModel) {
        viewModel.navEvents.collect { event ->
            when (event) {
                is NavEvent.NavTo -> onNavigate(event.destination)
                NavEvent.NavBack -> onBack()
            }
        }
    }

    MangaScreen(modifier = modifier, viewModel = viewModel)
}

@Composable
private fun MangaScreen(
    modifier: Modifier = Modifier,
    viewModel: MangaViewModel
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showCategoriesSheet by remember { mutableStateOf(false) }
    var showChapterFilterSheet by remember { mutableStateOf(false) }
    var showReadingStatusSheet by remember { mutableStateOf(false) }
    var showReadingModeSheet by remember { mutableStateOf(false) }
    // Reported by MangaContentView as the hero collapses; drives the top bar's solid/title state.
    var heroCollapsed by remember { mutableStateOf(false) }

    Box(
        // No status-bar inset here: the hero draws edge-to-edge behind the status bar. The top bar and
        // hero content inset themselves; the bottom keeps its navigation-bar inset for the action bar.
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .navigationBarsPadding()
    ) {
        Column(Modifier.fillMaxSize()) {
            when (val current = state) {
                Loading -> {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        TanukiLoadingIndicator()
                    }
                }

                NotFound -> {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(Res.string.manga_not_found),
                            style = TanukiTheme.typography.body,
                            color = TanukiTheme.colors.textFaint
                        )
                    }
                }

                is Content -> {
                    MangaContentView(
                        state = current,
                        onToggleSort = viewModel::onToggleChapterSort,
                        onOpenChapterFilter = { showChapterFilterSheet = true },
                        onManageCategories = { showCategoriesSheet = true },
                        onEditReadingStatus = { showReadingStatusSheet = true },
                        onEditReadingMode = { showReadingModeSheet = true },
                        onChapterClick = { chapter -> viewModel.onChapterClick(chapter.id) },
                        onChapterLongClick = { chapter -> viewModel.onChapterLongClick(chapter.id) },
                        onChapterDownloadClick = { chapter -> viewModel.onChapterDownloadClick(chapter.id) },
                        onChapterDownloadDelete = { chapter -> viewModel.onDeleteChapterDownload(chapter.id) },
                        onSelectAbove = viewModel::onSelectAbove,
                        onSelectBelow = viewModel::onSelectBelow,
                        onMarkSelectionRead = { viewModel.onMarkSelectionRead(read = true) },
                        onMarkSelectionUnread = { viewModel.onMarkSelectionRead(read = false) },
                        onDownloadSelection = viewModel::onDownloadSelection,
                        onDeleteSelectionDownloads = viewModel::onDeleteSelectionDownloads,
                        onClearSelection = viewModel::onClearSelection,
                        onReadClick = viewModel::onReadClick,
                        onAddToLibrary = viewModel::onAddToLibrary,
                        onRemoveFromLibrary = viewModel::onRemoveFromLibrary,
                        onHeroCollapsedChange = { heroCollapsed = it },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        val selection = (state as? Content)?.selection ?: ChapterSelection.None

        // System back leaves the selection before it leaves the screen — the mode the user is in is the
        // one back has to undo, and losing a 200-chapter selection to a stray swipe would be brutal. It
        // only intercepts while a selection is running, so back otherwise pops the screen as always.
        val backState = rememberNavigationEventState(NavigationEventInfo.None)
        NavigationBackHandler(
            state = backState,
            isBackEnabled = selection.isActive,
            onBackCompleted = viewModel::onClearSelection
        )

        // Overlaid on top so it floats over the hero. While the hero shows the title the bar stays
        // transparent (just the back arrow); once the hero collapses under it, the bar goes solid and fades
        // in the title. The loading/not-found states have no hero, so it renders solid immediately.
        //
        // A running selection replaces the bar outright (cross-faded) rather than adding controls to it:
        // the screen is in a different mode, and the back arrow in particular has to stop meaning "leave".
        AnimatedContent(
            targetState = selection.isActive,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "mangaTopBar",
            modifier = Modifier.align(Alignment.TopStart)
        ) { selecting ->
            if (selecting) {
                ChapterSelectionTopBar(
                    selection = selection,
                    onClose = viewModel::onClearSelection,
                    onToggleSelectAll = viewModel::onToggleSelectAll
                )
            } else {
                TanukiDetailTopBar(
                    title =
                        (state as? Content)
                            ?.manga
                            ?.title
                            ?.value
                            .orEmpty(),
                    onBack = viewModel::onBack,
                    collapsed = state !is Content || heroCollapsed,
                    actions = {
                        // Only once there is a chapter list to act on — an empty or unresolved series has
                        // nothing to queue, and an action that can only disappoint is worse than none.
                        (state as? Content)?.takeIf { it.chapters.isNotEmpty() }?.let { content ->
                            MangaDownloadMenu(
                                hasDownloads = content.hasDownloads,
                                onDownloadNext = viewModel::onDownloadNextChapters,
                                onDownloadAll = viewModel::onDownloadAllChapters,
                                onDeleteAll = viewModel::onDeleteAllDownloads
                            )
                        }
                    }
                )
            }
        }

        val content = state as? Content
        if (showCategoriesSheet && content != null) {
            MangaCategoriesSheet(
                categories = content.categories,
                onToggle = viewModel::onToggleCategory,
                onDismiss = { showCategoriesSheet = false }
            )
        }
        if (showChapterFilterSheet && content != null) {
            ChapterFilterSheet(
                scanlators = content.scanlators,
                visibleChapterCount = content.chapters.size,
                onToggle = viewModel::onToggleScanlator,
                onReset = viewModel::onResetChapterFilter,
                onDismiss = { showChapterFilterSheet = false }
            )
        }
        if (showReadingStatusSheet && content != null) {
            ReadingStatusSheet(
                current = content.manga.readingStatus,
                onSelect = { status ->
                    viewModel.onSetReadingStatus(status)
                    showReadingStatusSheet = false
                },
                onDismiss = { showReadingStatusSheet = false }
            )
        }
        if (showReadingModeSheet && content != null) {
            ReadingModeSheet(
                current = content.manga.readingMode,
                onSelect = { mode ->
                    viewModel.onSetReadingMode(mode)
                    showReadingModeSheet = false
                },
                onDismiss = { showReadingModeSheet = false }
            )
        }
    }
}