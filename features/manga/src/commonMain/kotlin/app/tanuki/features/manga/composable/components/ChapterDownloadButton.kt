package app.tanuki.features.manga.composable.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.DownloadIcon
import app.tanuki.assets.designSystem.component.DownloadedIcon
import app.tanuki.assets.designSystem.component.ErrorIcon
import app.tanuki.assets.designSystem.component.TanukiIconButton
import app.tanuki.assets.designSystem.component.TanukiMenu
import app.tanuki.assets.designSystem.component.TanukiMenuItem
import app.tanuki.assets.designSystem.component.TanukiProgressRing
import app.tanuki.core.domain.download.model.ChapterDownload
import app.tanuki.core.domain.download.model.DownloadState
import app.tanuki.features.manga.resources.Res
import app.tanuki.features.manga.resources.manga_download_cancel
import app.tanuki.features.manga.resources.manga_download_chapter
import app.tanuki.features.manga.resources.manga_download_delete
import app.tanuki.features.manga.resources.manga_download_downloaded
import app.tanuki.features.manga.resources.manga_download_failed
import app.tanuki.features.manga.resources.manga_download_queued
import org.jetbrains.compose.resources.stringResource

/** The glyph size inside the affordance; the tap target around it stays `spacing.tapMin`. */
private val GlyphSize = 20.dp

/** How far a queued chapter's glyph fades — waiting, not acting. */
private const val QUEUED_ALPHA = 0.45f

/**
 * The trailing download control on a chapter row: one target whose meaning is whatever the chapter's
 * download state affords.
 *
 * | State | Shows | Tap |
 * |---|---|---|
 * | not queued | outline download glyph | queue it |
 * | queued | the same glyph, dimmed | cancel |
 * | downloading | a determinate progress ring | cancel |
 * | downloaded | a filled success check | open a menu holding *Delete download* |
 * | failed | an error glyph in `danger` | retry |
 *
 * **Only the finished state opens a menu.** Every other tap is a single, reversible act — queue, cancel,
 * retry — and putting those behind a menu would tax the common case to protect the rare one. Deleting
 * files is neither reversible nor guessable from a glyph, so it is the one thing that asks twice.
 *
 * Each state also carries its own content description, because the glyph is the only thing distinguishing
 * five different actions on an otherwise identical row — an unlabelled icon button here would leave a
 * screen-reader user with five buttons called "download".
 *
 * @param download this chapter's record, or `null` when it has never been queued.
 * @param onClick the affordance was tapped — the ViewModel resolves what that means for the state.
 * @param onDelete *Delete download* was chosen from the finished state's menu.
 */
@Composable
internal fun ChapterDownloadButton(
    download: ChapterDownload?,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state = download?.state
    var menuOpen by remember { mutableStateOf(false) }
    val description =
        when (state) {
            null -> stringResource(Res.string.manga_download_chapter)
            DownloadState.Queued -> stringResource(Res.string.manga_download_queued)
            DownloadState.Downloading -> stringResource(Res.string.manga_download_cancel)
            DownloadState.Downloaded -> stringResource(Res.string.manga_download_downloaded)
            DownloadState.Failed -> stringResource(Res.string.manga_download_failed)
        }

    Box(modifier) {
        TanukiIconButton(
            onClick = { if (state == DownloadState.Downloaded) menuOpen = true else onClick() },
            modifier = Modifier.semantics { contentDescription = description }
        ) {
            when (state) {
                null ->
                    DownloadIcon(tint = TanukiTheme.colors.textFaint, size = GlyphSize)

                DownloadState.Queued ->
                    DownloadIcon(
                        tint = TanukiTheme.colors.textFaint,
                        size = GlyphSize,
                        modifier = Modifier.alpha(QUEUED_ALPHA)
                    )

                DownloadState.Downloading ->
                    TanukiProgressRing(progress = download.progress, size = GlyphSize)

                DownloadState.Downloaded ->
                    DownloadedIcon(
                        tint = TanukiTheme.colors.success,
                        // Near-black on the green disc in the dark dialect, near-white in the light one:
                        // the tick contrasts with the *disc*, so it holds on a selected row's accent wash
                        // exactly as it does on the plain list.
                        knockout = MaterialTheme.colorScheme.surface,
                        size = GlyphSize
                    )

                DownloadState.Failed ->
                    ErrorIcon(tint = TanukiTheme.colors.danger, size = GlyphSize)
            }
        }
        TanukiMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            TanukiMenuItem(
                text = stringResource(Res.string.manga_download_delete),
                // Destructive: this is the one tap on the row that cannot be undone, and the only reason
                // a finished chapter opens a menu at all rather than acting on the tap.
                destructive = true,
                onClick = {
                    menuOpen = false
                    onDelete()
                }
            )
        }
    }
}