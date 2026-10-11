package app.tanuki.core.domain.settings.repository

import app.tanuki.core.domain.library.model.LibraryPreferences
import app.tanuki.core.domain.settings.model.AutoDownloadMode
import app.tanuki.core.domain.settings.model.DeleteAfterReading
import app.tanuki.core.domain.settings.model.LibraryGridSize
import app.tanuki.core.domain.settings.model.LibraryUpdateInterval
import app.tanuki.core.domain.settings.model.ThemePreference
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant
import kotlin.uuid.Uuid

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
     * The anonymous identifier of this install, created on first read and stable afterwards.
     *
     * Crash reporting attaches it so a report can tell one user crashing many times from many users
     * crashing once. It is random and generated on the device: it names no account, no email and no
     * device, and clearing the app's data replaces it with a new one.
     */
    suspend fun getOrCreateInstallId(): String

    /**
     * Replaces the install id with a new random one, so later reports can't be linked to earlier ones.
     *
     * @return The new id, which [getOrCreateInstallId] returns from now on.
     */
    suspend fun resetInstallId(): String

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
     * Observes whether the user has paused the download queue.
     *
     * A single app preference rather than a per-chapter state, deliberately: pausing must keep every
     * chapter's place in line, and a `Paused` row state would mean "resume all" rewrote every row —
     * losing the ordering the pause was supposed to preserve (see
     * [app.tanuki.core.domain.download.model.DownloadState]).
     *
     * @return A [Flow] emitting the stored choice, defaulting to `false`. An absent or unreadable value
     *   has to mean "not paused": the failure mode of guessing the other way is a queue that silently
     *   never runs again, with nothing on screen explaining why.
     */
    fun observeDownloadsPaused(): Flow<Boolean>

    /**
     * Pauses or resumes the download queue.
     *
     * @param paused `true` to stop the queue after the page in flight.
     */
    suspend fun setDownloadsPaused(paused: Boolean)

    /**
     * Observes which newly-found chapters are downloaded without being asked for.
     *
     * @return A [Flow] emitting the stored mode, defaulting to [AutoDownloadMode.Off]. The default is
     *   the whole point: this setting spends storage and mobile data on the user's behalf, so an absent
     *   or unreadable value has to mean "don't".
     */
    fun observeAutoDownloadMode(): Flow<AutoDownloadMode>

    /**
     * Observes the categories [AutoDownloadMode.SelectedCategories] applies to.
     *
     * Kept independently of the mode so switching auto-download off and on again does not lose the
     * selection. Ids of categories that have since been deleted are harmless — nothing matches them.
     */
    fun observeAutoDownloadCategories(): Flow<Set<Uuid>>

    /**
     * Records the auto-download rule.
     *
     * Both halves are written together because they are one decision: a mode without the categories it
     * refers to is a rule that cannot be applied.
     *
     * @param mode Which chapters to download automatically.
     * @param categories The categories [AutoDownloadMode.SelectedCategories] applies to.
     */
    suspend fun setAutoDownload(
        mode: AutoDownloadMode,
        categories: Set<Uuid>
    )

    /**
     * Observes whether the download queue may only run on an unmetered connection.
     *
     * @return A [Flow] defaulting to `false`. Not `true`: a queue that silently never runs because the
     *   user is on mobile data, with nothing on screen explaining why, is a worse first experience than
     *   one that uses the data they asked it to.
     */
    fun observeDownloadOnWifiOnly(): Flow<Boolean>

    /** @param wifiOnly `true` to hold the queue until an unmetered connection is available. */
    suspend fun setDownloadOnWifiOnly(wifiOnly: Boolean)

    /**
     * Observes what happens to a downloaded chapter once it has been read.
     *
     * @return A [Flow] defaulting to [DeleteAfterReading.Off] — deleting a user's files is never a safe
     *   default, and an unreadable store must not start doing it.
     */
    fun observeDeleteAfterReading(): Flow<DeleteAfterReading>

    /** @param setting How much read-chapter slack to keep on the device. */
    suspend fun setDeleteAfterReading(setting: DeleteAfterReading)

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

    /**
     * The library's remembered filter and sort. Absent or unreadable values read as their defaults, so
     * a store from an older or newer build degrades to the plain library rather than failing.
     */
    fun observeLibraryPreferences(): Flow<LibraryPreferences>

    /**
     * Rewrites the library's filter and sort through [transform], applied to the stored value in one
     * atomic edit.
     */
    suspend fun updateLibraryPreferences(transform: (LibraryPreferences) -> LibraryPreferences)

    /** How big library covers are drawn; [LibraryGridSize.Medium] when unset. */
    fun observeLibraryGridSize(): Flow<LibraryGridSize>

    suspend fun setLibraryGridSize(size: LibraryGridSize)
}
