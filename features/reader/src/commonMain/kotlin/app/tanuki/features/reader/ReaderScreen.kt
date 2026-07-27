package app.tanuki.features.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.ChevronRightIcon
import app.tanuki.assets.designSystem.component.EmptyLibraryIcon
import app.tanuki.assets.designSystem.component.TanukiButton
import app.tanuki.assets.designSystem.component.TanukiButtonVariant
import app.tanuki.assets.designSystem.component.TanukiEmptyState
import app.tanuki.assets.designSystem.component.TanukiIconButton
import app.tanuki.assets.designSystem.component.TanukiLoadingIndicator
import app.tanuki.core.domain.chapter.model.ChapterNumber
import app.tanuki.core.domain.manga.model.ReadingMode
import app.tanuki.core.domain.source.model.Page
import app.tanuki.core.navigation.NavEvent
import app.tanuki.features.reader.resources.Res
import app.tanuki.features.reader.resources.reader_chapter_number
import app.tanuki.features.reader.resources.reader_end_subtitle
import app.tanuki.features.reader.resources.reader_end_title
import app.tanuki.features.reader.resources.reader_error_message
import app.tanuki.features.reader.resources.reader_no_pages
import app.tanuki.features.reader.resources.reader_page_indicator
import app.tanuki.features.reader.resources.reader_retry
import app.tanuki.features.reader.resources.reader_transition_finished
import app.tanuki.features.reader.resources.reader_transition_swipe_hint
import app.tanuki.features.reader.resources.reader_transition_up_next
import app.tanuki.features.reader.viewmodel.ReaderCommand
import app.tanuki.features.reader.viewmodel.ReaderItem
import app.tanuki.features.reader.viewmodel.ReaderState
import app.tanuki.features.reader.viewmodel.ReaderViewModel
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import kotlinx.coroutines.flow.Flow
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt
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

    // The reader is an immersive surface: pages are images, shown on black regardless of app theme so
    // nothing tints the artwork. This is a deliberate reader convention, not an off-token colour.
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

@Composable
private fun ReaderError(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(TanukiTheme.spacing.gutter),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.lg)
    ) {
        Text(
            text = stringResource(Res.string.reader_error_message),
            style = TanukiTheme.typography.body,
            color = Color.White,
            textAlign = TextAlign.Center
        )
        TanukiButton(
            text = stringResource(Res.string.reader_retry),
            onClick = onRetry,
            variant = TanukiButtonVariant.Filled
        )
    }
}

/**
 * The reader surface. It renders [ReaderState.Content.items] — the concatenated pages of the loaded
 * chapters with transition slides between them — in one pager or long-strip, and wires the two halves of
 * the continuous-reading contract:
 *
 * - **report:** the settled item index is pushed to [onItemFocused]; the ViewModel derives the chapter,
 *   persists progress, and preloads the next chapter as boundaries approach.
 * - **command:** the ViewModel drives the viewport back via [commands] — a [ReaderCommand.Reanchor] to
 *   hold position when the loaded range grows above the current page (paged layouts only; the strip
 *   anchors by item key), and a [ReaderCommand.Jump] for the scrubber and prev/next-chapter buttons.
 */
@Composable
private fun ReaderContent(
    content: ReaderState.Content,
    commands: Flow<ReaderCommand>,
    onItemFocused: (Int) -> Unit,
    onToggleChrome: () -> Unit,
    onBack: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onScrubToPage: (Int) -> Unit
) {
    if (content.items.isEmpty()) {
        TanukiEmptyState(
            message = stringResource(Res.string.reader_no_pages),
            modifier = Modifier.fillMaxSize().padding(TanukiTheme.spacing.gutter)
        )
        return
    }

    val paged = content.readingMode != ReadingMode.WEBTOON && content.readingMode != ReadingMode.CONTINUOUS_VERTICAL

    Box(Modifier.fillMaxSize()) {
        if (paged) {
            val pagerState = rememberPagerState(initialPage = content.initialItemIndex) { content.items.size }
            LaunchedEffect(pagerState) {
                snapshotFlow { pagerState.currentPage }.collect(onItemFocused)
            }
            // A pager doesn't anchor by key, so it honours both re-anchors and explicit jumps.
            LaunchedEffect(pagerState, commands) {
                commands.collect { command -> pagerState.scrollToPage(command.index) }
            }
            PagePrefetch(content.items, pagerState.currentPage)
            PagedReader(
                items = content.items,
                pagerState = pagerState,
                vertical = content.readingMode == ReadingMode.VERTICAL,
                reverse = content.readingMode == ReadingMode.RIGHT_TO_LEFT,
                onToggleChrome = onToggleChrome
            )
        } else {
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = content.initialItemIndex)
            LaunchedEffect(listState) {
                snapshotFlow { listState.firstVisibleItemIndex }.collect(onItemFocused)
            }
            // The strip keeps its visible item anchored by key when the list grows above it, so a
            // re-anchor is a no-op here; only explicit jumps need to be applied.
            LaunchedEffect(listState, commands) {
                commands.collect { command ->
                    if (command is ReaderCommand.Jump) listState.scrollToItem(command.index)
                }
            }
            PagePrefetch(content.items, listState.firstVisibleItemIndex)
            WebtoonReader(
                items = content.items,
                listState = listState,
                onToggleChrome = onToggleChrome
            )
        }

        // Ambient orientation while immersed: a progress hairline and a brief chapter-change pill, both
        // shown only when the chrome is hidden (the chrome carries the same information when open).
        ChapterProgressBar(
            currentPage = content.currentPage,
            pageCount = content.currentPageCount,
            reversed = content.readingMode == ReadingMode.RIGHT_TO_LEFT,
            visible = !content.chromeVisible
        )
        ChapterChangeIndicator(
            chapterId = content.currentChapterId,
            title = chapterTitle(content.chapterNumber, content.chapterName),
            chromeVisible = content.chromeVisible
        )

        ReaderChrome(
            content = content,
            onBack = onBack,
            onPreviousChapter = onPreviousChapter,
            onNextChapter = onNextChapter,
            onScrubToPage = onScrubToPage
        )
    }
}

/** Paged layouts (L→R, R→L, one-page vertical): one item per swipe, snapping to bounds. Each item is a
 *  full-screen page or a full-screen transition slide, so crossing a chapter boundary is one more swipe. */
@Composable
private fun PagedReader(
    items: List<ReaderItem>,
    pagerState: PagerState,
    vertical: Boolean,
    reverse: Boolean,
    onToggleChrome: () -> Unit
) {
    val slot: @Composable (Int) -> Unit = { index ->
        ReaderItemContent(
            item = items[index],
            onToggleChrome = onToggleChrome,
            transitionModifier = Modifier.fillMaxSize()
        )
    }
    if (vertical) {
        VerticalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { index -> slot(index) }
    } else {
        HorizontalPager(
            state = pagerState,
            reverseLayout = reverse,
            modifier = Modifier.fillMaxSize()
        ) { index -> slot(index) }
    }
}

/** Long-strip layout (webtoons): items stacked in a continuous scroll, no per-item snapping. Transition
 *  slides fill the viewport, so scrolling past a chapter's last page lands on a full "up next" panel and
 *  scrolling on flows into the following chapter. */
@Composable
private fun WebtoonReader(
    items: List<ReaderItem>,
    listState: LazyListState,
    onToggleChrome: () -> Unit
) {
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(items, key = { it.key }) { item ->
            ReaderItemContent(
                item = item,
                onToggleChrome = onToggleChrome,
                fillHeight = false,
                transitionModifier = Modifier.fillParentMaxHeight().fillMaxWidth()
            )
        }
    }
}

/** Renders one [ReaderItem]: a chapter page image, or a chapter transition slide. The [transitionModifier]
 *  sizes the slide to its host (full screen in a pager, full viewport in the strip). */
@Composable
private fun ReaderItemContent(
    item: ReaderItem,
    onToggleChrome: () -> Unit,
    fillHeight: Boolean = true,
    transitionModifier: Modifier = Modifier
) {
    when (item) {
        is ReaderItem.PageItem -> ReaderPage(item.page, onToggleChrome, fillHeight)
        is ReaderItem.Transition -> ChapterTransition(item, onToggleChrome, transitionModifier)
        ReaderItem.End -> EndOfSeries(onToggleChrome, transitionModifier)
    }
}

/**
 * A single page's image. Fetched through the reader's dedicated page [LocalPageImageLoader] (not the
 * singleton cover loader) from the page's direct [Page.imageUrl], falling back to its [Page.url] for
 * sources that address pages by URL. Tapping anywhere toggles the reader chrome — no ripple, since the
 * whole surface is the target.
 */
@Composable
private fun ReaderPage(
    page: Page,
    onToggleChrome: () -> Unit,
    fillHeight: Boolean = true
) {
    val base =
        Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggleChrome
            )
    val context = LocalPlatformContext.current
    // An ImageRequest (not a bare URL) so the source's per-page headers ride along — e.g. a Referer a
    // hotlink-guarded host requires. The fetch runs on the Cloudflare-carrying page loader.
    val request = remember(context, page) { page.toImageRequest(context) }
    AsyncImage(
        model = request,
        contentDescription = null,
        imageLoader = LocalPageImageLoader.current,
        contentScale = if (fillHeight) ContentScale.Fit else ContentScale.FillWidth,
        modifier = if (fillHeight) base.fillMaxSize() else base
    )
}

/**
 * Builds the [ImageRequest] for a [Page], attaching its source-provided [Page.headers] (empty for most
 * sources). Shared by [ReaderPage] and [PagePrefetch] so a prefetched image is byte-for-byte the request
 * the page later renders — same URL *and* same headers, so both hit the same cache entry.
 */
private fun Page.toImageRequest(context: coil3.PlatformContext): ImageRequest =
    ImageRequest
        .Builder(context)
        .data(imageUrl?.value ?: url?.value)
        .httpHeaders(
            if (headers.isEmpty()) {
                NetworkHeaders.EMPTY
            } else {
                NetworkHeaders.Builder().apply { headers.forEach { (name, value) -> add(name, value) } }.build()
            }
        ).build()

/**
 * Enqueues the next [PAGE_PREFETCH_AHEAD] page images after the focused item into the page cache, so
 * paging forward hits a warm cache. Walks past transition slides and across chapter boundaries, so the
 * first pages of the next chapter warm up before the reader reaches them. Fire-and-forget: a prefetch
 * that doesn't finish before the user arrives simply becomes a normal load.
 */
@Composable
private fun PagePrefetch(
    items: List<ReaderItem>,
    focusedIndex: Int
) {
    val imageLoader = LocalPageImageLoader.current
    val context = LocalPlatformContext.current
    LaunchedEffect(focusedIndex, items) {
        var warmed = 0
        var index = focusedIndex + 1
        while (index < items.size && warmed < PAGE_PREFETCH_AHEAD) {
            val item = items[index]
            if (item is ReaderItem.PageItem && (item.page.imageUrl != null || item.page.url != null)) {
                imageLoader.enqueue(item.page.toImageRequest(context))
                warmed++
            }
            index++
        }
    }
}

/** How many page images ahead of the current one to warm into the cache. */
private const val PAGE_PREFETCH_AHEAD = 3

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

/** Immersive top/bottom chrome, shown when [ReaderState.Content.chromeVisible] and hidden on tap. */
@Composable
private fun BoxScope.ReaderChrome(
    content: ReaderState.Content,
    onBack: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onScrubToPage: (Int) -> Unit
) {
    val scrim = TanukiTheme.colors.scrimSoft

    // Top bar: back + current chapter title.
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

/**
 * The bottom-chrome page control: a page count over a `[prev chapter] — slider — [next chapter]` row. The
 * page count and slider track the chapter the visible page belongs to, so they update as the reader flows
 * across boundaries. The prev/next buttons jump to the start of the adjacent chapter (loading it first if
 * needed).
 *
 * Dragging the slider jumps to any page. While dragging it follows the finger and the label previews the
 * target page ([scrubValue]); the actual scroll fires once on release, so the pager/list isn't churned
 * every frame. The slider is continuous (no tick marks — a long chapter would be a picket fence) and its
 * value is rounded to a page. In right-to-left mode it is mirrored (page 1 on the right, filling leftward)
 * so its direction matches the reading direction.
 */
@Composable
private fun ColumnScope.PageScrubber(
    currentPage: Int,
    pageCount: Int,
    reversed: Boolean,
    hasPreviousChapter: Boolean,
    hasNextChapter: Boolean,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onScrubToPage: (Int) -> Unit
) {
    // Non-null only while a drag is in progress; drives both the slider position and the label preview.
    var scrubValue by remember { mutableStateOf<Float?>(null) }
    val displayPage = (scrubValue?.roundToInt() ?: currentPage).coerceIn(0, (pageCount - 1).coerceAtLeast(0))

    Text(
        text = stringResource(Res.string.reader_page_indicator, displayPage + 1, pageCount),
        style = TanukiTheme.typography.caption,
        color = Color.White,
        modifier = Modifier.align(Alignment.CenterHorizontally)
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.xs)
    ) {
        TanukiIconButton(
            onClick = onPreviousChapter,
            icon = { tint ->
                ChevronRightIcon(
                    tint = if (hasPreviousChapter) tint else TanukiTheme.colors.textFaint,
                    modifier = Modifier.rotate(180f)
                )
            }
        )
        // A slider needs at least two pages to have anywhere to travel; a one-page chapter just spaces.
        if (pageCount > 1) {
            Box(Modifier.weight(1f)) {
                val slider: @Composable () -> Unit = {
                    Slider(
                        value = (scrubValue ?: currentPage.toFloat()).coerceIn(0f, (pageCount - 1).toFloat()),
                        onValueChange = { scrubValue = it },
                        onValueChangeFinished = {
                            scrubValue?.let { onScrubToPage(it.roundToInt().coerceIn(0, pageCount - 1)) }
                            scrubValue = null
                        },
                        valueRange = 0f..(pageCount - 1).toFloat(),
                        colors =
                            SliderDefaults.colors(
                                thumbColor = TanukiTheme.colors.accent,
                                activeTrackColor = TanukiTheme.colors.accent,
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                            ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (reversed) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { slider() }
                } else {
                    slider()
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
        TanukiIconButton(
            onClick = onNextChapter,
            icon = { tint ->
                ChevronRightIcon(tint = if (hasNextChapter) tint else TanukiTheme.colors.textFaint)
            }
        )
    }
}

/** The current chapter's display title: its source name, or the localized "Chapter N" fallback. */
@Composable
private fun chapterTitle(
    number: ChapterNumber?,
    name: String?
): String = name ?: stringResource(Res.string.reader_chapter_number, number?.let(::formatChapterNumber) ?: "")

/** Formats a chapter number without a trailing `.0` — `4.0` → "4", `4.5` → "4.5". */
private fun formatChapterNumber(number: ChapterNumber): String {
    val value = number.value
    return if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
}