package app.tanuki.core.domain.settings.repository

import app.tanuki.core.domain.settings.model.ThemePreference
import kotlinx.coroutines.flow.Flow

/**
 * Persistence for app-level user settings that aren't tied to any single entity (unlike the Room-backed
 * repositories). Reads are reactive ([Flow]); writes are `suspend`.
 *
 * Backed by a key-value preference store (DataStore) rather than the relational database — these are
 * small, independent scalars the UI observes directly.
 */
interface SettingsRepository {
    /**
     * Observes the chosen [ThemePreference].
     *
     * @return A [Flow] emitting the stored preference, defaulting to [ThemePreference.System] until the
     *   user picks one. Re-emits on every change.
     */
    fun observeThemePreference(): Flow<ThemePreference>

    /**
     * Persists the chosen [ThemePreference].
     *
     * @param preference The theme to store.
     */
    suspend fun setThemePreference(preference: ThemePreference)
}