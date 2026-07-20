package app.tanuki.features.library.composable.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import app.tanuki.assets.designSystem.TanukiRadii
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.MangaCover
import app.tanuki.core.domain.common.model.Url
import app.tanuki.core.domain.common.service.uuidV7
import app.tanuki.core.domain.manga.model.Manga
import app.tanuki.core.domain.manga.model.PublishStatus.PUBLISHING_FINISHED
import app.tanuki.core.domain.manga.model.ReadingMode.RIGHT_TO_LEFT
import app.tanuki.core.domain.manga.model.ReadingStatus.READING
import kotlin.time.Clock

@Composable
fun LibraryItem(
    manga: Manga,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = TanukiTheme.spacing
    Box(
        modifier = modifier.clickable(onClick = onClick),
        contentAlignment = Alignment.BottomStart
    ) {
        MangaCover(
            seed = manga.id.toString(),
            modifier = Modifier.fillMaxWidth(),
            downloaded = manga.isDownloaded,
            unread = manga.unreadCount
        )
        // Bottom protection gradient so the title stays legible over any cover art (per the design
        // system's MangaCover). Clipped to the cover's rounded corners to sit flush inside the frame.
        Box(
            modifier =
                Modifier
                    .matchParentSize()
                    .clip(TanukiRadii.coverShape)
                    .background(COVER_TITLE_SCRIM)
        )
        Text(
            text = manga.title.value,
            style = TanukiTheme.typography.caption.copy(fontWeight = FontWeight.Bold),
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.sm, vertical = spacing.xs)
        )
    }
}

/** Transparent → dark wash over the lower cover, protecting the overlaid title from bright art. */
private val COVER_TITLE_SCRIM =
    Brush.verticalGradient(
        0.45f to Color.Transparent,
        1f to Color.Black.copy(alpha = 0.78f)
    )

private val manga = Manga(
    id = uuidV7(),
    source = uuidV7(),
    title = app.tanuki.core.domain.common.model.Text("Manga"),
    author = null,
    description = null,
    cover =
        app.tanuki.core.domain.manga.model.MangaCover(
            mangaId = uuidV7(),
            url = null,
            lastModified = Clock.System.now()
        ),
    url = Url("https://example.com/manga"),
    isDownloaded = true,
    unreadCount = 1u,
    dateAdded = null,
    lastRead = null,
    readingMode = RIGHT_TO_LEFT,
    publishStatus = PUBLISHING_FINISHED,
    readingStatus = READING
)

@Composable
@Preview(showBackground = true)
private fun LibraryItemPreview() {
    LibraryItem(
        manga = manga,
        onClick = {},
    )
}