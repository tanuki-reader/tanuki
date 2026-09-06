package app.tanuki.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.ContrastIcon
import app.tanuki.assets.designSystem.component.DownloadIcon
import app.tanuki.assets.designSystem.component.ExtensionIcon
import app.tanuki.assets.designSystem.component.LabelIcon
import app.tanuki.assets.designSystem.component.ShieldCheckIcon
import app.tanuki.assets.designSystem.component.SectionHeader
import app.tanuki.assets.designSystem.component.TanukiListRow
import app.tanuki.assets.designSystem.component.TanukiListRowDefaults
import app.tanuki.assets.designSystem.component.TanukiRowDivider
import app.tanuki.assets.designSystem.component.TanukiSectionGroup
import app.tanuki.assets.designSystem.component.TanukiTopBar
import app.tanuki.core.navigation.Destination
import app.tanuki.core.navigation.Downloads
import app.tanuki.core.navigation.SettingsAppearance
import app.tanuki.core.navigation.SettingsCategories
import app.tanuki.core.navigation.SettingsPrivacy
import app.tanuki.core.navigation.SettingsSources
import app.tanuki.features.settings.resources.Res
import app.tanuki.features.settings.resources.settings_appearance
import app.tanuki.features.settings.resources.settings_categories
import app.tanuki.features.settings.resources.settings_category_count
import app.tanuki.features.settings.resources.settings_download_queue
import app.tanuki.features.settings.resources.settings_downloads
import app.tanuki.features.settings.resources.settings_downloads_idle
import app.tanuki.features.settings.resources.settings_privacy
import app.tanuki.features.settings.resources.settings_privacy_off
import app.tanuki.features.settings.resources.settings_privacy_on
import app.tanuki.features.settings.resources.settings_section_library
import app.tanuki.features.settings.resources.settings_section_personalization
import app.tanuki.features.settings.resources.settings_section_privacy
import app.tanuki.features.settings.resources.settings_source_count
import app.tanuki.features.settings.resources.settings_sources
import app.tanuki.features.settings.resources.settings_title
import app.tanuki.features.settings.viewmodel.SettingsViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Stateful entry point for the Settings tab — the **index**: a scannable list of grouped rows, each
 * showing its current value and drilling into a focused sub-screen. This drill-down structure is what
 * lets the settings surface grow (Reader, Downloads, Tracking, …) without an endless single scroll.
 *
 * The index itself only reads state (row subtitles); the sub-screens own their controls. Navigation
 * mechanics stay in the host — this route just reports the tapped [Destination] to [onNavigate].
 *
 * @param onNavigate invoked with the sub-screen [Destination] a row targets.
 * @param modifier the overall modifier for the composable.
 * @param viewModel the ViewModel driving the row subtitles.
 */
@Composable
fun SettingsRoute(
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = metroViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding()
    ) {
        TanukiTopBar(
            title = stringResource(Res.string.settings_title),
            searchActive = false,
            searchQuery = "",
            onSearchClose = {},
            onSearchQueryChange = {}
        )

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        horizontal = TanukiTheme.spacing.gutter,
                        vertical = TanukiTheme.spacing.md
                    ),
            verticalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.md)
        ) {
            SectionHeader(title = stringResource(Res.string.settings_section_personalization))
            TanukiSectionGroup {
                TanukiListRow(
                    title = stringResource(Res.string.settings_appearance),
                    subtitle = stringResource(state.theme.labelRes()),
                    leading = { ContrastIcon(tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = { onNavigate(SettingsAppearance) }
                )
            }

            SectionHeader(title = stringResource(Res.string.settings_section_privacy))
            TanukiSectionGroup {
                TanukiListRow(
                    title = stringResource(Res.string.settings_privacy),
                    subtitle =
                        stringResource(
                            if (state.crashReportingEnabled) {
                                Res.string.settings_privacy_on
                            } else {
                                Res.string.settings_privacy_off
                            }
                        ),
                    leading = { ShieldCheckIcon(tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = { onNavigate(SettingsPrivacy) }
                )
            }

            SectionHeader(title = stringResource(Res.string.settings_section_library))
            TanukiSectionGroup {
                TanukiListRow(
                    title = stringResource(Res.string.settings_categories),
                    subtitle =
                        pluralStringResource(
                            Res.plurals.settings_category_count,
                            state.categoryCount,
                            state.categoryCount
                        ),
                    leading = { LabelIcon(tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = { onNavigate(SettingsCategories) }
                )
                TanukiRowDivider(startInset = RowDividerInset)
                TanukiListRow(
                    title = stringResource(Res.string.settings_sources),
                    subtitle =
                        pluralStringResource(
                            Res.plurals.settings_source_count,
                            state.sourceCount,
                            state.sourceCount
                        ),
                    leading = { ExtensionIcon(tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = { onNavigate(SettingsSources) }
                )
                TanukiRowDivider(startInset = RowDividerInset)
                // The queue's only entry point that doesn't require already being on a series' page —
                // and the reason it names its count: a download that stopped on an error is otherwise
                // invisible from anywhere in the app the user is likely to be.
                TanukiListRow(
                    title = stringResource(Res.string.settings_downloads),
                    subtitle =
                        if (state.downloadQueueCount == 0) {
                            stringResource(Res.string.settings_downloads_idle)
                        } else {
                            pluralStringResource(
                                Res.plurals.settings_download_queue,
                                state.downloadQueueCount,
                                state.downloadQueueCount
                            )
                        },
                    leading = {
                        DownloadIcon(
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            size = SettingsIconSize
                        )
                    },
                    onClick = { onNavigate(Downloads) }
                )
            }
        }
    }
}

/** The index's leading icons are the [ContrastIcon] family at its default size. */
private val SettingsIconSize = 20.dp

/** Runs the rule between two rows under their titles rather than their icons. */
private val RowDividerInset = TanukiListRowDefaults.dividerInset(SettingsIconSize)