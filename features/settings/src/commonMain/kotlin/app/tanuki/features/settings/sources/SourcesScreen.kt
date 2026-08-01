package app.tanuki.features.settings.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tanuki.assets.designSystem.TanukiRadii
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.SectionHeader
import app.tanuki.assets.designSystem.component.SourceIcon
import app.tanuki.assets.designSystem.component.TanukiButton
import app.tanuki.assets.designSystem.component.TanukiButtonVariant
import app.tanuki.assets.designSystem.component.TanukiDetailTopBar
import app.tanuki.assets.designSystem.component.TanukiDialog
import app.tanuki.assets.designSystem.component.TanukiEmptyState
import app.tanuki.assets.designSystem.component.TanukiIconButton
import app.tanuki.assets.designSystem.component.TanukiLoadingIndicator
import app.tanuki.assets.designSystem.component.TrashIcon
import app.tanuki.features.settings.resources.Res
import app.tanuki.features.settings.resources.settings_cancel
import app.tanuki.features.settings.resources.settings_install
import app.tanuki.features.settings.resources.settings_remove
import app.tanuki.features.settings.resources.settings_remove_source_action
import app.tanuki.features.settings.resources.settings_remove_source_message
import app.tanuki.features.settings.resources.settings_remove_source_title
import app.tanuki.features.settings.resources.settings_source_install_failed
import app.tanuki.features.settings.resources.settings_source_remove_failed
import app.tanuki.features.settings.resources.settings_sources
import app.tanuki.features.settings.resources.settings_sources_available
import app.tanuki.features.settings.resources.settings_sources_available_hint
import app.tanuki.features.settings.resources.settings_sources_empty
import app.tanuki.features.settings.resources.settings_sources_installed
import app.tanuki.features.settings.sources.viewmodel.SourceItem
import app.tanuki.features.settings.sources.viewmodel.SourcesState
import app.tanuki.features.settings.sources.viewmodel.SourcesViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.jetbrains.compose.resources.stringResource

/**
 * Source-management sub-screen. Back-navigating leaf pushed onto the Settings tab stack; lists the
 * installed sources with a (confirmed) remove, and below them the ones that can be installed again.
 *
 * It lives here rather than on the Home tab's source list on purpose: that list is a browse surface
 * where every row is a tap target the user hits constantly, and a destructive control does not belong
 * adjacent to one. Managing sources is a deliberate trip, and the trip is what makes the trash icon
 * safe to show inline (the same shape as the Categories screen).
 *
 * @param onBack pop back to the settings index.
 * @param viewModel the ViewModel driving the lists and the actions.
 */
@Composable
fun SourcesRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SourcesViewModel = metroViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SourcesScreen(
        state = state,
        onInstall = viewModel::onInstall,
        onUninstall = viewModel::onUninstall,
        onBack = onBack,
        modifier = modifier
    )
}

@Composable
private fun SourcesScreen(
    state: SourcesState,
    onInstall: (String) -> Unit,
    onUninstall: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Screen-local, not ViewModel state: which row is awaiting confirmation is a property of this
    // dialog being open, and it should not survive the screen the way an in-flight install does.
    var sourcePendingRemoval by remember { mutableStateOf<SourceItem?>(null) }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
    ) {
        TanukiDetailTopBar(
            title = stringResource(Res.string.settings_sources),
            onBack = onBack
        )
        when {
            // Held on the spinner until the first emission: a user with sources must never be shown
            // "no sources" for a frame while the bundles are still being read.
            state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { TanukiLoadingIndicator() }

            state.installed.isEmpty() && state.available.isEmpty() ->
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    TanukiEmptyState(message = stringResource(Res.string.settings_sources_empty))
                }

            else ->
                SourceSections(
                    state = state,
                    onInstall = onInstall,
                    onRemoveRequest = { sourcePendingRemoval = it }
                )
        }
    }

    sourcePendingRemoval?.let { source ->
        RemoveSourceDialog(
            source = source,
            onDismiss = { sourcePendingRemoval = null },
            onConfirm = {
                onUninstall(source.sourceId)
                sourcePendingRemoval = null
            }
        )
    }
}

/**
 * The two labelled sections. Each renders only when it has rows, so a device with everything installed
 * shows one clean list rather than an "Available" header over nothing.
 */
@Composable
private fun SourceSections(
    state: SourcesState,
    onInstall: (String) -> Unit,
    onRemoveRequest: (SourceItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = TanukiTheme.spacing
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.gutter, vertical = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.md)
    ) {
        if (state.installed.isNotEmpty()) {
            SectionHeader(title = stringResource(Res.string.settings_sources_installed))
            SourceGroup(
                sources = state.installed,
                installed = true,
                state = state,
                onAction = { onRemoveRequest(it) }
            )
        }

        if (state.available.isNotEmpty()) {
            SectionHeader(title = stringResource(Res.string.settings_sources_available))
            // States the consequence of the *previous* removal where the user can act on it: the library
            // is still there, and installing again is what resumes fetching for it.
            Text(
                text = stringResource(Res.string.settings_sources_available_hint),
                style = TanukiTheme.typography.caption,
                color = TanukiTheme.colors.textFaint
            )
            SourceGroup(
                sources = state.available,
                installed = false,
                state = state,
                onAction = { onInstall(it.sourceId) }
            )
        }
    }
}

/** One section's rows on a single rounded surface, separated by hairlines (the settings-card look). */
@Composable
private fun SourceGroup(
    sources: List<SourceItem>,
    installed: Boolean,
    state: SourcesState,
    onAction: (SourceItem) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(TanukiRadii.chipShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        sources.forEachIndexed { index, source ->
            if (index > 0) {
                HorizontalDivider(
                    thickness = DividerThickness,
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }
            SourceRow(
                source = source,
                installed = installed,
                busy = source.sourceId in state.busyIds,
                failed = source.sourceId in state.failedIds,
                onAction = { onAction(source) }
            )
        }
    }
}

/**
 * One source: its badge, its name over a subtitle, and the single action its section implies — remove
 * when installed, install when not.
 *
 * The subtitle carries the failure message when the last action failed, replacing the language tag. An
 * error belongs next to the control that produced it, and the app has no snackbar; putting it in the
 * row also means it disappears exactly when the user retries.
 */
@Composable
private fun SourceRow(
    source: SourceItem,
    installed: Boolean,
    busy: Boolean,
    failed: Boolean,
    onAction: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = TanukiTheme.spacing
    val failureText =
        when {
            !failed -> null
            installed -> stringResource(Res.string.settings_source_remove_failed)
            else -> stringResource(Res.string.settings_source_install_failed)
        }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = RowMinHeight)
                .padding(horizontal = spacing.md, vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SourceIcon(name = source.name, iconUrl = source.iconUrl, color = TanukiTheme.colors.accent)
        Column(
            modifier = Modifier.weight(1f).padding(horizontal = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xxs)
        ) {
            Text(
                text = source.name,
                style = TanukiTheme.typography.body,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val subtitle = failureText ?: source.lang
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = TanukiTheme.typography.caption,
                    color = if (failed) TanukiTheme.colors.danger else TanukiTheme.colors.textFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        when {
            // Progress replaces the control rather than sitting beside it: the row is unavailable while
            // the action runs, and an indicator where the button was says so without a disabled state.
            busy ->
                Box(Modifier.size(spacing.tapMin), Alignment.Center) {
                    TanukiLoadingIndicator(size = BusyIndicatorSize)
                }

            installed -> {
                // The glyph is the button's only label, so it carries the accessible name — and names the
                // source, since "Remove" alone is ambiguous in a list of them.
                val removeLabel = stringResource(Res.string.settings_remove_source_action, source.name)
                TanukiIconButton(onClick = onAction) { _ ->
                    TrashIcon(
                        tint = TanukiTheme.colors.danger,
                        modifier = Modifier.semantics { contentDescription = removeLabel }
                    )
                }
            }

            // A worded button, not a download glyph: installing is the rarer action here, and an
            // unlabelled icon would leave the user guessing what the row's one control does.
            else ->
                TanukiButton(
                    text = stringResource(Res.string.settings_install),
                    onClick = onAction,
                    variant = TanukiButtonVariant.Tonal
                )
        }
    }
}

/**
 * Confirmation before removing a source. The message's job is to correct the assumption a delete button
 * invites — this one does **not** touch the library — because a user who thinks it might will never risk
 * the tap, and one who finds out afterwards was misled.
 */
@Composable
private fun RemoveSourceDialog(
    source: SourceItem,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    TanukiDialog(
        title = stringResource(Res.string.settings_remove_source_title, source.name),
        onDismiss = onDismiss,
        dismissText = stringResource(Res.string.settings_cancel),
        confirmText = stringResource(Res.string.settings_remove),
        confirmVariant = TanukiButtonVariant.Danger,
        onConfirm = onConfirm
    ) {
        Text(
            text = stringResource(Res.string.settings_remove_source_message),
            style = TanukiTheme.typography.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Taller than a text-only settings row: this one carries a 34dp source badge and a two-line label. */
private val RowMinHeight = 60.dp

/** Sized to read as motion inside the 44dp control slot without crowding it. */
private val BusyIndicatorSize = 28.dp

private val DividerThickness = Dp.Hairline
