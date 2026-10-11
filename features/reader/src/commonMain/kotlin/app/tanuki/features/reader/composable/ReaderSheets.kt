package app.tanuki.features.reader.composable

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tanuki.assets.designSystem.ReaderColors
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.MangaCover
import app.tanuki.assets.designSystem.component.Segment
import app.tanuki.assets.designSystem.component.TanukiChoiceRow
import app.tanuki.assets.designSystem.component.TanukiEmptyState
import app.tanuki.assets.designSystem.component.TanukiSegmentedControl
import app.tanuki.core.domain.manga.model.ReadingMode
import app.tanuki.core.domain.settings.model.ReaderBackground
import app.tanuki.features.reader.LocalPageImageLoader
import app.tanuki.features.reader.composable.components.chapterTitle
import app.tanuki.features.reader.resources.Res
import app.tanuki.features.reader.resources.reader_background_black
import app.tanuki.features.reader.resources.reader_background_paper
import app.tanuki.features.reader.resources.reader_background_sepia
import app.tanuki.features.reader.resources.reader_background_title
import app.tanuki.features.reader.resources.reader_bookmark_page
import app.tanuki.features.reader.resources.reader_bookmarks_empty
import app.tanuki.features.reader.resources.reader_chapter_bookmarks
import app.tanuki.features.reader.resources.reader_chapter_current
import app.tanuki.features.reader.resources.reader_chapter_downloaded
import app.tanuki.features.reader.resources.reader_chapter_read
import app.tanuki.features.reader.resources.reader_mode_hint
import app.tanuki.features.reader.resources.reader_mode_ltr
import app.tanuki.features.reader.resources.reader_mode_rtl
import app.tanuki.features.reader.resources.reader_mode_title
import app.tanuki.features.reader.resources.reader_mode_vertical
import app.tanuki.features.reader.resources.reader_mode_webtoon
import app.tanuki.features.reader.resources.reader_tab_bookmarks
import app.tanuki.features.reader.resources.reader_tab_chapters
import app.tanuki.features.reader.utils.pageImageRequest
import app.tanuki.features.reader.viewmodel.ReaderBookmarkItem
import app.tanuki.features.reader.viewmodel.ReaderChapterItem
import app.tanuki.features.reader.viewmodel.ReaderChaptersTab
import app.tanuki.features.reader.viewmodel.ReaderSheet
import app.tanuki.features.reader.viewmodel.ReaderState
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.size.Size
import kotlin.uuid.Uuid
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Bookmark thumbnails: small, so decoded small. */
private val ThumbnailWidth = 40.dp
private const val THUMBNAIL_DECODE_PX = 160

/** A read chapter steps back without disappearing, like Manga detail's rows. */
private const val READ_ALPHA = 0.6f

/** The sheets the reader's chrome opens: the chapter list and bookmarks, and the reader settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderSheets(
    content: ReaderState.Content,
    onDismiss: () -> Unit,
    onChaptersTabChange: (ReaderChaptersTab) -> Unit,
    onChapterSelected: (Uuid) -> Unit,
    onBookmarkSelected: (Uuid) -> Unit,
    onReadingModeChange: (ReadingMode) -> Unit,
    onBackgroundChange: (ReaderBackground) -> Unit
) {
    val sheet = content.sheet ?: return
    ModalBottomSheet(onDismissRequest = onDismiss) {
        when (sheet) {
            ReaderSheet.Chapters ->
                ChaptersSheet(content, onChaptersTabChange, onChapterSelected, onBookmarkSelected)

            ReaderSheet.Settings ->
                SettingsSheet(content.readingMode, content.background, onReadingModeChange, onBackgroundChange)
        }
    }
}

@Composable
private fun ChaptersSheet(
    content: ReaderState.Content,
    onTabChange: (ReaderChaptersTab) -> Unit,
    onChapterSelected: (Uuid) -> Unit,
    onBookmarkSelected: (Uuid) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        TanukiSegmentedControl(
            segments =
                listOf(
                    Segment(ReaderChaptersTab.Chapters, stringResource(Res.string.reader_tab_chapters)),
                    Segment(ReaderChaptersTab.Bookmarks, stringResource(Res.string.reader_tab_bookmarks))
                ),
            selected = content.chaptersTab,
            onSelect = onTabChange,
            modifier = Modifier.padding(horizontal = TanukiTheme.spacing.gutter).padding(bottom = TanukiTheme.spacing.sm)
        )
        when (content.chaptersTab) {
            ReaderChaptersTab.Chapters -> ChapterList(content.chapters, onChapterSelected)
            ReaderChaptersTab.Bookmarks -> BookmarkList(content.bookmarks, onBookmarkSelected)
        }
    }
}

@Composable
private fun ChapterList(
    chapters: List<ReaderChapterItem>,
    onSelected: (Uuid) -> Unit
) {
    val listState = rememberLazyListState()
    // Opened scrolled to the chapter being read, so "the next one" is in reach without hunting.
    LaunchedEffect(chapters.isNotEmpty()) {
        val current = chapters.indexOfFirst { it.isCurrent }
        if (current > 0) listState.scrollToItem((current - 1).coerceAtLeast(0))
    }
    LazyColumn(state = listState) {
        items(chapters, key = { it.id.toString() }) { chapter ->
            ChapterRow(chapter, onClick = { onSelected(chapter.id) })
        }
    }
}

@Composable
private fun ChapterRow(
    chapter: ReaderChapterItem,
    onClick: () -> Unit
) {
    val read = stringResource(Res.string.reader_chapter_read)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .background(if (chapter.isCurrent) TanukiTheme.colors.accentTint else Color.Transparent)
                .padding(horizontal = TanukiTheme.spacing.gutter, vertical = TanukiTheme.spacing.md)
                .semantics { if (chapter.isRead) contentDescription = read },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.sm)
    ) {
        Column(Modifier.weight(1f).alpha(if (chapter.isRead && !chapter.isCurrent) READ_ALPHA else 1f)) {
            Text(
                text = chapterTitle(chapter.numberLabel, chapter.name),
                style = TanukiTheme.typography.body,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val meta = listOfNotNull(
                chapter.scanlator,
                stringResource(Res.string.reader_chapter_current).takeIf { chapter.isCurrent }
            )
            if (meta.isNotEmpty()) {
                Text(
                    text = meta.joinToString(" · "),
                    style = TanukiTheme.typography.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
        if (chapter.bookmarkCount > 0) {
            val description = pluralStringResource(Res.plurals.reader_chapter_bookmarks, chapter.bookmarkCount, chapter.bookmarkCount)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.semantics { contentDescription = description }
            ) {
                Icon(Icons.Rounded.Bookmark, contentDescription = null, tint = TanukiTheme.colors.accent, modifier = Modifier.size(16.dp))
                Text(
                    text = chapter.bookmarkCount.toString(),
                    style = TanukiTheme.typography.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (chapter.isDownloaded) {
            Icon(
                Icons.Rounded.DownloadDone,
                contentDescription = stringResource(Res.string.reader_chapter_downloaded),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun BookmarkList(
    bookmarks: List<ReaderBookmarkItem>,
    onSelected: (Uuid) -> Unit
) {
    if (bookmarks.isEmpty()) {
        TanukiEmptyState(
            message = stringResource(Res.string.reader_bookmarks_empty),
            modifier = Modifier.fillMaxWidth().padding(TanukiTheme.spacing.gutter)
        )
        return
    }
    LazyColumn {
        items(bookmarks, key = { it.id.toString() }) { bookmark ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSelected(bookmark.id) }
                        .padding(horizontal = TanukiTheme.spacing.gutter, vertical = TanukiTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.md)
            ) {
                BookmarkThumbnail(bookmark)
                Column(Modifier.weight(1f)) {
                    Text(
                        text = chapterTitle(bookmark.chapterNumberLabel, bookmark.chapterName),
                        style = TanukiTheme.typography.body,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(Res.string.reader_bookmark_page, bookmark.page + 1),
                        style = TanukiTheme.typography.caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * The bookmarked page, small, through the reader's page loader (Cloudflare-aware, page cache). A source
 * URL may have expired since the bookmark was made; the seeded ground then stays.
 */
@Composable
private fun BookmarkThumbnail(bookmark: ReaderBookmarkItem) {
    val context = LocalPlatformContext.current
    val request =
        remember(bookmark.id) {
            pageImageRequest(
                context,
                bookmark.imageUrl,
                bookmark.imageHeaders,
                bookmark.localPath,
                Size(THUMBNAIL_DECODE_PX, THUMBNAIL_DECODE_PX * 2)
            )
        }
    MangaCover(seed = bookmark.chapterId.toString(), modifier = Modifier.width(ThumbnailWidth)) {
        if (bookmark.imageUrl != null || bookmark.localPath != null) {
            AsyncImage(model = request, contentDescription = null, imageLoader = LocalPageImageLoader.current)
        }
    }
}

@Composable
private fun SettingsSheet(
    readingMode: ReadingMode,
    background: ReaderBackground,
    onReadingModeChange: (ReadingMode) -> Unit,
    onBackgroundChange: (ReaderBackground) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(bottom = TanukiTheme.spacing.xxl)) {
        SheetHeader(stringResource(Res.string.reader_mode_title))
        listOf(
            ReadingMode.LEFT_TO_RIGHT to Res.string.reader_mode_ltr,
            ReadingMode.RIGHT_TO_LEFT to Res.string.reader_mode_rtl,
            ReadingMode.VERTICAL to Res.string.reader_mode_vertical,
            ReadingMode.WEBTOON to Res.string.reader_mode_webtoon
        ).forEach { (mode, label) ->
            TanukiChoiceRow(
                title = stringResource(label),
                selected = mode == readingMode,
                onClick = { onReadingModeChange(mode) }
            )
        }
        Text(
            text = stringResource(Res.string.reader_mode_hint),
            style = TanukiTheme.typography.caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = TanukiTheme.spacing.gutter)
        )

        SheetHeader(stringResource(Res.string.reader_background_title))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = TanukiTheme.spacing.gutter),
            horizontalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.lg)
        ) {
            BackgroundSwatch(ReaderColors.Black, Color.White, stringResource(Res.string.reader_background_black), background == ReaderBackground.Black) {
                onBackgroundChange(ReaderBackground.Black)
            }
            BackgroundSwatch(ReaderColors.Paper, Color.Black, stringResource(Res.string.reader_background_paper), background == ReaderBackground.Paper) {
                onBackgroundChange(ReaderBackground.Paper)
            }
            BackgroundSwatch(ReaderColors.Sepia, ReaderColors.SepiaInk, stringResource(Res.string.reader_background_sepia), background == ReaderBackground.Sepia) {
                onBackgroundChange(ReaderBackground.Sepia)
            }
        }
    }
}

@Composable
private fun SheetHeader(text: String) {
    Text(
        text = text,
        style = TanukiTheme.typography.headline,
        color = MaterialTheme.colorScheme.onSurface,
        modifier =
            Modifier.padding(
                start = TanukiTheme.spacing.gutter,
                end = TanukiTheme.spacing.gutter,
                top = TanukiTheme.spacing.lg,
                bottom = TanukiTheme.spacing.xs
            )
    )
}

/** A round swatch of the ground, its name under it; the picked one ringed in accent with a check. */
@Composable
private fun BackgroundSwatch(
    ground: Color,
    ink: Color,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick).padding(TanukiTheme.spacing.xs)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(48.dp)
                    .border(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (selected) TanukiTheme.colors.accent else MaterialTheme.colorScheme.outlineVariant,
                        shape = CircleShape
                    ).padding(3.dp)
                    .background(ground, CircleShape)
        ) {
            if (selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = ink)
        }
        Text(
            text = label,
            style = TanukiTheme.typography.caption,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = TanukiTheme.spacing.xs)
        )
    }
}
