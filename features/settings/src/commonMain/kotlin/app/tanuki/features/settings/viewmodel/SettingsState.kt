package app.tanuki.features.settings.viewmodel

import app.tanuki.core.domain.settings.model.ThemePreference

/**
 * Immutable UI state for the Settings **index** — the values previewed as row subtitles so each row
 * shows its current setting at a glance without opening it: the active [theme] (Appearance row), the
 * number of user [categoryCount] (Categories row), how many sources are installed (Sources row), and
 * whether crash reporting is on (Privacy row), and how many chapters the download queue is holding
 * (Downloads row).
 *
 * [downloadQueueCount] is the only number in the app that says the queue exists at all. It counts what
 * the queue *screen* shows — waiting, downloading and failed — rather than only the work still owed, so
 * a queue that has stopped on an error does not read as empty from here.
 */
data class SettingsState(
    val theme: ThemePreference = ThemePreference.System,
    val categoryCount: Int = 0,
    val sourceCount: Int = 0,
    val crashReportingEnabled: Boolean = false,
    val downloadQueueCount: Int = 0
)