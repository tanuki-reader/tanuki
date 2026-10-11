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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
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
import app.tanuki.assets.designSystem.component.TanukiBottomSheet
import app.tanuki.assets.designSystem.component.TanukiDetailTopBar
import app.tanuki.assets.designSystem.component.TanukiLoadingIndicator
import app.tanuki.core.navigation.Destination
import app.tanuki.core.navigation.NavEvent
import app.tanuki.features.manga.composable.MangaContentView
import app.tanuki.features.manga.composable.components.ChapterFilterSheet
import app.tanuki.features.manga.composable.components.ChapterSelectionTopBar
import app.tanuki.features.manga.composable.components.InfoMetadata
import app.tanuki.features.manga.composable.components.MangaCategoriesSheet
import app.tanuki.features.manga.composable.components.MangaDownloadMenu
import app.tanuki.features.manga.composable.components.MangaInfoTab
import app.tanuki.features.manga.composable.components.MangaRefreshError
import app.tanuki.features.manga.composable.components.ReadingModeSheet
import app.tanuki.features.manga.composable.components.ReadingStatusSheet
import app.tanuki.features.manga.resources.Res
import app.tanuki.features.manga.resources.manga_not_found
import app.tanuki.features.manga.viewmodel.MangaSheet
import app.tanuki.features.manga.viewmodel.MangaState.ChapterSelection
import app.tanuki.features.manga.viewmodel.MangaState.Content
import app.tanuki.features.manga.viewmodel.MangaState.Loading
import app.tanuki.features.manga.viewmodel.MangaState.NotFound
import app.tanuki.features.manga.viewmodel.MangaViewModel
import app.tanuki.features.manga.viewmodel.RecommendationsPlacement
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import kotlin.uuid.Uuid
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/** How long a failed-refresh banner stays up before it clears itself. */
private const val RefreshErrorDurationMs = 6_000L

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MangaScreen(
    modifier: Modifier = Modifier,
    viewModel: MangaViewModel
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Hoisted out of PullToRefreshBox so the custom indicator below reads the same drag state the box does.
    val pullState = rememberPullToRefreshState()
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
                    // The pull gesture reaches this box through the panes' nested scroll — but only after
                    // the collapsing hero has had its turn at the leftover downward drag, so an over-scroll
                    // re-expands the hero first and starts a refresh only once it is fully open. That is
                    // the right order: the hero is what the user is pulling back into view.
                    PullToRefreshBox(
                        isRefreshing = current.refresh.inProgress,
                        onRefresh = viewModel::onRefresh,
                        state = pullState,
                        indicator = {
                            PullToRefreshDefaults.Indicator(
                                state = pullState,
                                isRefreshing = current.refresh.inProgress,
                                containerColor = MaterialTheme.colorScheme.surface,
                                color = TanukiTheme.colors.accent,
                                // The hero runs edge-to-edge under the status bar, so an indicator placed
                                // from the top of the box would drop out of it while it travels.
                                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding()
                            )
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        MangaContentView(
                            state = current,
                            onToggleSort = viewModel::onToggleChapterSort,
                            onOpenChapterFilter = { viewModel.onOpenSheet(MangaSheet.ChapterFilter) },
                            onManageCategories = { viewModel.onOpenSheet(MangaSheet.Categories) },
                            onEditReadingStatus = { viewModel.onOpenSheet(MangaSheet.ReadingStatus) },
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
                            onOpenAbout = { viewModel.onOpenSheet(MangaSheet.About) },
                            onRecommendationClick = { viewModel.onRecommendationClick(it.title) },
                            onHeroCollapsedChange = { heroCollapsed = it },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
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
                                hasPendingDownloads = content.hasPendingDownloads,
                                onDownloadNext = viewModel::onDownloadNextChapters,
                                onDownloadAll = viewModel::onDownloadAllChapters,
                                onDeleteAll = viewModel::onDeleteAllDownloads,
                                onOpenQueue = viewModel::onOpenDownloadQueue
                            )
                        }
                    }
                )
            }
        }

        val content = state as? Content

        // A failed refresh is reported over the hero, just under the top bar — the strip a snackbar would
        // occupy at the bottom belongs to the Read action, and this is where the pull started. It clears
        // itself after a few seconds: the failure is already visible in that nothing new appeared, and a
        // banner that outstays the moment turns into furniture the user reads around.
        if (content?.refresh?.failed == true) {
            LaunchedEffect(Unit) {
                delay(RefreshErrorDurationMs)
                viewModel.onDismissRefreshError()
            }
            MangaRefreshError(
                onRetry = viewModel::onRefresh,
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = TanukiTheme.spacing.appBarHeight + TanukiTheme.spacing.sm)
            )
        }

        if (content?.sheet == MangaSheet.About) {
            // Reference material, off the main list: the former Info tab's content plus the metadata.
            TanukiBottomSheet(onDismissRequest = viewModel::onDismissSheet, skipPartiallyExpanded = false) {
                MangaInfoTab(
                    manga = content.manga,
                    source = content.source,
                    inLibrary = content.inLibrary,
                    onEditReadingMode = { viewModel.onOpenSheet(MangaSheet.ReadingMode) },
                    onAddToLibrary = viewModel::onAddToLibrary,
                    onRemoveFromLibrary = viewModel::onRemoveFromLibrary,
                    synopsis = content.header.synopsis,
                    onRecommendationClick = { viewModel.onRecommendationClick(it.title) },
                    metadata =
                        content.series?.takeIf { content.isMatched }?.let { series ->
                            InfoMetadata(
                                scoreLabel = content.scoreLabel,
                                genres = emptyList(),
                                tags = content.tags.filterNot { it.spoiler }.map { it.name },
                                useSourceInfo = content.useSourceInfo,
                                characters = series.characters.map { it.name },
                                recommendations =
                                    series.recommendations.takeIf {
                                        content.recommendationsPlacement == RecommendationsPlacement.AboutOnly
                                    }.orEmpty()
                            )
                        },
                    onUseSourceInfoChange = viewModel::onUseSourceInfoChange
                )
            }
        }
        if (content?.sheet == MangaSheet.Categories) {
            MangaCategoriesSheet(
                categories = content.categories,
                onToggle = viewModel::onToggleCategory,
                onDismiss = viewModel::onDismissSheet
            )
        }
        if (content?.sheet == MangaSheet.ChapterFilter) {
            ChapterFilterSheet(
                scanlators = content.scanlators.takeIf { content.canFilterScanlators }.orEmpty(),
                bookmarked = content.bookmarkedOnly.takeIf { content.canFilterBookmarked },
                visibleChapterCount = content.chapters.size,
                onToggleBookmarked = viewModel::onToggleBookmarkedOnly,
                onToggle = viewModel::onToggleScanlator,
                onReset = viewModel::onResetChapterFilter,
                onDismiss = viewModel::onDismissSheet
            )
        }
        if (content?.sheet == MangaSheet.ReadingStatus) {
            ReadingStatusSheet(
                current = content.manga.readingStatus,
                onSelect = viewModel::onSetReadingStatus,
                onDismiss = viewModel::onDismissSheet
            )
        }
        if (content?.sheet == MangaSheet.ReadingMode) {
            ReadingModeSheet(
                current = content.manga.readingMode,
                onSelect = viewModel::onSetReadingMode,
                onDismiss = viewModel::onDismissSheet
            )
        }
    }
}