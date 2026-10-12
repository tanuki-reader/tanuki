package app.tanuki.features.reader.composable

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import app.tanuki.assets.designSystem.component.TanukiSymbols
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.TanukiMenu
import app.tanuki.assets.designSystem.component.TanukiMenuItem
import app.tanuki.core.domain.manga.model.ReadingMode
import app.tanuki.features.reader.composable.components.PageScrubber
import app.tanuki.features.reader.composable.components.chapterTitle
import app.tanuki.features.reader.resources.Res
import app.tanuki.features.reader.resources.reader_back
import app.tanuki.features.reader.resources.reader_bookmark_add
import app.tanuki.features.reader.resources.reader_bookmark_remove
import app.tanuki.features.reader.resources.reader_chapters
import app.tanuki.features.reader.resources.reader_more
import app.tanuki.features.reader.resources.reader_open_in_browser
import app.tanuki.features.reader.resources.reader_settings
import org.jetbrains.compose.resources.stringResource

/**
 * The reader's chrome: shown on tap, faded by the ViewModel after a few idle seconds, never on screen
 * otherwise (the design system keeps the reader chrome-less by default).
 *
 * - **Top:** back · chapter title over the series name · bookmark this page · ⋯ (open in browser).
 * - **Bottom:** previous chapter · page scrubber with "12 / 48" · next chapter, then the **Chapters** and
 *   **Reader settings** sheets.
 *
 * Opaque `surface` bars — the Android dialect has no glass or blur.
 *
 * Takes the scalars it renders rather than the whole `Content` on purpose: the chrome is a function of the
 * *current page*, not of the item list, and a `Content` parameter would recompose it every time a chapter
 * loaded into the reader's window for no visible change.
 */
@Composable
internal fun BoxScope.ReaderChrome(
    visible: Boolean,
    chapterNumberLabel: String?,
    chapterName: String?,
    mangaTitle: String,
    isBookmarked: Boolean,
    currentPage: Int,
    pageCount: Int,
    readingMode: ReadingMode,
    hasPreviousChapter: Boolean,
    hasNextChapter: Boolean,
    onBack: () -> Unit,
    onToggleBookmark: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onScrubToPage: (Int) -> Unit,
    onScrubbingChange: (Boolean) -> Unit,
    onOpenChapters: () -> Unit,
    onOpenSettings: () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { -it },
        exit = fadeOut() + slideOutVertically { -it },
        modifier = Modifier.align(Alignment.TopCenter)
    ) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = TanukiTheme.spacing.xs, vertical = TanukiTheme.spacing.xxs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(TanukiSymbols.ArrowBack, contentDescription = stringResource(Res.string.reader_back))
                }
                Column(Modifier.weight(1f).padding(horizontal = TanukiTheme.spacing.xs)) {
                    Text(
                        text = chapterTitle(chapterNumberLabel, chapterName),
                        style = TanukiTheme.typography.headline,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // Content from the source, never localized.
                    Text(
                        text = mangaTitle,
                        style = TanukiTheme.typography.caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onToggleBookmark) {
                    Icon(
                        imageVector = if (isBookmarked) TanukiSymbols.BookmarkFilled else TanukiSymbols.Bookmark,
                        contentDescription =
                            stringResource(if (isBookmarked) Res.string.reader_bookmark_remove else Res.string.reader_bookmark_add),
                        // State, so accent (design system: accent marks state and the one primary action).
                        tint = if (isBookmarked) TanukiTheme.colors.accent else MaterialTheme.colorScheme.onSurface
                    )
                }
                MoreMenu(onOpenInBrowser)
            }
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it },
        exit = fadeOut() + slideOutVertically { it },
        modifier = Modifier.align(Alignment.BottomCenter)
    ) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
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
                    onScrubToPage = onScrubToPage,
                    onScrubbingChange = onScrubbingChange
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    ChromeAction(TanukiSymbols.FormatListBulleted, stringResource(Res.string.reader_chapters), onOpenChapters)
                    ChromeAction(TanukiSymbols.Tune, stringResource(Res.string.reader_settings), onOpenSettings)
                }
            }
        }
    }
}

@Composable
private fun MoreMenu(onOpenInBrowser: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(TanukiSymbols.MoreVert, contentDescription = stringResource(Res.string.reader_more))
        }
        TanukiMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            TanukiMenuItem(
                text = stringResource(Res.string.reader_open_in_browser),
                onClick = {
                    expanded = false
                    onOpenInBrowser()
                }
            )
        }
    }
}

/** A labelled bottom-bar action: words as well as a glyph, since these open whole sheets. */
@Composable
private fun ChromeAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Text(
            text = label,
            style = TanukiTheme.typography.label,
            modifier = Modifier.padding(start = TanukiTheme.spacing.xs)
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ReaderChromePreview() {
    TanukiTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            ReaderChrome(
                visible = true,
                chapterNumberLabel = "1",
                chapterName = "Chapter Name",
                mangaTitle = "Berserk",
                isBookmarked = true,
                currentPage = 1,
                pageCount = 10,
                readingMode = ReadingMode.RIGHT_TO_LEFT,
                hasPreviousChapter = true,
                hasNextChapter = true,
                onBack = {},
                onToggleBookmark = {},
                onOpenInBrowser = {},
                onPreviousChapter = {},
                onNextChapter = {},
                onScrubToPage = {},
                onScrubbingChange = {},
                onOpenChapters = {},
                onOpenSettings = {}
            )
        }
    }
}
