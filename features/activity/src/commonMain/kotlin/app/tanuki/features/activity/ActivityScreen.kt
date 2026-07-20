package app.tanuki.features.activity

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.Segment
import app.tanuki.assets.designSystem.component.TanukiLoadingIndicator
import app.tanuki.assets.designSystem.component.TanukiSegmentedControl
import app.tanuki.core.navigation.Destination
import app.tanuki.core.navigation.NavEvent
import app.tanuki.features.activity.composable.HistoryList
import app.tanuki.features.activity.composable.StatisticsView
import app.tanuki.features.activity.resources.Res
import app.tanuki.features.activity.resources.activity_history_count
import app.tanuki.features.activity.resources.activity_history_empty
import app.tanuki.features.activity.resources.activity_tab_curve
import app.tanuki.features.activity.resources.activity_tab_history
import app.tanuki.features.activity.resources.activity_title
import app.tanuki.features.activity.viewmodel.ActivityState.Loaded
import app.tanuki.features.activity.viewmodel.ActivityState.Loading
import app.tanuki.features.activity.viewmodel.ActivityViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** The two top-level tabs of the Activity screen. */
private enum class ActivityTab { HISTORY, CURVE }

private val HeaderHeight = 56.dp

/**
 * Stateful entry point for the Activity tab: resolves the [ActivityViewModel] from Metro, collects its
 * state, and forwards its screen-scoped [NavEvent]s to [onNavigate] so the host performs the actual push.
 * Mirrors `LibraryRoute` — the only place the feature touches navigation mechanics.
 *
 * @param onNavigate invoked with the [Destination] a [NavEvent.NavTo] targets (a manga's detail, or the
 *   reader to resume a chapter).
 */
@Composable
fun ActivityRoute(
    modifier: Modifier = Modifier,
    viewModel: ActivityViewModel = metroViewModel(),
    onNavigate: (Destination) -> Unit
) {
    LaunchedEffect(viewModel) {
        viewModel.navEvents.collect { event ->
            when (event) {
                is NavEvent.NavTo -> onNavigate(event.destination)
                NavEvent.NavBack -> Unit
            }
        }
    }

    ActivityScreen(
        modifier = modifier,
        viewModel = viewModel
    )
}

@Composable
private fun ActivityScreen(
    modifier: Modifier = Modifier,
    viewModel: ActivityViewModel
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Two tabs → a boolean is enough and is trivially saveable across config changes.
    var showCurve by rememberSaveable { mutableStateOf(false) }
    val selectedTab = if (showCurve) ActivityTab.CURVE else ActivityTab.HISTORY

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding()
    ) {
        Text(
            text = stringResource(Res.string.activity_title),
            style = TanukiTheme.typography.title,
            color = MaterialTheme.colorScheme.onSurface,
            modifier =
                Modifier
                    .height(HeaderHeight)
                    .padding(horizontal = TanukiTheme.spacing.gutter, vertical = TanukiTheme.spacing.md)
        )

        when (val current = state) {
            Loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    TanukiLoadingIndicator()
                }
            }

            is Loaded -> {
                TanukiSegmentedControl(
                    segments =
                        listOf(
                            Segment(ActivityTab.HISTORY, stringResource(Res.string.activity_tab_history)),
                            Segment(ActivityTab.CURVE, stringResource(Res.string.activity_tab_curve))
                        ),
                    selected = selectedTab,
                    onSelect = { showCurve = it == ActivityTab.CURVE },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(TanukiTheme.spacing.gutter)
                )

                AnimatedContent(
                    targetState = selectedTab,
                    transitionSpec = {
                        val forward = targetState.ordinal > initialState.ordinal
                        val width = { full: Int -> if (forward) full else -full }
                        // sizeTransform = null: the panes fill a fixed slot, so we want only the horizontal
                        // slide — matching MangaContentView, which avoids AnimatedContent's default size pass.
                        ContentTransform(
                            targetContentEnter = slideInHorizontally(initialOffsetX = width) + fadeIn(),
                            initialContentExit = slideOutHorizontally(targetOffsetX = { -width(it) }) + fadeOut(),
                            sizeTransform = null
                        )
                    },
                    label = "activityTab",
                    modifier = Modifier.weight(1f)
                ) { tab ->
                    when (tab) {
                        ActivityTab.HISTORY -> {
                            HistoryList(
                                entries = current.history,
                                countLabel =
                                    pluralStringResource(
                                        Res.plurals.activity_history_count,
                                        current.history.size,
                                        current.history.size
                                    ),
                                emptyMessage = stringResource(Res.string.activity_history_empty),
                                onEntryClick = viewModel::onHistoryClick,
                                onOpenSeries = { viewModel.onOpenSeries(it.manga.id) },
                                onDeleteEntry = viewModel::onDeleteHistory,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        ActivityTab.CURVE -> {
                            Column(
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState())
                            ) {
                                StatisticsView(
                                    period = current.period,
                                    activity = current.activity,
                                    mostRead = current.mostRead,
                                    stats = current.stats,
                                    onPeriodChange = viewModel::onPeriodChange,
                                    onOpenSeries = { viewModel.onOpenSeries(it.manga.id) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}