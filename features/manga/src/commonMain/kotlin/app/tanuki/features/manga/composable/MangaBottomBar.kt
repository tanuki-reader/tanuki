package app.tanuki.features.manga.composable

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.TanukiButton
import app.tanuki.assets.designSystem.component.TanukiButtonSize
import app.tanuki.core.domain.chapter.model.Chapter
import app.tanuki.features.manga.resources.Res
import app.tanuki.features.manga.resources.manga_read_action
import app.tanuki.features.manga.resources.manga_read_chapter
import org.jetbrains.compose.resources.stringResource

/**
 * The pinned primary-action bar at the bottom of the manga detail screen. It sits below the scrolling
 * content (never inside it) so the "Read" action stays visible no matter how far the chapter list is
 * scrolled. A hairline divider separates it from the content above.
 *
 * The label names the chapter the action resumes at — "Read Chapter 5" for the last chapter the user
 * read — falling back to a plain "Read" when there are no chapters (in which case the button is disabled)
 * or the resume chapter has no recognized number.
 *
 * @param resumeChapter the chapter "Read" opens; `null` disables the button (no chapters to read).
 * @param onReadClick the "Read" action was tapped.
 */
@Composable
fun MangaBottomBar(
    resumeChapter: Chapter?,
    onReadClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = TanukiTheme.spacing
    val label =
        resumeChapter?.chapterNumber?.let { stringResource(Res.string.manga_read_chapter, it.display) }
            ?: stringResource(Res.string.manga_read_action)

    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        TanukiButton(
            text = label,
            onClick = onReadClick,
            size = TanukiButtonSize.Large,
            block = true,
            enabled = resumeChapter != null,
            modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.gutter, vertical = spacing.md)
        )
    }
}
