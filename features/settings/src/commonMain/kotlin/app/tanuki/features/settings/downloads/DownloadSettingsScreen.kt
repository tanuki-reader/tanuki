package app.tanuki.features.settings.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.DownloadIcon
import app.tanuki.assets.designSystem.component.SectionHeader
import app.tanuki.assets.designSystem.component.TanukiButtonVariant
import app.tanuki.assets.designSystem.component.TanukiChoiceRow
import app.tanuki.assets.designSystem.component.TanukiDetailTopBar
import app.tanuki.assets.designSystem.component.TanukiDialog
import app.tanuki.assets.designSystem.component.TanukiFilterChip
import app.tanuki.assets.designSystem.component.TanukiListRow
import app.tanuki.assets.designSystem.component.TanukiListRowDefaults
import app.tanuki.assets.designSystem.component.TanukiLoadingIndicator
import app.tanuki.assets.designSystem.component.TanukiRowDivider
import app.tanuki.assets.designSystem.component.TanukiSectionGroup
import app.tanuki.assets.designSystem.component.TanukiSwitchRow
import app.tanuki.assets.designSystem.component.TrashIcon
import app.tanuki.core.domain.download.model.DownloadStorageUsage
import app.tanuki.core.domain.download.model.SeriesStorage
import app.tanuki.core.domain.settings.model.AutoDownloadMode
import app.tanuki.core.domain.settings.model.DeleteAfterReading
import app.tanuki.core.navigation.Destination
import app.tanuki.core.navigation.Downloads
import app.tanuki.features.settings.downloads.viewmodel.DownloadSettingsState
import app.tanuki.features.settings.downloads.viewmodel.DownloadSettingsViewModel
import app.tanuki.features.settings.resources.Res
import app.tanuki.features.settings.resources.settings_auto_download_all
import app.tanuki.features.settings.resources.settings_auto_download_all_hint
import app.tanuki.features.settings.resources.settings_auto_download_categories
import app.tanuki.features.settings.resources.settings_auto_download_categories_hint
import app.tanuki.features.settings.resources.settings_auto_download_off
import app.tanuki.features.settings.resources.settings_auto_download_off_hint
import app.tanuki.features.settings.resources.settings_auto_download_pick
import app.tanuki.features.settings.resources.settings_delete_immediately
import app.tanuki.features.settings.resources.settings_delete_immediately_hint
import app.tanuki.features.settings.resources.settings_delete_keep_five
import app.tanuki.features.settings.resources.settings_delete_keep_five_hint
import app.tanuki.features.settings.resources.settings_delete_keep_one
import app.tanuki.features.settings.resources.settings_delete_keep_one_hint
import app.tanuki.features.settings.resources.settings_delete_never
import app.tanuki.features.settings.resources.settings_delete_never_hint
import app.tanuki.features.settings.resources.settings_delete_not_retroactive
import app.tanuki.features.settings.resources.settings_downloads_title
import app.tanuki.features.settings.resources.settings_section_auto_download
import app.tanuki.features.settings.resources.settings_section_delete_after_reading
import app.tanuki.features.settings.resources.settings_section_network
import app.tanuki.features.settings.resources.settings_section_storage
import app.tanuki.features.settings.resources.settings_storage_delete_all
import app.tanuki.features.settings.resources.settings_storage_delete_all_cancel
import app.tanuki.features.settings.resources.settings_storage_delete_all_confirm
import app.tanuki.features.settings.resources.settings_storage_delete_all_message
import app.tanuki.features.settings.resources.settings_storage_delete_all_title
import app.tanuki.features.settings.resources.settings_storage_empty
import app.tanuki.features.settings.resources.settings_storage_open_queue
import app.tanuki.features.settings.resources.settings_storage_open_queue_hint
import app.tanuki.features.settings.resources.settings_storage_series_chapters
import app.tanuki.features.settings.resources.settings_storage_total
import app.tanuki.features.settings.resources.settings_wifi_only
import app.tanuki.features.settings.resources.settings_wifi_only_hint
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlin.uuid.Uuid
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** How far the storage section fades while the delete-everything sweep runs. */
private const val BUSY_ALPHA = 0.5f

/** The leading glyph size on the storage rows — the settings index's own icon scale. */
private val RowGlyphSize = 20.dp

/** How many series the breakdown lists before it stops. See [StorageSection]. */
private const val MAX_LISTED_SERIES = 5

/**
 * Download settings: the rules the subsystem follows on its own, and what it is costing.
 *
 * Four sections, in the order a user reaches for them: what gets downloaded without asking, when it is
 * allowed to happen, what becomes of it afterwards, and how much room it is taking. Each is a
 * [TanukiSectionGroup] under a [SectionHeader] — the same grouped-index shape as the settings root, so
 * this reads as part of that surface rather than as a screen of its own invention.
 *
 * ### Progressive disclosure
 *
 * The category picker appears only under the mode that uses it, and the per-category mode itself is
 * hidden when the user has no categories. Both follow the same rule: never show a control whose only
 * possible outcome is confusion. A disabled "only these categories" row invites the user to find out how
 * to enable it, and the answer lives on a different screen with no way to say so from here.
 *
 * ### Explanations, not just labels
 *
 * Every choice carries a subtitle saying what picking it does. These settings spend the user's storage
 * and mobile data, and "Keep the last five" means nothing on its own — a control whose consequence has
 * to be guessed at is one the user will either avoid or regret.
 */
@Composable
fun DownloadSettingsRoute(
    onNavigate: (Destination) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DownloadSettingsViewModel = metroViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    DownloadSettingsScreen(
        state = state,
        onBack = onBack,
        onOpenQueue = { onNavigate(Downloads) },
        onAutoDownloadModeChange = viewModel::onAutoDownloadModeChange,
        onCategoryToggle = viewModel::onAutoDownloadCategoryToggle,
        onWifiOnlyChange = viewModel::onWifiOnlyChange,
        onDeleteAfterReadingChange = viewModel::onDeleteAfterReadingChange,
        onDeleteAllDownloads = viewModel::onDeleteAllDownloads,
        modifier = modifier
    )
}

@Composable
internal fun DownloadSettingsScreen(
    state: DownloadSettingsState,
    onBack: () -> Unit,
    onOpenQueue: () -> Unit,
    onAutoDownloadModeChange: (AutoDownloadMode) -> Unit,
    onCategoryToggle: (Uuid) -> Unit,
    onWifiOnlyChange: (Boolean) -> Unit,
    onDeleteAfterReadingChange: (DeleteAfterReading) -> Unit,
    onDeleteAllDownloads: () -> Unit,
    modifier: Modifier = Modifier
) {
    var confirmingDeleteAll by remember { mutableStateOf(false) }
    val spacing = TanukiTheme.spacing

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
    ) {
        TanukiDetailTopBar(
            title = stringResource(Res.string.settings_downloads_title),
            onBack = onBack
        )
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = spacing.gutter, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            AutoDownloadSection(
                state = state,
                onModeChange = onAutoDownloadModeChange,
                onCategoryToggle = onCategoryToggle
            )
            NetworkSection(
                wifiOnly = state.settings.onWifiOnly,
                onWifiOnlyChange = onWifiOnlyChange
            )
            DeleteAfterReadingSection(
                selected = state.settings.deleteAfterReading,
                onChange = onDeleteAfterReadingChange
            )
            StorageSection(
                state = state,
                onOpenQueue = onOpenQueue,
                onDeleteAll = { confirmingDeleteAll = true }
            )
        }
    }

    if (confirmingDeleteAll) {
        TanukiDialog(
            title = stringResource(Res.string.settings_storage_delete_all_title),
            onDismiss = { confirmingDeleteAll = false },
            confirmText = stringResource(Res.string.settings_storage_delete_all_confirm),
            onConfirm = {
                confirmingDeleteAll = false
                onDeleteAllDownloads()
            },
            dismissText = stringResource(Res.string.settings_storage_delete_all_cancel),
            confirmVariant = TanukiButtonVariant.Danger
        ) {
            Text(
                // Says what is *not* touched as well as what is. The fear this dialog has to answer is
                // "will I lose my library", and only naming it answers it.
                text = stringResource(Res.string.settings_storage_delete_all_message),
                style = TanukiTheme.typography.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Which new chapters are fetched without being asked for, and — under one mode — for which categories. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AutoDownloadSection(
    state: DownloadSettingsState,
    onModeChange: (AutoDownloadMode) -> Unit,
    onCategoryToggle: (Uuid) -> Unit
) {
    val settings = state.settings
    Column(verticalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.sm)) {
        SectionHeader(title = stringResource(Res.string.settings_section_auto_download))
        TanukiSectionGroup {
            TanukiChoiceRow(
                title = stringResource(Res.string.settings_auto_download_off),
                subtitle = stringResource(Res.string.settings_auto_download_off_hint),
                selected = settings.autoDownloadMode == AutoDownloadMode.Off,
                onClick = { onModeChange(AutoDownloadMode.Off) }
            )
            TanukiRowDivider(startInset = TanukiListRowDefaults.dividerInset(0.dp))
            TanukiChoiceRow(
                title = stringResource(Res.string.settings_auto_download_all),
                subtitle = stringResource(Res.string.settings_auto_download_all_hint),
                selected = settings.autoDownloadMode == AutoDownloadMode.AllLibrary,
                onClick = { onModeChange(AutoDownloadMode.AllLibrary) }
            )
            // Hidden rather than disabled when there are no categories to pick — see the screen KDoc.
            if (state.canSelectCategories) {
                TanukiRowDivider(startInset = TanukiListRowDefaults.dividerInset(0.dp))
                TanukiChoiceRow(
                    title = stringResource(Res.string.settings_auto_download_categories),
                    subtitle = stringResource(Res.string.settings_auto_download_categories_hint),
                    selected = settings.autoDownloadMode == AutoDownloadMode.SelectedCategories,
                    onClick = { onModeChange(AutoDownloadMode.SelectedCategories) }
                )
            }
        }

        if (state.showCategoryPicker) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.sm),
                verticalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.sm)
            ) {
                state.categories.forEach { category ->
                    TanukiFilterChip(
                        // Content: the user named these, so they are never localized.
                        text = category.name.value,
                        selected = category.id in settings.autoDownloadCategories,
                        onClick = { onCategoryToggle(category.id) }
                    )
                }
            }
            // Only while the selection would do nothing. A permanent hint is noise; this one appears
            // exactly when the setting is configured into a state that silently downloads nothing.
            if (settings.autoDownloadCategories.isEmpty()) {
                Hint(stringResource(Res.string.settings_auto_download_pick))
            }
        }
    }
}

/** When the queue is allowed to run. */
@Composable
private fun NetworkSection(
    wifiOnly: Boolean,
    onWifiOnlyChange: (Boolean) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.sm)) {
        SectionHeader(title = stringResource(Res.string.settings_section_network))
        TanukiSectionGroup {
            TanukiSwitchRow(
                title = stringResource(Res.string.settings_wifi_only),
                subtitle = stringResource(Res.string.settings_wifi_only_hint),
                checked = wifiOnly,
                onCheckedChange = onWifiOnlyChange
            )
        }
    }
}

/** What becomes of a chapter once it has been read. */
@Composable
private fun DeleteAfterReadingSection(
    selected: DeleteAfterReading,
    onChange: (DeleteAfterReading) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.sm)) {
        SectionHeader(title = stringResource(Res.string.settings_section_delete_after_reading))
        TanukiSectionGroup {
            DeleteAfterReading.entries.forEachIndexed { index, option ->
                if (index > 0) TanukiRowDivider(startInset = TanukiListRowDefaults.dividerInset(0.dp))
                TanukiChoiceRow(
                    title = stringResource(option.labelRes()),
                    subtitle = stringResource(option.hintRes()),
                    selected = selected == option,
                    onClick = { onChange(option) }
                )
            }
        }
        // Shown only once the setting actually deletes something. It answers the question the user has
        // at exactly that moment — "is it about to eat what I already have?" — and saying it while the
        // setting is off would raise a worry that does not apply.
        if (selected.isEnabled) {
            Hint(stringResource(Res.string.settings_delete_not_retroactive))
        }
    }
}

/**
 * What the downloads cost, and the two things to do about it.
 *
 * The breakdown is capped at [MAX_LISTED_SERIES]. A full list would be a second library grid on a
 * settings screen, and the question here is "what is taking the room" — which the largest few answer.
 * Anything finer is a per-series decision, and the series' own page is where that is made.
 */
@Composable
private fun StorageSection(
    state: DownloadSettingsState,
    onOpenQueue: () -> Unit,
    onDeleteAll: () -> Unit
) {
    val usage = state.usage
    Column(verticalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.sm)) {
        SectionHeader(title = stringResource(Res.string.settings_section_storage))
        TanukiSectionGroup {
            TanukiListRow(
                title = stringResource(Res.string.settings_storage_open_queue),
                subtitle = stringResource(Res.string.settings_storage_open_queue_hint),
                leading = { DownloadIcon(tint = MaterialTheme.colorScheme.onSurfaceVariant, size = RowGlyphSize) },
                onClick = onOpenQueue
            )
        }

        when {
            // `null` means "not measured yet", which is not the same as zero — flashing "0 B" at
            // someone with a full device is the one wrong answer this section can give.
            usage == null ->
                Box(
                    modifier = Modifier.fillMaxWidth().padding(TanukiTheme.spacing.md),
                    contentAlignment = Alignment.Center
                ) { TanukiLoadingIndicator() }

            usage.isEmpty -> Hint(stringResource(Res.string.settings_storage_empty))

            else ->
                StorageBreakdown(
                    usage = usage,
                    busy = state.busy,
                    onDeleteAll = onDeleteAll
                )
        }
    }
}

@Composable
private fun StorageBreakdown(
    usage: DownloadStorageUsage,
    busy: Boolean,
    onDeleteAll: () -> Unit
) {
    Column(
        // Dimmed, not disabled: the sweep takes a moment and the rows emptying underneath are the
        // feedback that it is working.
        modifier = Modifier.alpha(if (busy) BUSY_ALPHA else 1f),
        verticalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.sm)
    ) {
        Text(
            text = stringResource(Res.string.settings_storage_total, usage.totalBytes.asFileSize()),
            style = TanukiTheme.typography.headline,
            color = MaterialTheme.colorScheme.onSurface
        )
        TanukiSectionGroup {
            usage.series.take(MAX_LISTED_SERIES).forEachIndexed { index, series ->
                if (index > 0) TanukiRowDivider(startInset = TanukiListRowDefaults.dividerInset(0.dp))
                SeriesStorageRow(series)
            }
            TanukiRowDivider(startInset = TanukiListRowDefaults.dividerInset(0.dp))
            TanukiListRow(
                title = stringResource(Res.string.settings_storage_delete_all),
                leading = { TrashIcon(tint = TanukiTheme.colors.danger, size = RowGlyphSize) },
                onClick = onDeleteAll,
                showChevron = false,
                // The one irreversible row on the screen: `danger`-tinted, sat below a divider at the
                // end of the group, and confirmed. Colour is not the whole signal — the trash glyph and
                // its position after everything else say the same thing.
                destructive = true
            )
        }
    }
}

@Composable
private fun SeriesStorageRow(series: SeriesStorage) {
    TanukiListRow(
        // Content from the source, never localized.
        title = series.manga.title.value,
        subtitle =
            pluralStringResource(
                Res.plurals.settings_storage_series_chapters,
                series.chapterCount,
                series.chapterCount,
                series.bytes.asFileSize()
            )
    )
}

/**
 * A line of explanatory copy under a group.
 *
 * `onSurfaceVariant` rather than `textFaint`, which is 2.5:1 against the light dialect's paper canvas —
 * below the floor for even secondary text. This copy is the part of a setting that makes it
 * understandable, so it has to be as readable as the labels above it.
 */
@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = TanukiTheme.typography.caption,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = TanukiTheme.spacing.xs)
    )
}
