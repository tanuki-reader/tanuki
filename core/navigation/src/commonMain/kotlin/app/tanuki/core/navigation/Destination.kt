package app.tanuki.core.navigation

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * Every screen the app can navigate to — the single source of truth the one shared Compose
 * `NavHost` (in `:shared`) renders over, identically on Android and iOS.
 *
 * The set is split into two tiers:
 *  - [TopLevelDestination] — the root tabs, and the ONLY destinations the bottom bar shows.
 *  - the sub-screens below — pushed onto the active tab's back stack; never shown in the bottom bar.
 *
 * The split is enforced by the type system: a bottom-bar API typed to [TopLevelDestination] simply
 * cannot accept a [MangaDetail] or [Reader], so leaf screens can't leak into the tab bar.
 */
sealed interface Destination

/**
 * Root-level destinations — these are the bottom-bar tabs. Each owns its own back stack at runtime,
 * onto which the sub-screens below are pushed.
 */
sealed interface TopLevelDestination : Destination {
    @Serializable
    data object Library : TopLevelDestination

    /**
     * The Home tab — the app's landing surface. Hosts a "For You" view (activity over the user's
     * library) and a "Sources" view (installed sources, grouped by language). Its per-source manga
     * browser is a separate leaf, [SourceBrowse], pushed onto this tab's stack.
     */
    @Serializable
    data object Home : TopLevelDestination

    @Serializable
    data object Activity : TopLevelDestination

    @Serializable
    data object Settings : TopLevelDestination

    companion object {
        /** Ordered tabs the bottom bar renders. Add a tab here and it appears in the bar. */
        val entries: List<TopLevelDestination> = listOf(Library, Home, Activity, Settings)
    }
}

/**
 * A specific manga's detail screen. Pushed onto whichever tab's stack is active (Library, Home…),
 * so it carries its own id rather than belonging to one tab.
 */
@Serializable
data class MangaDetail(
    val mangaId: Uuid
) : Destination

/**
 * A single source's browse screen — its catalog, searchable and paged, with the source's declared
 * listings as tabs. A leaf pushed onto the Home tab's stack from its source list.
 *
 * Identified by the **source-native** [sourceId] string (e.g. `"mangadex"`), not a local `Uuid`: a
 * source is addressed by its own id everywhere above persistence (see [app.tanuki.core.navigation]
 * note on ids), and this route only needs to tell the loader which source to browse.
 */
@Serializable
data class SourceBrowse(
    val sourceId: String
) : Destination

/**
 * One source's full, paged catalog — what a Source home rail's "See all" or a search opens. [listing] is
 * a [app.tanuki.core.domain.source.model.SourceListing.wireName] (`null` = the default catalog) and
 * [query] a search to run; a string rather than the enum so the route stays a plain serializable value.
 */
@Serializable
data class SourceCatalogGrid(
    val sourceId: String,
    val listing: String? = null,
    val query: String? = null
) : Destination

/** The reader for a specific chapter. [mangaId] is carried so the reader can page across chapters. */
@Serializable
data class Reader(
    val mangaId: Uuid,
    val chapterId: Uuid
) : Destination

/**
 * Appearance settings — theme and other look-and-feel options. A leaf pushed onto the Settings tab's
 * stack from the settings index.
 */
@Serializable
data object SettingsAppearance : Destination

/**
 * Privacy settings — what, if anything, the app sends off the device. A leaf pushed onto the Settings
 * tab's stack from the settings index.
 *
 * A screen rather than a switch on the index: consent is only meaningful if the user can see what is
 * collected before giving it, and that explanation needs more room than a row subtitle.
 */
@Serializable
data object SettingsPrivacy : Destination

/**
 * Category management — create/delete the library shelves manga can be organized into. A leaf pushed
 * onto the Settings tab's stack from the settings index.
 */
@Serializable
data object SettingsCategories : Destination

/**
 * The download queue — what is waiting, what is being fetched, and what stopped with an error.
 *
 * A leaf rather than a tab: the bottom bar stays Library/Home/Activity/Settings, and a queue is
 * something the user visits when they want to check on it, not a place they read from. It is reachable
 * from the Settings index and from a series' own download menu while it has something queued, so it is
 * pushed onto whichever tab the user was already in rather than belonging to one.
 */
@Serializable
data object Downloads : Destination

/**
 * Download settings — the rules the download subsystem follows on its own, and what it is costing.
 *
 * Distinct from [Downloads], and deliberately reachable from the same place: a queue you *watch* and a
 * set of rules you *configure* are different errands, and collapsing them would make the common one
 * (checking on a download) scroll past the rare one (changing when downloads happen).
 */
@Serializable
data object SettingsDownloads : Destination

/**
 * Source management — install and uninstall the extensions the app browses through. A leaf pushed onto
 * the Settings tab's stack from the settings index.
 *
 * Deliberately not part of the Home tab's source list: that list is a browse surface whose every row is
 * a tap target, and a destructive action does not belong next to one. Managing sources is a separate,
 * intentional trip.
 */
@Serializable
data object SettingsSources : Destination