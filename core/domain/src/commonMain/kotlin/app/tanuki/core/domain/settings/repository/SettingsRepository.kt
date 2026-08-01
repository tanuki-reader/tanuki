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
     * Observes whether the user has opted in to sending crash reports.
     *
     * @return A [Flow] emitting the stored choice, defaulting to `false` until the user opts in. The
     *   default is the whole point: diagnostics must never be collected from someone who has not agreed,
     *   so an absent or unreadable value has to mean "off", never "on".
     */
    fun observeCrashReportingEnabled(): Flow<Boolean>

    /**
     * Records the user's crash-reporting choice.
     *
     * @param enabled `true` when the user opts in.
     */
    suspend fun setCrashReportingEnabled(enabled: Boolean)

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

    /**
     * Source-native ids the user has explicitly uninstalled.
     *
     * A record of *intent*, not of state — what is actually installed is the bundle store's business.
     * It exists because the app seeds its bundled sources on launch, and without it every uninstall
     * would be silently undone the next time the app started. Anything installed again drops out of
     * this set.
     *
     * A one-shot read, not a [Flow]: its only reader is that launch-time seeding, which asks once
     * per run. Nothing renders it.
     */
    suspend fun getRemovedSourceIds(): Set<String>

    /**
     * Records whether the user has uninstalled [sourceId], adding it to (or clearing it from)
     * [getRemovedSourceIds].
     *
     * @param sourceId The source-native id.
     * @param removed `true` on uninstall, `false` when it is installed again.
     */
    suspend fun setSourceRemoved(
        sourceId: String,
        removed: Boolean
    )
}