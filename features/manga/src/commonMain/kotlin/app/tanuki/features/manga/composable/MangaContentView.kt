package app.tanuki.features.manga.composable

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.Segment
import app.tanuki.assets.designSystem.component.TanukiSegmentedControl
import app.tanuki.core.domain.chapter.model.Chapter
import app.tanuki.features.manga.composable.components.MangaHero
import app.tanuki.features.manga.composable.components.MangaInfoTab
import app.tanuki.features.manga.composable.components.chapterList
import app.tanuki.features.manga.resources.Res
import app.tanuki.features.manga.resources.manga_chapters_title
import app.tanuki.features.manga.resources.manga_tab_info
import app.tanuki.features.manga.viewmodel.MangaState.Content
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/** The two panes of the detail body's tabbed section. Ordinal order drives the slide direction. */
private enum class MangaTab { Chapters, Info }

/** Matches the design system's detail app-bar height; the hero collapses down to this (under the bar). */
private val DetailBarHeight = 56.dp

/**
 * The detail body: a **collapsing** hero, then a Chapters / Info segmented control, then the selected
 * pane, with the "Read" action bar pinned below — shown only on the Chapters tab (it slides away on Info,
 * which has no chapter to resume).
 *
 * Scrolling either pane collapses the hero via a shared [NestedScrollConnection]: the drag first shrinks
 * the hero (up to a bar-height sliver that tucks under the top bar), then scrolls the pane; at the top of
 * a pane, an over-scroll re-expands it. Because the shrinking space is a real [Column] child, the tabs /
 * pane / Read bar below just flow upward — the tabs pin under the top bar once the hero is gone. The
 * collapse offset is shared, so it carries over between tabs. [onHeroCollapsedChange] tells the host when
 * the hero has mostly collapsed, so the top bar can fade in the title.
 *
 * The two panes swap with the design system's tabbed slide-and-fade (the same [AnimatedContent] transition
 * as the library's filter sheet), directional by tab order. The Chapters pane stays a lazy [LazyColumn] —
 * so a series with hundreds of chapters never composes rows off screen — while the Info pane scrolls its
 * own (short) content.
 */
@Composable
fun MangaContentView(
    state: Content,
    onToggleSort: () -> Unit,
    onOpenChapterFilter: () -> Unit,
    onManageCategories: () -> Unit,
    onEditReadingStatus: () -> Unit,
    onChapterClick: (Chapter) -> Unit,
    onReadClick: () -> Unit,
    onAddToLibrary: () -> Unit,
    onRemoveFromLibrary: () -> Unit,
    onHeroCollapsedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = TanukiTheme.spacing
    var tab by remember { mutableStateOf(MangaTab.Chapters) }
    // Hoisted so the chapter scroll position survives switching to the Info tab and back.
    val chapterListState = rememberLazyListState()

    val density = LocalDensity.current
    // The hero collapses until only the top-bar strip (status bar + app bar) remains, so the tabs come to
    // rest exactly under the bar. heroFullPx is the hero's measured height; heroOffset is the live collapse
    // (0 = expanded, -maxCollapse = collapsed), consumed from the pane scroll by the connection below.
    val barHeightPx = WindowInsets.statusBars.getTop(density) + with(density) { DetailBarHeight.roundToPx() }
    var heroFullPx by remember { mutableIntStateOf(0) }
    var heroOffset by remember { mutableFloatStateOf(0f) }

    val nestedScroll =
        remember(barHeightPx) {
            object : NestedScrollConnection {
                fun consume(delta: Float): Float {
                    val maxCollapse = (heroFullPx - barHeightPx).coerceAtLeast(0).toFloat()
                    val next = (heroOffset + delta).coerceIn(-maxCollapse, 0f)
                    val consumed = next - heroOffset
                    heroOffset = next
                    return consumed
                }

                // Collapse before the pane scrolls (dragging content up); expand only once the pane can no
                // longer scroll down (it's at the top, so the leftover downward drag lands here).
                override fun onPreScroll(
                    available: Offset,
                    source: NestedScrollSource
                ): Offset = if (available.y < 0f) Offset(0f, consume(available.y)) else Offset.Zero

                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource
                ): Offset = if (available.y > 0f) Offset(0f, consume(available.y)) else Offset.Zero
            }
        }

    // Report "mostly collapsed" (past halfway), so the top bar goes solid and shows the title.
    val collapsed by remember(barHeightPx) {
        derivedStateOf {
            val maxCollapse = (heroFullPx - barHeightPx).coerceAtLeast(0).toFloat()
            maxCollapse > 0f && heroOffset <= -maxCollapse * 0.5f
        }
    }

    LaunchedEffect(collapsed) { onHeroCollapsedChange(collapsed) }

    Column(modifier = modifier.fillMaxWidth().nestedScroll(nestedScroll)) {
        // Collapsing hero: measured at full height, but laid out at the shrinking `visible` height with its
        // top clipping under the bar. Done in the layout pass (not via a state-read height) so scrolling
        // re-lays-out without recomposing the whole body; the fade runs in the draw pass for the same reason.
        Box(
            Modifier
                .fillMaxWidth()
                .clipToBounds()
                .layout { measurable, constraints ->
                    val placeable =
                        measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                    heroFullPx = placeable.height
                    val visible = (placeable.height + heroOffset).roundToInt().coerceIn(barHeightPx, placeable.height)
                    layout(placeable.width, visible) {
                        placeable.place(0, visible - placeable.height)
                    }
                }
        ) {
            MangaHero(
                manga = state.manga,
                chapterCount = state.chapters.size,
                categoryNames = state.categories.filter { it.isMember }.map { it.category.name.value },
                onManageCategories = onManageCategories,
                onEditReadingStatus = onEditReadingStatus,
                modifier =
                    Modifier.graphicsLayer {
                        val maxCollapse = (heroFullPx - barHeightPx).coerceAtLeast(0).toFloat()
                        val fraction = if (maxCollapse <= 0f) 1f else 1f + heroOffset / maxCollapse
                        // Hold full opacity until the final stretch, then fade as it tucks under the bar.
                        alpha = (fraction / 0.4f).coerceIn(0f, 1f)
                    }
            )
        }

        TanukiSegmentedControl(
            segments =
                listOf(
                    Segment(MangaTab.Chapters, stringResource(Res.string.manga_chapters_title)),
                    Segment(MangaTab.Info, stringResource(Res.string.manga_tab_info))
                ),
            selected = tab,
            onSelect = { tab = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.gutter, vertical = spacing.sm)
        )

        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                val width = { full: Int -> if (forward) full else -full }
                // sizeTransform = null: the panes fill a fixed slot, so we want only the horizontal slide —
                // without this, AnimatedContent's default size animation adds a second (clipping) pass as
                // the Read bar below resizes the slot, which reads as the content animating twice.
                ContentTransform(
                    targetContentEnter = slideInHorizontally(initialOffsetX = width) + fadeIn(),
                    initialContentExit = slideOutHorizontally(targetOffsetX = { -width(it) }) + fadeOut(),
                    sizeTransform = null
                )
            },
            label = "mangaTab",
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) { current ->
            when (current) {
                MangaTab.Chapters -> {
                    LazyColumn(
                        state = chapterListState,
                        contentPadding = PaddingValues(bottom = spacing.xl),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        chapterList(
                            chapters = state.chapters,
                            ascending = state.chapterSortAscending,
                            canFilter = state.canFilterScanlators,
                            filterActive = state.isChapterFilterActive,
                            onToggleSort = onToggleSort,
                            onOpenFilter = onOpenChapterFilter,
                            onChapterClick = onChapterClick
                        )
                    }
                }

                MangaTab.Info -> {
                    MangaInfoTab(
                        manga = state.manga,
                        source = state.source,
                        inLibrary = state.inLibrary,
                        onAddToLibrary = onAddToLibrary,
                        onRemoveFromLibrary = onRemoveFromLibrary,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        // The "Read" bar belongs to the Chapters tab only; it slides down out of view on Info.
        AnimatedVisibility(
            visible = tab == MangaTab.Chapters,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut()
        ) {
            MangaBottomBar(
                resumeChapter = state.resumeChapter,
                onReadClick = onReadClick
            )
        }
    }
}