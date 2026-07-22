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

/** The reader for a specific chapter. [mangaId] is carried so the reader can page across chapters. */
@Serializable
data class Reader(
    val mangaId: Uuid,
    val chapterId: Uuid
) : Destination