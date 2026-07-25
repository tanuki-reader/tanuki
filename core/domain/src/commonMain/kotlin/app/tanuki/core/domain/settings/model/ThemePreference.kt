package app.tanuki.core.domain.settings.model

/**
 * The user's chosen app theme. Resolved to a concrete light/dark scheme at the UI root:
 * [System] defers to the OS setting, while [Light] and [Dark] force a dialect regardless of it.
 *
 * Persisted by the settings layer (see [app.tanuki.core.domain.settings.repository.SettingsRepository]);
 * [System] is the default when nothing has been stored yet.
 */
enum class ThemePreference {
    System,
    Light,
    Dark
}