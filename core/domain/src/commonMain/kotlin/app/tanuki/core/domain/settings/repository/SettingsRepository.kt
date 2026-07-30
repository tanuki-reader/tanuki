package app.tanuki.core.domain.settings.repository

import app.tanuki.core.domain.settings.model.LibraryUpdateInterval
import app.tanuki.core.domain.settings.model.ThemePreference
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

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

    /**
     * Observes how often the library checks its sources for new chapters.
     *
     * @return A [Flow] emitting the stored interval, defaulting to [LibraryUpdateInterval.Default]
     *   until the user picks one.
     */
    fun observeLibraryUpdateInterval(): Flow<LibraryUpdateInterval>

    /**
     * Persists how often the library should check for new chapters.
     *
     * @param interval The interval to store.
     */
    suspend fun setLibraryUpdateInterval(interval: LibraryUpdateInterval)

    /**
     * When the library last finished checking its sources, or `null` if it never has.
     *
     * A one-shot read, not a [Flow]: its only reader is the check deciding whether an update is due,
     * which asks once per run. Nothing renders it.
     */
    suspend fun getLastLibraryUpdate(): Instant?

    /**
     * Records that the library finished checking its sources.
     *
     * @param at When the run finished.
     */
    suspend fun setLastLibraryUpdate(at: Instant)
}