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

    @Serializable
    data object Browser : TopLevelDestination

    @Serializable
    data object Activity : TopLevelDestination

    @Serializable
    data object Settings : TopLevelDestination

    companion object {
        /** Ordered tabs the bottom bar renders. Add a tab here and it appears in the bar. */
        val entries: List<TopLevelDestination> = listOf(Library, Browser, Activity, Settings)
    }
}

/**
 * A specific manga's detail screen. Pushed onto whichever tab's stack is active (Library, Browser…),
 * so it carries its own id rather than belonging to one tab.
 */
@Serializable
data class MangaDetail(
    val mangaId: Uuid
) : Destination

/** The reader for a specific chapter. [mangaId] is carried so the reader can page across chapters. */
@Serializable
data class Reader(
    val mangaId: Uuid,
    val chapterId: Uuid
) : Destination