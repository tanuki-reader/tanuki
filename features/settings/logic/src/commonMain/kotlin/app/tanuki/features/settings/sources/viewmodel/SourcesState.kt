package app.tanuki.features.settings.sources.viewmodel

import app.tanuki.core.domain.download.model.SourceDownloadUsage

/**
 * Immutable UI state for the source-management screen: the sources on the device, and the ones that
 * could be put back.
 *
 * The two lists are pre-split here rather than in the composable because which side a source belongs on
 * *is* presentation — the screen shows installed sources and installable ones as two labelled sections,
 * and a row's only action follows from its section.
 *
 * @property loading `true` until the first catalog emission arrives; the screen shows a spinner rather
 *   than flashing an empty state at a user who does have sources.
 * @property installed sources whose bundle is on the device, ordered by name.
 * @property available sources that could be installed, ordered by name. Empty once everything the
 *   catalog offers is installed.
 * @property busyIds sources with an install or uninstall in flight — their row's action is disabled and
 *   shows progress, so a second tap can't queue a duplicate.
 * @property failedIds sources whose last action failed. The row reads it against its own section to say
 *   which action failed, and it clears the moment that source is acted on again.
 * @property pendingUsage what the source awaiting confirmation has downloaded, or `null` while that
 *   answer is still being read (and whenever no dialog is open). The remove dialog offers to delete
 *   those files only once this arrives non-empty, so the offer never appears without the count and size
 *   that make it answerable.
 * @property pendingRemoval the source the remove dialog is asking about, or `null` when it is closed.
 * @property removalDeletesDownloads whether the dialog's "also delete downloads" option is ticked.
 */
data class SourcesState(
    val loading: Boolean = true,
    val installed: List<SourceItem> = emptyList(),
    val available: List<SourceItem> = emptyList(),
    val busyIds: Set<String> = emptySet(),
    val failedIds: Set<String> = emptySet(),
    val pendingUsage: SourceDownloadUsage? = null,
    val pendingRemoval: SourceItem? = null,
    val removalDeletesDownloads: Boolean = false,
    /** Whether sensitive (adult) content is allowed — the toggle at the top of the screen. */
    val showSensitive: Boolean = false
)

/**
 * One source row.
 *
 * @property sourceId source-native id, and the row's stable list key.
 * @property name display name — already the fallback-resolved one from the domain, never blank.
 * @property lang language tag shown as the row's subtitle, or `null` when it isn't known (a source that
 *   has never been installed on this device reports nothing until it is).
 * @property iconUrl remote icon, or `null` for the lettered avatar.
 */
data class SourceItem(
    val sourceId: String,
    val name: String,
    val lang: String?,
    val iconUrl: String?
)
