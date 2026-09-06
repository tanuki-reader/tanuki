package app.tanuki.features.manga.composable.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.DownloadIcon
import app.tanuki.assets.designSystem.component.TanukiButtonVariant
import app.tanuki.assets.designSystem.component.TanukiDialog
import app.tanuki.assets.designSystem.component.TanukiIconButton
import app.tanuki.assets.designSystem.component.TanukiMenu
import app.tanuki.assets.designSystem.component.TanukiMenuDivider
import app.tanuki.assets.designSystem.component.TanukiMenuItem
import app.tanuki.features.manga.resources.Res
import app.tanuki.features.manga.resources.manga_download_all
import app.tanuki.features.manga.resources.manga_download_all_unread
import app.tanuki.features.manga.resources.manga_download_delete_all
import app.tanuki.features.manga.resources.manga_download_delete_all_cancel
import app.tanuki.features.manga.resources.manga_download_delete_all_confirm
import app.tanuki.features.manga.resources.manga_download_delete_all_message
import app.tanuki.features.manga.resources.manga_download_delete_all_title
import app.tanuki.features.manga.resources.manga_download_menu
import app.tanuki.features.manga.resources.manga_download_next
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** The "next N" sizes offered. Small, then a chapter's worth of an evening, then a sitting. */
private val NextCounts = listOf(1, 5, 10)

/**
 * The series-wide download menu, hung off the detail top bar.
 *
 * It lives in the app bar rather than beside the "Read" button because the bottom bar is a single
 * full-width primary action, and the design kit's own manga-detail screen puts a download control up
 * here. What it offers is the bulk shape of the feature: queue the next few chapters without picking them
 * one by one, or clear the series off the device.
 *
 * "Next N" means the next *unread* chapters in reading order, honouring the scanlator filter — the rule
 * lives in the domain (`EnqueueNextChapters`) so it means the same thing wherever it is offered. "All"
 * means every chapter the list is currently showing, read ones included.
 *
 * Deleting is separated from the queueing items by a divider and coloured `danger`, and it asks for
 * confirmation: it is the only irreversible item here, and the only one whose cost (a re-download over
 * mobile data) the menu cannot undo. It is hidden entirely when there is nothing downloaded, so the
 * destructive item never appears for a series it could not affect.
 *
 * @param hasDownloads whether this series has anything downloaded or queued — gates the delete item.
 * @param onDownloadNext queue the next [Int] unread chapters, or every unread one when `null`.
 * @param onDownloadAll queue every chapter the list is showing.
 * @param onDeleteAll remove this series' downloads from the device; called only after confirmation.
 */
@Composable
internal fun MangaDownloadMenu(
    hasDownloads: Boolean,
    onDownloadNext: (Int?) -> Unit,
    onDownloadAll: () -> Unit,
    onDeleteAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val menuDescription = stringResource(Res.string.manga_download_menu)

    Box(modifier) {
        TanukiIconButton(
            onClick = { menuOpen = true },
            modifier = Modifier.semantics { contentDescription = menuDescription }
        ) { tint ->
            DownloadIcon(tint = tint)
        }
        TanukiMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            NextCounts.forEach { count ->
                TanukiMenuItem(
                    text = pluralStringResource(Res.plurals.manga_download_next, count, count),
                    onClick = {
                        menuOpen = false
                        onDownloadNext(count)
                    }
                )
            }
            TanukiMenuItem(
                text = stringResource(Res.string.manga_download_all_unread),
                onClick = {
                    menuOpen = false
                    onDownloadNext(null)
                }
            )
            TanukiMenuItem(
                text = stringResource(Res.string.manga_download_all),
                onClick = {
                    menuOpen = false
                    onDownloadAll()
                }
            )
            if (hasDownloads) {
                TanukiMenuDivider()
                TanukiMenuItem(
                    text = stringResource(Res.string.manga_download_delete_all),
                    destructive = true,
                    onClick = {
                        menuOpen = false
                        confirmDelete = true
                    }
                )
            }
        }
    }

    if (confirmDelete) {
        TanukiDialog(
            title = stringResource(Res.string.manga_download_delete_all_title),
            onDismiss = { confirmDelete = false },
            dismissText = stringResource(Res.string.manga_download_delete_all_cancel),
            confirmText = stringResource(Res.string.manga_download_delete_all_confirm),
            confirmVariant = TanukiButtonVariant.Danger,
            onConfirm = {
                confirmDelete = false
                onDeleteAll()
            }
        ) {
            Text(
                text = stringResource(Res.string.manga_download_delete_all_message),
                style = TanukiTheme.typography.body,
                color = TanukiTheme.colors.textFaint
            )
        }
    }
}