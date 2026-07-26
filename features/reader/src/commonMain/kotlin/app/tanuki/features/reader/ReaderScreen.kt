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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.ChevronRightIcon
import app.tanuki.assets.designSystem.component.TanukiButton
import app.tanuki.assets.designSystem.component.TanukiButtonVariant
import app.tanuki.assets.designSystem.component.TanukiEmptyState
import app.tanuki.assets.designSystem.component.TanukiIconButton
import app.tanuki.assets.designSystem.component.TanukiLoadingIndicator
import app.tanuki.core.domain.chapter.model.ChapterNumber
import app.tanuki.core.domain.manga.model.ReadingMode
import app.tanuki.core.domain.source.model.Page
import app.tanuki.core.navigation.Destination
import app.tanuki.core.navigation.NavEvent
import app.tanuki.features.reader.resources.Res
import app.tanuki.features.reader.resources.reader_chapter_number
import app.tanuki.features.reader.resources.reader_error_message
import app.tanuki.features.reader.resources.reader_no_pages
import app.tanuki.features.reader.resources.reader_page_indicator
import app.tanuki.features.reader.resources.reader_retry
import app.tanuki.features.reader.viewmodel.ReaderState
import app.tanuki.features.reader.viewmodel.ReaderViewModel
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt
import kotlin.uuid.Uuid

/**
 * Stateful entry point for the reader. Resolves the [ReaderViewModel] from Metro — assisted with the
 * [mangaId] and [chapterId] the route carried — collects its state, and forwards its screen-scoped
 * [NavEvent]s: a [NavEvent.NavTo] (page into an adjacent chapter) to [onNavigate], a [NavEvent.NavBack]
 * to [onBack]. The only place the feature touches navigation mechanics; the composables below are pure UI.
 *
 * @param mangaId the series being read — so the reader can page across sibling chapters.
 * @param chapterId the chapter to open — decoded from the route by the host `NavHost`.
 * @param onNavigate invoked with the [Destination] a [NavEvent.NavTo] targets (the adjacent chapter).
 * @param onBack invoked when navigation should pop back to the previous screen.
 */
@Composable
fun ReaderRoute(
    mangaId: Uuid,
    chapterId: Uuid,
    onNavigate: (Destination) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel =
        assistedMetroViewModel<ReaderViewModel, ReaderViewModel.Factory> { create(mangaId, chapterId) }

    LaunchedEffect(viewModel) {
        viewModel.navEvents.collect { event ->
            when (event) {
                is NavEvent.NavTo -> onNavigate(event.destination)
                NavEvent.NavBack -> onBack()
            }
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
            ReaderState.Loading ->
                TanukiLoadingIndicator(Modifier.align(Alignment.Center))

            ReaderState.Error ->
                ReaderError(onRetry = viewModel::onRetry, modifier = Modifier.align(Alignment.Center))

            is ReaderState.Content ->
                ReaderContent(
                    content = current,
                    onPageChanged = viewModel::onPageChanged,
                    onToggleChrome = viewModel::onToggleChrome,
                    onBack = viewModel::onBack,
                    onPreviousChapter = viewModel::onPreviousChapter,
                    onNextChapter = viewModel::onNextChapter
                )
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

@Composable
private fun ReaderContent(
    content: ReaderState.Content,
    onPageChanged: (Int) -> Unit,
    onToggleChrome: () -> Unit,
    onBack: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit
) {
    if (content.pages.isEmpty()) {
        TanukiEmptyState(
            message = stringResource(Res.string.reader_no_pages),
            modifier = Modifier.fillMaxSize().padding(TanukiTheme.spacing.gutter)
        )
        return
    }

    // Warm the next few pages into the page cache so a swipe shows an already-decoded image instead of
    // a spinner. Keyed on the current page, so prefetch advances as the user reads.
    PagePrefetch(content.pages, content.currentPage)

    // The scroll state is hoisted here (not owned inside each layout) so the chrome's scrubber can
    // command it: the layout reflects the state, and `onScrubToPage` drives it from the slider.
    val scope = rememberCoroutineScope()
    val paged = content.readingMode != ReadingMode.WEBTOON && content.readingMode != ReadingMode.CONTINUOUS_VERTICAL

    Box(Modifier.fillMaxSize()) {
        val onScrubToPage: (Int) -> Unit
        if (paged) {
            val pagerState = rememberPagerState(initialPage = content.currentPage) { content.pages.size }
            LaunchedEffect(pagerState) { snapshotFlow { pagerState.currentPage }.collect(onPageChanged) }
            PagedReader(
                pages = content.pages,
                pagerState = pagerState,
                vertical = content.readingMode == ReadingMode.VERTICAL,
                reverse = content.readingMode == ReadingMode.RIGHT_TO_LEFT,
                onToggleChrome = onToggleChrome
            )
            onScrubToPage = { page -> scope.launch { pagerState.scrollToPage(page) } }
        } else {
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = content.currentPage)
            LaunchedEffect(listState) { snapshotFlow { listState.firstVisibleItemIndex }.collect(onPageChanged) }
            WebtoonReader(pages = content.pages, listState = listState, onToggleChrome = onToggleChrome)
            onScrubToPage = { page -> scope.launch { listState.scrollToItem(page) } }
        }

        ReaderChrome(
            content = content,
            onBack = onBack,
            onPreviousChapter = onPreviousChapter,
            onNextChapter = onNextChapter,
            onScrubToPage = onScrubToPage
        )
    }
}

/** Paged layouts (L→R, R→L, one-page vertical): one page per swipe, snapping to page bounds. The
 *  [pagerState] is owned by [ReaderContent] so the chrome's scrubber can drive it. */
@Composable
private fun PagedReader(
    pages: List<Page>,
    pagerState: PagerState,
    vertical: Boolean,
    reverse: Boolean,
    onToggleChrome: () -> Unit
) {
    if (vertical) {
        VerticalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            ReaderPage(pages[page], onToggleChrome)
        }
    } else {
        HorizontalPager(
            state = pagerState,
            reverseLayout = reverse,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            ReaderPage(pages[page], onToggleChrome)
        }
    }
}

/** Long-strip layout (webtoons): pages stacked in a continuous scroll, no per-page snapping. The
 *  [listState] is owned by [ReaderContent] so the chrome's scrubber can drive it. */
@Composable
private fun WebtoonReader(
    pages: List<Page>,
    listState: LazyListState,
    onToggleChrome: () -> Unit
) {
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(pages, key = { it.index.toLong() }) { page ->
            ReaderPage(page, onToggleChrome, fillHeight = false)
        }
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
    AsyncImage(
        model = page.imageUrl?.value ?: page.url?.value,
        contentDescription = null,
        imageLoader = LocalPageImageLoader.current,
        contentScale = if (fillHeight) ContentScale.Fit else ContentScale.FillWidth,
        modifier = if (fillHeight) base.fillMaxSize() else base
    )
}

/**
 * Enqueues the next [PAGE_PREFETCH_AHEAD] pages after [currentPage] into the page cache with no target,
 * so paging forward hits a warm cache. Uses the same [LocalPageImageLoader] the pages render through,
 * so a prefetched image is the exact one [ReaderPage] later requests. Fire-and-forget: a prefetch that
 * doesn't finish before the user arrives simply becomes a normal load.
 */
@Composable
private fun PagePrefetch(
    pages: List<Page>,
    currentPage: Int
) {
    val imageLoader = LocalPageImageLoader.current
    val context = LocalPlatformContext.current
    LaunchedEffect(currentPage, pages) {
        val start = currentPage + 1
        val end = minOf(start + PAGE_PREFETCH_AHEAD, pages.size)
        for (index in start until end) {
            val url = pages[index].imageUrl?.value ?: pages[index].url?.value ?: continue
            imageLoader.enqueue(ImageRequest.Builder(context).data(url).build())
        }
    }
}

/** How many pages ahead of the current one to warm into the cache. */
private const val PAGE_PREFETCH_AHEAD = 3

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
                pageCount = content.pages.size,
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
 * The bottom-chrome page control: a page count over a `[prev chapter] — slider — [next chapter]` row.
 *
 * Dragging the slider jumps to any page. While dragging it follows the finger and the label previews the
 * target page ([scrubValue]); the actual scroll fires once on release, so the pager/list isn't churned
 * every frame. The slider is continuous (no tick marks — a long chapter would be a picket fence) and
 * its value is rounded to a page. In right-to-left mode it is mirrored (page 1 on the right, filling
 * leftward) so its direction matches the reading direction.
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
): String =
    name ?: stringResource(Res.string.reader_chapter_number, number?.let(::formatChapterNumber) ?: "")

/** Formats a chapter number without a trailing `.0` — `4.0` → "4", `4.5` → "4.5". */
private fun formatChapterNumber(number: ChapterNumber): String {
    val value = number.value
    return if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
}