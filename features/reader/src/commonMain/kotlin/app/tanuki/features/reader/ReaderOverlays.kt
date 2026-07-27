package app.tanuki.features.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.tanuki.assets.designSystem.TanukiMotion
import app.tanuki.assets.designSystem.TanukiRadii
import app.tanuki.assets.designSystem.TanukiTheme
import kotlinx.coroutines.delay
import kotlin.uuid.Uuid

/**
 * Ambient reading-progress hairline pinned to the bottom edge, shown only while the chrome is hidden. In
 * continuous reading the full scrubber is out of sight most of the time, so this keeps the reader oriented
 * — "how far into this chapter am I" — without breaking immersion. It reflects the *current chapter's*
 * progress (it resets at each boundary), fills from the reading side (right in [reversed] / RTL), and sits
 * above the system navigation inset so gesture bars never cover it. Fades with the chrome so the two never
 * show the progress at once. Non-interactive: taps pass through to toggle the chrome.
 *
 * @param visible typically the inverse of the chrome's visibility.
 */
@Composable
internal fun BoxScope.ChapterProgressBar(
    currentPage: Int,
    pageCount: Int,
    reversed: Boolean,
    visible: Boolean,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(TanukiMotion.screenEnterMillis, easing = TanukiMotion.EaseEnter)),
        exit = fadeOut(tween(TanukiMotion.screenExitMillis, easing = TanukiMotion.EaseExit)),
        modifier = modifier.align(Alignment.BottomCenter)
    ) {
        // A one-page chapter is always "complete"; otherwise fill through the current page.
        val fraction = if (pageCount <= 1) 1f else ((currentPage + 1).toFloat() / pageCount).coerceIn(0f, 1f)
        Box(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(PROGRESS_BAR_HEIGHT)
                .background(Color.White.copy(alpha = 0.16f))
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .align(if (reversed) Alignment.CenterEnd else Alignment.CenterStart)
                    .background(TanukiTheme.colors.accent)
            )
        }
    }
}

/**
 * Transient confirmation that the reader has flowed into a new chapter. Because paging across a boundary
 * no longer navigates, there's no screen change to signal it — so when [chapterId] changes this briefly
 * fades a title pill in at the top and gives a single light haptic tick, then fades out. It only shows
 * while the chrome is hidden (the top bar already names the chapter otherwise) and never fires on the
 * first chapter — only on an actual crossing. Non-interactive.
 *
 * @param title the already-resolved, localized chapter title to show.
 */
@Composable
internal fun BoxScope.ChapterChangeIndicator(
    chapterId: Uuid,
    title: String,
    chromeVisible: Boolean,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    var visible by remember { mutableStateOf(false) }
    // Seeded with the opening chapter so the pill doesn't announce the chapter the reader just opened.
    var previousId by remember { mutableStateOf(chapterId) }

    LaunchedEffect(chapterId) {
        if (chapterId != previousId) {
            previousId = chapterId
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            visible = true
            delay(CHAPTER_PILL_VISIBLE_MS)
            visible = false
        }
    }

    AnimatedVisibility(
        visible = visible && !chromeVisible,
        enter = fadeIn(tween(TanukiMotion.screenEnterMillis, easing = TanukiMotion.EaseEnter)),
        exit = fadeOut(tween(TanukiMotion.screenExitMillis, easing = TanukiMotion.EaseExit)),
        modifier = modifier.align(Alignment.TopCenter)
    ) {
        Box(
            Modifier
                .statusBarsPadding()
                .padding(top = TanukiTheme.spacing.sm)
                .clip(TanukiRadii.pillShape)
                .background(TanukiTheme.colors.scrimSoft)
                .padding(horizontal = TanukiTheme.spacing.md, vertical = TanukiTheme.spacing.xs)
        ) {
            Text(
                text = title,
                style = TanukiTheme.typography.caption,
                color = Color.White
            )
        }
    }
}

/** Thickness of the ambient progress hairline. */
private val PROGRESS_BAR_HEIGHT = 3.dp

/** How long the chapter-change pill lingers before fading out. */
private const val CHAPTER_PILL_VISIBLE_MS = 2200L

@Preview(showBackground = true)
@Composable
private fun ChapterProgressBarPreview() {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        ChapterProgressBar(currentPage = 3, pageCount = 10, reversed = false, visible = true)
    }
}