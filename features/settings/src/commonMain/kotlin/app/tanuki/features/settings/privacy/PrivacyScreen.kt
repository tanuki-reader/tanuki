package app.tanuki.features.settings.privacy

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tanuki.assets.designSystem.TanukiRadii
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.SectionHeader
import app.tanuki.assets.designSystem.component.TanukiDetailTopBar
import app.tanuki.assets.designSystem.component.TanukiListRow
import app.tanuki.features.settings.privacy.viewmodel.PrivacyViewModel
import app.tanuki.features.settings.resources.Res
import app.tanuki.features.settings.resources.settings_crash_reporting_collected
import app.tanuki.features.settings.resources.settings_crash_reporting_never
import app.tanuki.features.settings.resources.settings_crash_reporting_switch
import app.tanuki.features.settings.resources.settings_privacy
import app.tanuki.features.settings.resources.settings_section_crash_reporting
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.jetbrains.compose.resources.stringResource

/**
 * Privacy settings sub-screen: the one place that answers "what does this app send off my device?".
 *
 * The explanatory copy is the point of the screen, not decoration around the switch. Crash reporting is
 * off until the user turns it on, and an opt-in is only meaningful if what is being agreed to is visible
 * at the moment of agreeing — so the screen states plainly what a report contains and, just as
 * importantly, what it never contains.
 *
 * @param onBack pop back to the settings index.
 * @param viewModel the ViewModel driving the UI.
 */
@Composable
fun PrivacyRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PrivacyViewModel = metroViewModel()
) {
    val enabled by viewModel.crashReportingEnabled.collectAsStateWithLifecycle()
    PrivacyScreen(
        crashReportingEnabled = enabled,
        onCrashReportingChange = viewModel::onCrashReportingChange,
        onBack = onBack,
        modifier = modifier
    )
}

@Composable
private fun PrivacyScreen(
    crashReportingEnabled: Boolean,
    onCrashReportingChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
    ) {
        TanukiDetailTopBar(
            title = stringResource(Res.string.settings_privacy),
            onBack = onBack
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
            SectionHeader(title = stringResource(Res.string.settings_section_crash_reporting))
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(TanukiRadii.chipShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                TanukiListRow(
                    title = stringResource(Res.string.settings_crash_reporting_switch),
                    // The whole row toggles, so the switch is a display of state rather than the only
                    // target — the same affordance the rest of the settings rows offer.
                    onClick = { onCrashReportingChange(!crashReportingEnabled) },
                    showChevron = false,
                    trailing = {
                        Switch(
                            checked = crashReportingEnabled,
                            onCheckedChange = onCrashReportingChange
                        )
                    }
                )
            }
            Text(
                text = stringResource(Res.string.settings_crash_reporting_collected),
                style = TanukiTheme.typography.caption,
                color = TanukiTheme.colors.textFaint
            )
            Text(
                text = stringResource(Res.string.settings_crash_reporting_never),
                style = TanukiTheme.typography.caption,
                color = TanukiTheme.colors.textFaint
            )
        }
    }
}