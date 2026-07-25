package app.tanuki.features.settings

import app.tanuki.core.domain.settings.model.ThemePreference
import app.tanuki.features.settings.resources.Res
import app.tanuki.features.settings.resources.settings_theme_dark
import app.tanuki.features.settings.resources.settings_theme_light
import app.tanuki.features.settings.resources.settings_theme_system
import org.jetbrains.compose.resources.StringResource

/**
 * The localized label for a [ThemePreference]. Shared by the settings index (Appearance row subtitle)
 * and the Appearance screen (segmented-control labels) so the wording can't drift between them.
 */
internal fun ThemePreference.labelRes(): StringResource =
    when (this) {
        ThemePreference.System -> Res.string.settings_theme_system
        ThemePreference.Light -> Res.string.settings_theme_light
        ThemePreference.Dark -> Res.string.settings_theme_dark
    }