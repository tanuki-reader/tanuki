package app.tanuki.features.settings.downloads.viewmodel

import app.tanuki.core.domain.category.model.Category
import app.tanuki.core.domain.download.model.DownloadSettings
import app.tanuki.core.domain.download.model.DownloadStorageUsage
import app.tanuki.core.domain.settings.model.AutoDownloadMode
import kotlin.uuid.Uuid

/**
 * Immutable UI state for the download settings screen.
 *
 * @property settings the stored rules — the source of truth for every control on the screen.
 * @property categories every library category, for the auto-download selection. Empty means the user has
 *   made none, which is why [canSelectCategories] exists: offering "selected categories" with nothing to
 *   select is a dead end.
 * @property usage what the downloads take on disk, or `null` until the first measurement lands. Distinct
 *   from a zero total on purpose — a walk of the downloads tree takes a moment, and flashing "0 B" at
 *   someone with a full device is the one wrong answer this screen can give.
 * @property busy `true` while the destructive sweep runs, so a second tap cannot fire a duplicate.
 * @property confirmingDeleteAll whether the delete-all confirmation is showing.
 */
data class DownloadSettingsState(
    val settings: DownloadSettings = DownloadSettings(),
    val categories: List<Category> = emptyList(),
    val usage: DownloadStorageUsage? = null,
    val busy: Boolean = false,
    val confirmingDeleteAll: Boolean = false,
) {
    /**
     * Whether the per-category mode is worth offering.
     *
     * The option is *hidden* rather than shown disabled when there are no categories. A disabled row
     * invites the user to work out how to enable it, and the answer — "go and create a library category"
     * — is on a different screen with no way of saying so from here.
     */
    val canSelectCategories: Boolean get() = categories.isNotEmpty()

    /** Whether the category picker is showing, which is exactly when the mode calls for one. */
    val showCategoryPicker: Boolean
        get() = settings.autoDownloadMode == AutoDownloadMode.SelectedCategories

    /** Whether there is anything on disk to delete — what gates the destructive action. */
    val hasDownloads: Boolean get() = usage?.isEmpty == false

    /** The auto-download category picker's rows: every category, with whether it is selected. */
    val categoryOptions: List<CategoryOption>
        get() = categories.map { CategoryOption(it.id, it.name.value, it.id in settings.autoDownloadCategories) }

    /**
     * The series the storage breakdown lists: the first [MAX_LISTED_SERIES]. A full list would be a
     * second library grid on a settings screen, and the question here is "what is taking the room",
     * which the largest few answer. Anything finer is a per-series decision, made on the series' page.
     */
    val listedSeries: List<StorageSeriesItem>
        get() =
            usage?.series.orEmpty().take(MAX_LISTED_SERIES).map {
                StorageSeriesItem(it.manga.title.value, it.chapterCount, it.bytes)
            }

    /** The picker is showing with nothing ticked, which downloads nothing: prompt for a pick. */
    val needsCategoryPick: Boolean get() = showCategoryPicker && settings.autoDownloadCategories.isEmpty()
}

/** How many series the storage breakdown lists before it stops. */
private const val MAX_LISTED_SERIES = 5

/**
 * One row of the storage breakdown. A plain [title] rather than the domain `Manga`, whose `Text` value
 * class reaches Swift as an untyped `id`.
 */
data class StorageSeriesItem(
    val title: String,
    val chapterCount: Int,
    val bytes: Long,
)

/** One row of the auto-download category picker. */
data class CategoryOption(
    val id: Uuid,
    val name: String,
    val selected: Boolean,
)
