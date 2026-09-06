package app.tanuki.core.domain.download.model

import app.tanuki.core.domain.settings.model.AutoDownloadMode
import app.tanuki.core.domain.settings.model.DeleteAfterReading
import kotlin.uuid.Uuid

/**
 * Every download preference in one value, because they are read together and never in isolation.
 *
 * Four separate flows for four settings would make the settings screen combine them back and give the
 * auto-download rule two places to look — the mode and the category set are one decision expressed in
 * two fields, and [downloadsCategory] is the only correct way to ask it.
 *
 * @property autoDownloadMode Which new chapters are downloaded without being asked for.
 * @property autoDownloadCategories The categories [AutoDownloadMode.SelectedCategories] applies to.
 *   Ignored by every other mode; kept across a switch to `Off` and back, so turning the feature off for
 *   a week does not lose the selection.
 * @property onWifiOnly Whether the queue may run on a metered connection.
 * @property deleteAfterReading What happens to a chapter once it has been read.
 */
data class DownloadSettings(
    val autoDownloadMode: AutoDownloadMode = AutoDownloadMode.Off,
    val autoDownloadCategories: Set<Uuid> = emptySet(),
    val onWifiOnly: Boolean = false,
    val deleteAfterReading: DeleteAfterReading = DeleteAfterReading.Off,
) {
    /**
     * Whether a series in [categories] should have its new chapters downloaded automatically.
     *
     * An empty selection under [AutoDownloadMode.SelectedCategories] downloads nothing. "Download for
     * these categories" with none chosen cannot mean "all of them", and guessing the generous reading
     * would spend a user's data on a setting they had half-configured.
     */
    fun downloadsCategory(categories: Set<Uuid>): Boolean =
        when (autoDownloadMode) {
            AutoDownloadMode.Off -> false
            AutoDownloadMode.AllLibrary -> true
            AutoDownloadMode.SelectedCategories -> categories.any { it in autoDownloadCategories }
        }
}
