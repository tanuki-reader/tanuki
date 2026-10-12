package app.tanuki.features.downloads.composable.components

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.CloseIcon
import app.tanuki.assets.designSystem.component.ErrorIcon
import app.tanuki.assets.designSystem.component.TanukiButton
import app.tanuki.assets.designSystem.component.TanukiButtonSize
import app.tanuki.assets.designSystem.component.TanukiButtonVariant
import app.tanuki.assets.designSystem.component.TanukiIconButton
import app.tanuki.assets.designSystem.component.TanukiListRow
import app.tanuki.assets.designSystem.component.TanukiProgressRing
import app.tanuki.core.domain.download.model.DownloadError
import app.tanuki.features.downloads.resources.Res
import app.tanuki.features.downloads.resources.downloads_cancel_chapter
import app.tanuki.features.downloads.resources.downloads_chapter_number
import app.tanuki.features.downloads.resources.downloads_chapter_untitled
import app.tanuki.features.downloads.resources.downloads_error_network
import app.tanuki.features.downloads.resources.downloads_error_source
import app.tanuki.features.downloads.resources.downloads_error_space
import app.tanuki.features.downloads.resources.downloads_error_unknown
import app.tanuki.features.downloads.resources.downloads_error_verification
import app.tanuki.features.downloads.resources.downloads_retry_chapter
import app.tanuki.features.downloads.resources.downloads_state_pages
import app.tanuki.features.downloads.resources.downloads_state_queued
import app.tanuki.features.downloads.resources.downloads_verify_chapter
import app.tanuki.features.downloads.viewmodel.DownloadChapterItem
import org.jetbrains.compose.resources.stringResource

/** The leading indicator's diameter — the same glyph size the chapter list's download control uses. */
internal val QueueGlyphSize = 20.dp

/**
 * One chapter in the queue, rendered as the design system's [TanukiListRow].
 *
 * The row is the list-row shape exactly: a leading status glyph, a title over a secondary line, and a
 * trailing control. Reusing the component rather than rebuilding that shape is what keeps this screen's
 * rhythm — row height, leading gap, text styles, truncation — identical to Settings and Sources, and it
 * inherits the 56dp minimum height that puts the row past the 44dp tap floor without this file knowing
 * the number.
 *
 * | State | Leading | Second line | Trailing |
 * |---|---|---|---|
 * | queued | a ring sweeping (it has no page count yet) | "Waiting" | cancel |
 * | downloading | a ring filling as pages land | "7 of 20 pages" | cancel |
 * | failed | an error glyph in `danger` | the localized reason, in `danger` | *Try again* / *Verify*, cancel |
 *
 * A failed row's retry is a **labelled button**, not another glyph: it is the only row here that asks the
 * user to decide something, and the two decisions ("try again" and "go solve a challenge") do not read
 * off an icon. Everything else on the screen is a glyph, which is what makes the button stand out on
 * exactly the rows that need attention. It is inserted *before* the cancel button, so cancel keeps its
 * position at the row's end when a chapter fails — the title column absorbs the width instead.
 *
 * The failure never rests on colour: the leading glyph changes shape, and the reason is spelled out in
 * words. The `danger` tint is the third signal, not the only one.
 *
 * @param queued the chapter and its record.
 * @param onCancel take this chapter out of the queue.
 * @param onRetry queue this chapter again after a failure.
 */
@Composable
internal fun DownloadQueueRow(
    queued: DownloadChapterItem,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val failed = queued.failed
    val cancelDescription = stringResource(Res.string.downloads_cancel_chapter)

    TanukiListRow(
        title = queued.title(),
        subtitle = queued.statusLine(),
        subtitleColor = if (failed) TanukiTheme.colors.danger else null,
        modifier = modifier,
        leading = {
            if (failed) {
                ErrorIcon(tint = TanukiTheme.colors.danger, size = QueueGlyphSize)
            } else {
                // `progress` is null until the page list has been fetched, which is exactly the queued
                // case — the ring sweeps rather than sitting at zero, so "waiting" and "stuck at 0%"
                // can't be mistaken for each other.
                TanukiProgressRing(progress = queued.progress, size = QueueGlyphSize)
            }
        },
        trailing = {
            if (failed) {
                TanukiButton(
                    text =
                        stringResource(
                            if (queued.error == DownloadError.NeedsVerification) {
                                Res.string.downloads_verify_chapter
                            } else {
                                Res.string.downloads_retry_chapter
                            }
                        ),
                    onClick = onRetry,
                    variant = TanukiButtonVariant.Tonal,
                    size = TanukiButtonSize.Small
                )
            }
            TanukiIconButton(
                onClick = onCancel,
                modifier = Modifier.semantics { contentDescription = cancelDescription }
            ) { tint ->
                CloseIcon(tint = tint, modifier = Modifier.size(QueueGlyphSize))
            }
        },
        // The row itself does nothing, so it must not look like it opens something. Its two controls are
        // the only targets on it.
    )
}

/**
 * The row's title, following the chapter list's rule so the same chapter is named the same thing in both
 * places: the number when the source gave one, else its name, else a placeholder so the row is never
 * blank.
 */
@Composable
private fun DownloadChapterItem.title(): String =
    numberLabel?.let { stringResource(Res.string.downloads_chapter_number, it) }
        ?: name
        ?: stringResource(Res.string.downloads_chapter_untitled)

/**
 * The row's second line: what the queue is doing with this chapter, or why it stopped.
 *
 * A downloading chapter reports pages rather than a percentage. The ring beside it already carries the
 * proportion; the numbers answer the question the ring can't — how much is actually left.
 */
@Composable
private fun DownloadChapterItem.statusLine(): String =
    if (failed) {
        stringResource(
            when (error) {
                DownloadError.Network -> Res.string.downloads_error_network
                DownloadError.SourceUnavailable -> Res.string.downloads_error_source
                DownloadError.NeedsVerification -> Res.string.downloads_error_verification
                DownloadError.OutOfSpace -> Res.string.downloads_error_space
                // A record can be `Failed` with no reason only if a write was interrupted between the
                // state and the error; "the download failed" is true either way.
                DownloadError.Unknown, null -> Res.string.downloads_error_unknown
            }
        )
    } else {
        // Queued, or picked up but its page list not here yet: no total to count against, so "waiting"
        // as far as anything visible goes.
        pageCount?.let { stringResource(Res.string.downloads_state_pages, downloadedPages, it) }
            ?: stringResource(Res.string.downloads_state_queued)
    }
