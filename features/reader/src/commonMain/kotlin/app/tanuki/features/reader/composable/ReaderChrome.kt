package app.tanuki.features.reader.composable

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.ChevronRightIcon
import app.tanuki.assets.designSystem.component.TanukiIconButton
import app.tanuki.core.domain.chapter.model.ChapterNumber
import app.tanuki.core.domain.manga.model.ReadingMode
import app.tanuki.features.reader.composable.components.PageScrubber
import app.tanuki.features.reader.composable.components.chapterTitle
import app.tanuki.features.reader.viewmodel.ReaderState.Content
import kotlin.uuid.Uuid

/** Immersive top/bottom chrome, shown when [Content.chromeVisible] and hidden on tap. */
@Composable
internal fun BoxScope.ReaderChrome(
    content: Content,
    onBack: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onScrubToPage: (Int) -> Unit
) {
    val scrim = TanukiTheme.colors.scrimSoft

    // Top bar: back + current chapter title.
    // TODO: add a three-dot menu for more options. change reading mode from reader.
    AnimatedVisibility(
        visible = content.chromeVisible,
        enter = fadeIn() + slideInVertically { -it },
        exit = fadeOut() + slideOutVertically { -it },
        modifier = Modifier.align(Alignment.TopCenter)
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(scrim)
                    .statusBarsPadding()
                    .padding(horizontal = TanukiTheme.spacing.sm, vertical = TanukiTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.sm)
        ) {
            TanukiIconButton(
                onClick = onBack,
                icon = { tint -> ChevronRightIcon(tint = tint, modifier = Modifier.rotate(180f)) }
            )
            Text(
                text = chapterTitle(content.chapterNumber, content.chapterName),
                style = TanukiTheme.typography.title,
                color = Color.White
            )
        }
    }

    // Bottom bar: page count over a [prev chapter · scrubber · next chapter] row.
    AnimatedVisibility(
        visible = content.chromeVisible,
        enter = fadeIn() + slideInVertically { it },
        exit = fadeOut() + slideOutVertically { it },
        modifier = Modifier.align(Alignment.BottomCenter)
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(scrim)
                    .navigationBarsPadding()
                    .padding(horizontal = TanukiTheme.spacing.sm, vertical = TanukiTheme.spacing.xs)
        ) {
            PageScrubber(
                currentPage = content.currentPage,
                pageCount = content.currentPageCount,
                reversed = content.readingMode == ReadingMode.RIGHT_TO_LEFT,
                hasPreviousChapter = content.hasPreviousChapter,
                hasNextChapter = content.hasNextChapter,
                onPreviousChapter = onPreviousChapter,
                onNextChapter = onNextChapter,
                onScrubToPage = onScrubToPage
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ReaderChromePreview() {
    Box(modifier = Modifier.fillMaxSize()) {
        ReaderChrome(
            content =
                Content(
                    chapterName = "Chapter Name",
                    currentPage = 1,
                    currentPageCount = 10,
                    hasPreviousChapter = true,
                    hasNextChapter = true,
                    readingMode = ReadingMode.RIGHT_TO_LEFT,
                    chromeVisible = true,
                    initialItemIndex = 0,
                    items = emptyList(),
                    currentChapterId = Uuid.random(),
                    chapterNumber = ChapterNumber(1)
                ),
            onBack = {},
            onPreviousChapter = {},
            onNextChapter = {},
            onScrubToPage = {}
        )
    }
}