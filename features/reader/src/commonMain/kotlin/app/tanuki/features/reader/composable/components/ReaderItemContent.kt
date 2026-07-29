package app.tanuki.features.reader.composable.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.EmptyLibraryIcon
import app.tanuki.assets.designSystem.component.TanukiLoadingIndicator
import app.tanuki.core.domain.source.model.Page
import app.tanuki.features.reader.LocalPageImageLoader
import app.tanuki.features.reader.resources.Res
import app.tanuki.features.reader.resources.reader_end_subtitle
import app.tanuki.features.reader.resources.reader_end_title
import app.tanuki.features.reader.resources.reader_transition_finished
import app.tanuki.features.reader.resources.reader_transition_swipe_hint
import app.tanuki.features.reader.resources.reader_transition_up_next
import app.tanuki.features.reader.utils.toImageRequest
import app.tanuki.features.reader.viewmodel.ReaderItem
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import me.saket.telephoto.zoomable.ZoomableContentLocation
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable
import org.jetbrains.compose.resources.stringResource

/** Renders one [ReaderItem]: a chapter page image, or a chapter transition slide. The [transitionModifier]
 *  sizes the slide to its host (full screen in a pager, full viewport in the strip). [zoomable] says whether
 *  a page owns its zoom gestures — true in a pager, where a page *is* the viewport; false in the long strip,
 *  which zooms every visible page together from the [WebtoonReader] level and handles taps itself. [ratios]
 *  is the strip's memory of page proportions (see [PageAspectRatios]); a pager sizes pages by the viewport
 *  and doesn't need one. */
@Composable
internal fun ReaderItemContent(
    item: ReaderItem,
    onToggleChrome: () -> Unit,
    fillHeight: Boolean = true,
    zoomable: Boolean = true,
    ratios: PageAspectRatios? = null,
    transitionModifier: Modifier = Modifier
) {
    when (item) {
        is ReaderItem.PageItem -> {
            ReaderPage(item.page, item.key, onToggleChrome, fillHeight, zoomable, ratios)
        }

        is ReaderItem.Transition -> {
            ChapterTransition(item, onToggleChrome, transitionModifier)
        }

        ReaderItem.End -> {
            EndOfSeries(onToggleChrome, transitionModifier)
        }
    }
}

/**
 * A single page's image. Fetched through the reader's dedicated page [LocalPageImageLoader] (not the
 * singleton cover loader) from the page's direct [Page.imageUrl], falling back to its [Page.url] for
 * sources that address pages by URL.
 *
 * When [zoomable] (the paged modes), the image is wrapped in Telephoto's [zoomable] gesture layer, so the
 * page gets pinch-to-zoom, double-tap-to-zoom, and pan. Telephoto owns the fit transform
 * ([rememberZoomableState] with `autoApplyTransformations`), so the [Image] draws at natural size
 * ([ContentScale.Inside]) and the state scales it into the viewport; the decoded page's real size is handed
 * to the state via [setContentLocation] so pan/zoom bounds hug the image. The gesture layer only consumes
 * drags while zoomed in, handing the swipe back through at rest, so paging is unaffected. A single tap
 * toggles the reader chrome (double-tap is reserved for zoom) — no ripple, since the whole surface is the
 * target.
 *
 * In the long strip [zoomable] is false: a page there is one tile of a continuous image, so zooming it
 * alone would misalign the pages around it. The strip scales all of its pages together and owns the tap and
 * pinch gestures itself, leaving the page as a plain image.
 *
 * [fillHeight] distinguishes the two hosts. A pager page fills the screen and fits within it. A strip page
 * fills the *width* — always, at any load state — and takes its height from [ratios], the reader's memory of
 * page proportions: an item that has been seen or prefetched is measured at its true height straight away,
 * so nothing resizes after the fact. Sizing the strip page with [ContentScale.FillWidth] rather than
 * [ContentScale.Inside] is what keeps a not-yet-known ratio from *shrinking* the page: `Inside` would fit a
 * tall page into a too-short placeholder slot and draw it inset, so it would sit narrower than the page
 * above it — reading as if the two pages were at different zoom levels.
 */
@Composable
private fun ReaderPage(
    page: Page,
    key: String,
    onToggleChrome: () -> Unit,
    fillHeight: Boolean = true,
    zoomable: Boolean = true,
    ratios: PageAspectRatios? = null
) {
    val context = LocalPlatformContext.current
    val request = remember(context, page) { page.toImageRequest(context) }
    val painter =
        rememberAsyncImagePainter(model = request, imageLoader = LocalPageImageLoader.current)
    val zoomState = rememberZoomableState()

    val painterState by painter.state.collectAsState()
    // The page's true pixel size comes from the decoded result, never from `painter.intrinsicSize`: while
    // Coil crossfades, the painter is a `CrossfadePainter` whose intrinsic size is the *max* of the
    // outgoing and incoming images — the right proportions for neither, so a reused item can be measured
    // against a blend of the page it used to show and the one it now shows.
    val decoded: Size? =
        (painterState as? AsyncImagePainter.State.Success)
            ?.result
            ?.image
            ?.let { Size(it.width.toFloat(), it.height.toFloat()) }
            ?.takeIf { it.width > 0f && it.height > 0f }
    LaunchedEffect(decoded, zoomable) {
        if (decoded == null) return@LaunchedEffect
        ratios?.record(key, decoded.width, decoded.height)
        if (zoomable) {
            zoomState.setContentLocation(ZoomableContentLocation.scaledInsideAndCenterAligned(decoded))
        }
    }

    if (fillHeight) {
        Image(
            painter = painter,
            contentDescription = null,
            modifier =
                Modifier
                    .fillMaxSize()
                    .let { if (zoomable) it.zoomable(zoomState, onClick = { onToggleChrome() }) else it },
            contentScale = ContentScale.Inside,
            alignment = Alignment.Center
        )
        return
    }

    val ratio =
        decoded?.let { it.width / it.height }
            ?: ratios?.of(key)
            ?: DEFAULT_PAGE_ASPECT_RATIO
    Image(
        painter = painter,
        contentDescription = null,
        modifier = Modifier.fillMaxWidth().aspectRatio(ratio).clipToBounds(),
        contentScale = ContentScale.FillWidth,
        alignment = Alignment.TopCenter
    )
}

/**
 * The full-surface slide between two chapters: [ReaderItem.Transition.from] just finished, and
 * [ReaderItem.Transition.to] is up next. It is purely a boundary marker — the next chapter's pages sit
 * right after it, so the reader continues with one more swipe (a spinner shows while those pages are
 * still loading). Tapping toggles the chrome like a page does, so nothing on this slide can trigger an
 * accidental chapter jump.
 */
@Composable
private fun ChapterTransition(
    item: ReaderItem.Transition,
    onToggleChrome: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier =
            modifier
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onToggleChrome
                ).padding(TanukiTheme.spacing.x3l),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.lg, Alignment.CenterVertically)
    ) {
        TransitionSection(
            label = stringResource(Res.string.reader_transition_finished),
            title = chapterTitle(item.from.number, item.from.name),
            emphasized = false
        )
        TransitionSection(
            label = stringResource(Res.string.reader_transition_up_next),
            title = chapterTitle(item.to.number, item.to.name),
            emphasized = true
        )
        Spacer(Modifier.height(TanukiTheme.spacing.xs))
        if (item.loading) {
            TanukiLoadingIndicator()
        } else {
            Text(
                text = stringResource(Res.string.reader_transition_swipe_hint),
                style = TanukiTheme.typography.caption,
                color = TanukiTheme.colors.textFaint,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * The terminal slide after the last page of the last chapter: an open-book mark over a "no more chapters"
 * message, reassuring the reader they've reached the end of everything available rather than hitting a
 * silent dead-end. There is nothing to swipe into past it. Tapping toggles the chrome like a page.
 */
@Composable
private fun EndOfSeries(
    onToggleChrome: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier =
            modifier
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onToggleChrome
                ).padding(TanukiTheme.spacing.x3l),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.md, Alignment.CenterVertically)
    ) {
        EmptyLibraryIcon(tint = TanukiTheme.colors.textFaint, size = 56.dp)
        Text(
            text = stringResource(Res.string.reader_end_title),
            style = TanukiTheme.typography.headline,
            color = Color.White,
            textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(Res.string.reader_end_subtitle),
            style = TanukiTheme.typography.caption,
            color = TanukiTheme.colors.textFaint,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * A labelled chapter reference on a [ChapterTransition] slide: a small overline over the chapter title.
 * [emphasized] marks the chapter being moved *toward* (accent overline, bright title); the un-emphasized
 * form dims the chapter being left behind so the eye lands on where reading continues.
 */
@Composable
private fun TransitionSection(
    label: String,
    title: String,
    emphasized: Boolean
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.xxs)
    ) {
        Text(
            text = label,
            style = TanukiTheme.typography.label,
            color = if (emphasized) TanukiTheme.colors.accent else TanukiTheme.colors.textFaint,
            textAlign = TextAlign.Center
        )
        Text(
            text = title,
            style = if (emphasized) TanukiTheme.typography.headline else TanukiTheme.typography.title,
            color = if (emphasized) Color.White else Color.White.copy(alpha = 0.6f),
            textAlign = TextAlign.Center
        )
    }
}