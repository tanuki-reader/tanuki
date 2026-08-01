package app.tanuki.features.settings.viewmodel

import app.tanuki.core.domain.settings.model.ThemePreference

/**
 * Immutable UI state for the Settings **index** — the values previewed as row subtitles so each row
 * shows its current setting at a glance without opening it: the active [theme] (Appearance row), the
 * number of user [categoryCount] (Categories row), and how many sources are installed (Sources row).
 */
data class SettingsState(
    val theme: ThemePreference = ThemePreference.System,
    val categoryCount: Int = 0,
    val sourceCount: Int = 0
)