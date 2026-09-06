package app.tanuki.features.downloads.composable.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.MangaCoverTile
import app.tanuki.assets.designSystem.component.TanukiIconButton
import app.tanuki.assets.designSystem.component.TrashIcon
import app.tanuki.core.domain.download.model.DownloadQueueSeries
import app.tanuki.features.downloads.resources.Res
import app.tanuki.features.downloads.resources.downloads_cancel_series
import app.tanuki.features.downloads.resources.downloads_cover_content_description
import app.tanuki.features.downloads.resources.downloads_series_count
import app.tanuki.features.downloads.resources.downloads_series_failed
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Cover width in the header — the compact size the history rows use, not the library grid's. */
private val CoverWidth = 40.dp

/** Matches [app.tanuki.assets.designSystem.component.TanukiListRow]'s own floor, so the rhythm holds. */
private val HeaderMinHeight = 56.dp

/**
 * The header above one series' queued chapters: its cover and title, how many chapters are under it, and
 * a control that clears the whole group.
 *
 * ### Why this is not a `TanukiListRow`
 *
 * It has that component's exact anatomy, and the chapters below it *are* `TanukiListRow`s — which is the
 * problem. A list row fixes its title to the `body` style by design, so a header built from one would
 * carry the same weight as the three rows it heads, and the group would read as four peers instead of a
 * heading over three. Hierarchy here comes from type weight (`headline`) and the cover, since the queue
 * is a flat lazy list and cannot use the recessed `TanukiSectionGroup` panel that gives the Settings
 * index its grouping without collapsing every row into one un-recycled item. Everything else is borrowed:
 * the same 56dp floor, the same spacing tokens, the same cover and icon-button components.
 *
 * The count line names the failures instead when there are any, because a group of twelve with three
 * failures otherwise needs the user to scroll to discover them — and the failures are the only rows on
 * this screen that need a decision.
 *
 * Tapping the header opens the series rather than collapsing the group. Collapsing is what a header of
 * this shape usually does and is the wrong verb here: groups are a handful of rows, the queue is
 * transient, and the question the screen actually raises — "what is this, and why is it downloading?" —
 * is answered by the series page.
 *
 * @param series the group being headed.
 * @param onOpen open this series' detail screen.
 * @param onCancel take every chapter of this series out of the queue.
 */
@Composable
internal fun DownloadSeriesHeader(
    series: DownloadQueueSeries,
    onOpen: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val manga = series.manga
    val coverDescription = stringResource(Res.string.downloads_cover_content_description, manga.title.value)
    val cancelDescription = stringResource(Res.string.downloads_cancel_series)
    val failed = series.failedCount

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = HeaderMinHeight)
                .clickable(onClick = onOpen)
                .padding(horizontal = TanukiTheme.spacing.lg, vertical = TanukiTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MangaCoverTile(
            seed = manga.id.toString(),
            title = "",
            coverUrl = manga.cover.url?.value,
            coverHeaders = manga.cover.headers,
            // The same action as the row it sits in, deliberately: a nested target that did something
            // *different* would be a gesture conflict, but one that agrees is just a larger target.
            onClick = onOpen,
            modifier =
                Modifier
                    .width(CoverWidth)
                    .semantics { contentDescription = coverDescription }
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.xxs)
        ) {
            Text(
                // Content from the source, never localized.
                text = manga.title.value,
                style = TanukiTheme.typography.headline,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text =
                    if (failed > 0) {
                        pluralStringResource(Res.plurals.downloads_series_failed, failed, failed)
                    } else {
                        pluralStringResource(
                            Res.plurals.downloads_series_count,
                            series.chapters.size,
                            series.chapters.size
                        )
                    },
                style = TanukiTheme.typography.caption,
                // `onSurfaceVariant` rather than `textFaint`, which is 2.5:1 against the light dialect's
                // paper canvas — below the floor for even secondary text. This is also the token
                // `TanukiListRow` gives its own subtitles, so the header's second line and the rows'
                // second lines are the same tier.
                color = if (failed > 0) TanukiTheme.colors.danger else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        TanukiIconButton(
            onClick = onCancel,
            modifier = Modifier.semantics { contentDescription = cancelDescription }
        ) { tint ->
            TrashIcon(tint = tint, modifier = Modifier.size(QueueGlyphSize))
        }
    }
}