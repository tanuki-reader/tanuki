package app.tanuki.features.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tanuki.assets.designSystem.component.TanukiLoadingIndicator
import app.tanuki.core.navigation.NavEvent
import app.tanuki.features.reader.composable.ReaderContent
import app.tanuki.features.reader.composable.ReaderError
import app.tanuki.features.reader.viewmodel.ReaderState
import app.tanuki.features.reader.viewmodel.ReaderViewModel
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import kotlin.uuid.Uuid

/**
 * Stateful entry point for the reader. Resolves the [ReaderViewModel] from Metro — assisted with the
 * [mangaId] and [chapterId] the route carried — collects its state, and forwards its one back intent.
 *
 * The reader reads continuously across chapters *in place*: paging past the last chapter no longer
 * navigates to a fresh route, so the only screen-scoped navigation left is [NavEvent.NavBack].
 *
 * @param mangaId the series being read — so the reader can page across sibling chapters.
 * @param chapterId the chapter to open — decoded from the route by the host `NavHost`.
 * @param onBack invoked when navigation should pop back to the previous screen.
 */
@Composable
fun ReaderRoute(
    mangaId: Uuid,
    chapterId: Uuid,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel =
        assistedMetroViewModel<ReaderViewModel, ReaderViewModel.Factory> { create(mangaId, chapterId) }

    LaunchedEffect(viewModel) {
        viewModel.navEvents.collect { event ->
            if (event is NavEvent.NavBack) onBack()
        }
    }

    ReaderScreen(modifier = modifier, viewModel = viewModel)
}

@Composable
private fun ReaderScreen(
    viewModel: ReaderViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Box(modifier.fillMaxSize().background(Color.Black)) {
        when (val current = state) {
            ReaderState.Loading -> {
                TanukiLoadingIndicator(Modifier.align(Alignment.Center))
            }

            ReaderState.Error -> {
                ReaderError(onRetry = viewModel::onRetry, modifier = Modifier.align(Alignment.Center))
            }

            is ReaderState.Content -> {
                ReaderContent(
                    content = current,
                    commands = viewModel.commands,
                    onItemFocused = viewModel::onItemFocused,
                    onToggleChrome = viewModel::onToggleChrome,
                    onBack = viewModel::onBack,
                    onPreviousChapter = viewModel::onPreviousChapter,
                    onNextChapter = viewModel::onNextChapter,
                    onScrubToPage = viewModel::onScrubToPage
                )
            }
        }
    }
}