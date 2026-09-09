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

/**
 * Immersive top/bottom chrome, shown when [visible] and hidden on tap.
 *
 * Takes the scalars it renders rather than the whole [Content] on purpose: the chrome is a function of
 * the *current page*, not of the item list, and a `Content` parameter would recompose it every time a
 * chapter loaded into the reader's window for no visible change.
 */
@Composable
internal fun BoxScope.ReaderChrome(
    visible: Boolean,
    chapterNumber: ChapterNumber?,
    chapterName: String?,
    currentPage: Int,
    pageCount: Int,
    readingMode: ReadingMode,
    hasPreviousChapter: Boolean,
    hasNextChapter: Boolean,
    onBack: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onScrubToPage: (Int) -> Unit
) {
    val scrim = TanukiTheme.colors.scrimSoft

    // Top bar: back + current chapter title.
    // TODO: add a three-dot menu for more options. change reading mode from reader.
    AnimatedVisibility(
        visible = visible,
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
                text = chapterTitle(chapterNumber, chapterName),
                style = TanukiTheme.typography.title,
                color = Color.White
            )
        }
    }

    // Bottom bar: page count over a [prev chapter · scrubber · next chapter] row.
    AnimatedVisibility(
        visible = visible,
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
                currentPage = currentPage,
                pageCount = pageCount,
                reversed = readingMode == ReadingMode.RIGHT_TO_LEFT,
                hasPreviousChapter = hasPreviousChapter,
                hasNextChapter = hasNextChapter,
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
            visible = true,
            chapterNumber = ChapterNumber(1),
            chapterName = "Chapter Name",
            currentPage = 1,
            pageCount = 10,
            readingMode = ReadingMode.RIGHT_TO_LEFT,
            hasPreviousChapter = true,
            hasNextChapter = true,
            onBack = {},
            onPreviousChapter = {},
            onNextChapter = {},
            onScrubToPage = {}
        )
    }
}