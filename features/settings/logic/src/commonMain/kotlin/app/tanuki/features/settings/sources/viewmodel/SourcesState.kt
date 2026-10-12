package app.tanuki.features.settings.sources.viewmodel

import app.tanuki.core.domain.download.model.SourceDownloadUsage
import app.tanuki.core.domain.source.model.InstallProgress
import app.tanuki.core.domain.source.model.SourceCompatibility
import app.tanuki.core.domain.source.model.SourceRepoError

/**
 * Immutable UI state for the Sources screen (design system: `SourcesScreen`): installed sources and the
 * ones the user's repos offer, filtered by language and by the adult-content setting.
 *
 * @property loading `true` until the first catalog emission arrives; the screen shows a spinner rather
 *   than flashing an empty state at a user who does have sources.
 * @property installed sources whose bundle is on the device, by name, language variants together.
 * @property available sources the repos offer that aren't installed, in the same order.
 * @property languages every language either list has, with how many sources each, for the filter chips.
 *   Empty when there is only one language, since a filter with one choice filters nothing.
 * @property selectedLanguage the language tag the lists are filtered to, or `null` for all.
 * @property showRepo whether rows name their repo: only when more than one repo is added.
 * @property repoCount how many repos are added. With none and nothing installed, the screen is the
 *   first-run empty state whose one action is Add repo.
 * @property pendingUsage what the source awaiting removal has downloaded, or `null` while that answer is
 *   still being read (and whenever no dialog is open). The remove dialog offers to delete those files
 *   only once this arrives non-empty.
 * @property pendingRemoval the source the remove dialog is asking about, or `null` when it is closed.
 * @property removalDeletesDownloads whether the dialog's "also delete downloads" option is ticked.
 */
data class SourcesState(
    val loading: Boolean = true,
    val installed: List<SourceItem> = emptyList(),
    val available: List<SourceItem> = emptyList(),
    val languages: List<LanguageOption> = emptyList(),
    val selectedLanguage: String? = null,
    val showRepo: Boolean = false,
    val repoCount: Int = 0,
    val pendingUsage: SourceDownloadUsage? = null,
    val pendingRemoval: SourceItem? = null,
    val removalDeletesDownloads: Boolean = false
)

/** One language chip. [tag] is the source's BCP-47 tag; each platform words it in the user's language. */
data class LanguageOption(
    val tag: String,
    val count: Int
)

/**
 * One source row (design system: `SourceRow`), carrying its whole install life cycle.
 *
 * @property sourceId source-native id, and the row's stable list key.
 * @property name display name, verbatim from the source or its repo.
 * @property lang BCP-47 language tag, or `null` when it isn't known.
 * @property iconUrl remote icon, or `null` for the lettered avatar.
 * @property version the installed version, or the one on offer for an installable source.
 * @property repoName the repo offering it; shown only when [SourcesState.showRepo].
 * @property nsfw adult-only: the row carries an `18+` tag.
 * @property compatibility whether the version on offer runs on this app. An incompatible row is listed,
 *   so the user knows it exists, but offers no action.
 * @property progress the install in flight, or `null`.
 * @property installFailure the step the last install failed at, or `null`; the row offers Retry.
 * @property removing an uninstall is in flight.
 * @property removeFailed the last uninstall failed.
 */
data class SourceItem(
    val sourceId: String,
    val name: String,
    val lang: String?,
    val iconUrl: String?,
    val version: Int? = null,
    val repoName: String? = null,
    val nsfw: Boolean = false,
    val compatibility: SourceCompatibility = SourceCompatibility.Compatible,
    val progress: InstallProgress? = null,
    val installFailure: SourceRepoError? = null,
    val removing: Boolean = false,
    val removeFailed: Boolean = false
)
