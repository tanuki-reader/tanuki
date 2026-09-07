package app.tanuki.features.manga.composable.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.tanuki.assets.designSystem.TanukiRadii
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.TanukiButton
import app.tanuki.assets.designSystem.component.TanukiButtonSize
import app.tanuki.assets.designSystem.component.TanukiButtonVariant
import app.tanuki.features.manga.resources.Res
import app.tanuki.features.manga.resources.manga_refresh_failed
import app.tanuki.features.manga.resources.manga_refresh_retry
import org.jetbrains.compose.resources.stringResource

/**
 * What a failed pull-to-refresh says: a line of copy and the retry that makes the message actionable.
 *
 * A floating strip rather than a full-screen error state, because nothing about the screen has actually
 * broken — the cached series is still on show and still readable, and only the attempt to check the
 * source for something newer failed. It carries its own surface and hairline so it stays legible over
 * the hero artwork it floats above.
 *
 * @param onRetry the retry was tapped — run the refresh again.
 */
@Composable
internal fun MangaRefreshError(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.sm),
        modifier =
            modifier
                .clip(TanukiRadii.chipShape)
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, TanukiTheme.colors.danger, TanukiRadii.chipShape)
                .padding(start = TanukiTheme.spacing.lg, end = TanukiTheme.spacing.xs)
    ) {
        Text(
            text = stringResource(Res.string.manga_refresh_failed),
            style = TanukiTheme.typography.body,
            color = MaterialTheme.colorScheme.onSurface
        )
        TanukiButton(
            text = stringResource(Res.string.manga_refresh_retry),
            onClick = onRetry,
            variant = TanukiButtonVariant.Text,
            size = TanukiButtonSize.Small
        )
    }
}

@Preview
@Composable
private fun MangaRefreshErrorPreview() {
    MangaRefreshError(onRetry = {})
}
