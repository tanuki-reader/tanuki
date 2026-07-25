package app.tanuki.features.settings.appearance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.Segment
import app.tanuki.assets.designSystem.component.SectionHeader
import app.tanuki.assets.designSystem.component.TanukiDetailTopBar
import app.tanuki.assets.designSystem.component.TanukiSegmentedControl
import app.tanuki.core.domain.settings.model.ThemePreference
import app.tanuki.features.settings.labelRes
import app.tanuki.features.settings.appearance.viewmodel.AppearanceViewModel
import app.tanuki.features.settings.resources.Res
import app.tanuki.features.settings.resources.settings_appearance
import app.tanuki.features.settings.resources.settings_theme
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.jetbrains.compose.resources.stringResource

/**
 * Appearance settings sub-screen. Back-navigating leaf pushed onto the Settings tab stack; hosts the
 * theme control today and is the home for future look-and-feel options.
 *
 * @param onBack pop back to the settings index.
 * @param viewModel the ViewModel driving the UI.
 */
@Composable
fun AppearanceRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AppearanceViewModel = metroViewModel()
) {
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    AppearanceScreen(
        theme = theme,
        onThemeChange = viewModel::onThemeChange,
        onBack = onBack,
        modifier = modifier
    )
}

@Composable
private fun AppearanceScreen(
    theme: ThemePreference,
    onThemeChange: (ThemePreference) -> Unit,
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
            title = stringResource(Res.string.settings_appearance),
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
            SectionHeader(title = stringResource(Res.string.settings_theme))
            TanukiSegmentedControl(
                segments =
                    ThemePreference.entries.map { pref ->
                        Segment(pref, stringResource(pref.labelRes()))
                    },
                selected = theme,
                onSelect = onThemeChange
            )
        }
    }
}