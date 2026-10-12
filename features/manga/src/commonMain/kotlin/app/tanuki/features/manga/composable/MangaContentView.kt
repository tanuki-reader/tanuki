package app.tanuki.features.manga.composable

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import app.tanuki.assets.designSystem.TanukiSpacing
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.MangaCoverTile
import app.tanuki.assets.designSystem.component.TanukiTag
import app.tanuki.core.domain.chapter.model.Chapter
import app.tanuki.core.domain.metadata.model.MetadataRecommendation
import app.tanuki.features.manga.composable.components.MangaHero
import app.tanuki.features.manga.composable.components.chapterList
import app.tanuki.features.manga.resources.Res
import app.tanuki.features.manga.resources.manga_info_no_description
import app.tanuki.features.manga.resources.manga_info_show_more
import app.tanuki.features.manga.resources.manga_metadata_recommendations
import app.tanuki.features.manga.resources.manga_tags_more
import app.tanuki.features.manga.viewmodel.MangaState.Content
import app.tanuki.features.manga.viewmodel.RecommendationsPlacement
import app.tanuki.features.manga.viewmodel.TagRow
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource

/** The two panes of the detail body's tabbed section. Ordinal order drives the slide direction. */

/** Matches the design system's detail app-bar height; the hero collapses down to this (under the bar). */
private val DetailBarHeight = TanukiSpacing.appBarHeight

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
 * Long-pressing a chapter starts a multi-select; while one is running the "Read" bar gives way to the
 * [ChapterSelectionBar] in the same slot, and the host swaps the top bar to match.
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
    onChapterLongClick: (Chapter) -> Unit,
    onChapterDownloadClick: (Chapter) -> Unit,
    onChapterDownloadDelete: (Chapter) -> Unit,
    onSelectAbove: () -> Unit,
    onSelectBelow: () -> Unit,
    onMarkSelectionRead: () -> Unit,
    onMarkSelectionUnread: () -> Unit,
    onDownloadSelection: () -> Unit,
    onDeleteSelectionDownloads: () -> Unit,
    onClearSelection: () -> Unit,
    onReadClick: () -> Unit,
    onOpenAbout: () -> Unit,
    onRecommendationClick: (MetadataRecommendation) -> Unit,
    onHeroCollapsedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = TanukiTheme.spacing
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
            // Enrichment landing swaps the title and cover; fade it rather than jump.
            Crossfade(targetState = state.header, label = "header") { header ->
            MangaHero(
                manga = state.manga,
                header = header,
                heroLine = state.heroLine,
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
        }

        // One list: synopsis, one tag row, suggestions (only for a series not yet in the library — the
        // ViewModel decides), then the chapters. Reference material lives in the About sheet.
        LazyColumn(
            state = chapterListState,
            contentPadding = PaddingValues(bottom = spacing.xl),
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) {
            item(key = "synopsis") {
                Crossfade(targetState = state.header.synopsis, label = "synopsis") { synopsis ->
                    SynopsisPreview(text = synopsis, onMore = onOpenAbout)
                }
            }
            if (state.tagRow.visible.isNotEmpty()) {
                item(key = "tags") { Box(Modifier.animateItem()) { TagRowPreview(row = state.tagRow, onClick = onOpenAbout) } }
            }
            if (state.recommendationsPlacement == RecommendationsPlacement.AboveChapters) {
                state.series?.recommendations?.let { recommendations ->
                    item(key = "recommendations") { Box(Modifier.animateItem()) { RecommendationsRow(recommendations, onRecommendationClick) } }
                }
            }
            chapterList(
                chapters = state.chapters,
                ascending = state.chapterSortAscending,
                canFilter = state.canFilterChapters,
                filterActive = state.isChapterFilterActive,
                selectedIds = state.selection.ids,
                downloads = state.downloads,
                bookmarkCounts = state.bookmarkCounts,
                onToggleSort = onToggleSort,
                onOpenFilter = onOpenChapterFilter,
                onChapterClick = onChapterClick,
                onChapterLongClick = onChapterLongClick,
                onChapterDownloadClick = onChapterDownloadClick,
                onChapterDownloadDelete = onChapterDownloadDelete
            )
        }

        // The bottom bar swaps between reading and acting on a selection — a swap in place, not a second bar
        // stacked on the first, because the two are alternatives: nothing is being resumed mid-selection.
        // It sits below the list (not over it), so the last chapter is never covered.
        Box {
            AnimatedContent(targetState = state.selection.isActive, label = "chapterBottomBar") { selecting ->
                if (selecting) {
                    ChapterSelectionBar(
                        selection = state.selection,
                        onSelectAbove = onSelectAbove,
                        onSelectBelow = onSelectBelow,
                        onMarkRead = onMarkSelectionRead,
                        onMarkUnread = onMarkSelectionUnread,
                        onDownload = onDownloadSelection,
                        onDeleteDownload = onDeleteSelectionDownloads
                    )
                } else {
                    MangaBottomBar(
                        resumeChapter = state.resumeChapter,
                        onReadClick = onReadClick
                    )
                }
            }
        }
    }
}

/** The synopsis, three lines; "Show more" opens the About sheet with the full text. */
@Composable
private fun SynopsisPreview(
    text: String?,
    onMore: () -> Unit
) {
    val spacing = TanukiTheme.spacing
    Column(Modifier.padding(horizontal = spacing.gutter, vertical = spacing.sm)) {
        Text(
            text = text ?: stringResource(Res.string.manga_info_no_description),
            style = TanukiTheme.typography.body,
            color = if (text == null) TanukiTheme.colors.textFaint else MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        if (text != null) {
            Text(
                text = stringResource(Res.string.manga_info_show_more),
                style = TanukiTheme.typography.label,
                color = TanukiTheme.colors.accent,
                modifier = Modifier.padding(top = spacing.xs).clickable(onClick = onMore)
            )
        }
    }
}

/** The one tag row: a few tags and "+N"; tapping it opens About. */
@Composable
private fun TagRowPreview(
    row: TagRow,
    onClick: () -> Unit
) {
    val spacing = TanukiTheme.spacing
    Row(
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = spacing.gutter, vertical = spacing.sm)
    ) {
        row.visible.forEach { TanukiTag(text = it) }
        if (row.overflow > 0) TanukiTag(text = stringResource(Res.string.manga_tags_more, row.overflow))
    }
}

/** "You might also like": covers that open the title on AniList. */
@Composable
internal fun RecommendationsRow(
    recommendations: List<MetadataRecommendation>,
    onClick: (MetadataRecommendation) -> Unit
) {
    val spacing = TanukiTheme.spacing
    Column(Modifier.padding(vertical = spacing.sm), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Text(
            text = stringResource(Res.string.manga_metadata_recommendations),
            style = TanukiTheme.typography.headline,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = spacing.gutter)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = spacing.gutter),
            horizontalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            items(recommendations, key = { it.anilistId }) { recommendation ->
                MangaCoverTile(
                    seed = recommendation.anilistId.toString(),
                    title = recommendation.title,
                    coverUrl = recommendation.coverUrl,
                    onClick = { onClick(recommendation) },
                    modifier = Modifier.width(112.dp)
                )
            }
        }
    }
}

