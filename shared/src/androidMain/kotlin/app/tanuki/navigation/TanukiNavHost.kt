package app.tanuki.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.tanuki.assets.designSystem.TanukiMotion
import app.tanuki.core.navigation.Destination
import app.tanuki.core.navigation.Bookmarks
import app.tanuki.core.navigation.Downloads
import app.tanuki.core.navigation.MangaDetail
import app.tanuki.core.navigation.Reader
import app.tanuki.core.navigation.SettingsAppearance
import app.tanuki.core.navigation.SettingsPrivacy
import app.tanuki.core.navigation.SettingsCategories
import app.tanuki.core.navigation.SettingsDownloads
import app.tanuki.core.navigation.SettingsSources
import app.tanuki.core.navigation.SourceBrowse
import app.tanuki.core.navigation.SourceCatalogGrid
import app.tanuki.core.navigation.GlobalSearch
import app.tanuki.core.navigation.TopLevelDestination
import app.tanuki.platform.rememberReducedMotion
import kotlin.reflect.typeOf
import kotlin.uuid.Uuid

/**
 * The Compose navigation graph: Android's navigation (CLAUDE.md → "Navigation: shared contract, native
 * rendering"). iOS renders the same destinations with a SwiftUI `TabView` + `NavigationStack`, through
 * [DestinationContent] for screens not yet migrated. It hosts the [app.tanuki.core.navigation.Destination]
 * set; each screen's route stays owned by [app.tanuki.core.navigation.Destination] so routes/params
 * cannot drift across platforms.
 *
 * A [Scaffold] wraps the graph with the [TanukiBottomBar], shown **only** while a [TopLevelDestination]
 * is active — pushed leaf screens (MangaDetail, Reader) hide the bar and take the full height. Each tab
 * keeps its own back stack across switches (`saveState`/`restoreState`), the standard bottom-nav pattern.
 *
 * Screen-scoped pushes are expressed by feature ViewModels as `NavEvent`s, surfaced to each `*Route` via
 * its `onNavigate`/`onBack` callbacks, and performed here against the [androidx.navigation.NavController].
 *
 * Transitions: peer-level tab switches crossfade; hierarchical pushes (MangaDetail, Reader) slide + fade
 * (durations/easings from [app.tanuki.assets.designSystem.TanukiMotion]). Both collapse to a short
 * crossfade when the OS reduce-motion setting is on (see [app.tanuki.platform.rememberReducedMotion]).
 */
@Composable
fun TanukiNavHost(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    // Both MangaDetail and Reader carry Uuid fields, so the graph needs the Uuid <-> String NavType.
    val uuidTypeMap = mapOf(typeOf<Uuid>() to UuidNavType)

    // Motion: peer-level tab switches crossfade (no natural direction to slide); hierarchical pushes
    // (MangaDetail, Reader) slide + fade. When "reduce motion" is on, every transition collapses to a
    // short crossfade with no spatial travel (see rememberReducedMotion / TanukiMotion.reducedMillis).
    val reduceMotion = rememberReducedMotion()
    val enterMillis = if (reduceMotion) TanukiMotion.reducedMillis else TanukiMotion.screenEnterMillis
    val exitMillis = if (reduceMotion) TanukiMotion.reducedMillis else TanukiMotion.screenExitMillis

    // The NavHost default, inherited by the top-level tabs: a plain crossfade.
    val tabEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        fadeIn(tween(enterMillis, easing = TanukiMotion.EaseEnter))
    }
    val tabExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        fadeOut(tween(exitMillis, easing = TanukiMotion.EaseExit))
    }

    // Leaf pushes: slide toward the deeper screen on navigate, back the other way on pop — plus a fade.
    // Under reduce-motion they fall back to the same crossfade as the tabs.
    val leafEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (reduceMotion) {
            fadeIn(tween(enterMillis))
        } else {
            slideIntoContainer(SlideDirection.Left, tween(enterMillis, easing = TanukiMotion.EaseEnter)) +
                fadeIn(tween(enterMillis, easing = TanukiMotion.EaseEnter))
        }
    }
    val leafExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (reduceMotion) {
            fadeOut(tween(exitMillis))
        } else {
            slideOutOfContainer(SlideDirection.Left, tween(exitMillis, easing = TanukiMotion.EaseExit)) +
                fadeOut(tween(exitMillis, easing = TanukiMotion.EaseExit))
        }
    }
    val leafPopEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (reduceMotion) {
            fadeIn(tween(enterMillis))
        } else {
            slideIntoContainer(SlideDirection.Right, tween(enterMillis, easing = TanukiMotion.EaseEnter)) +
                fadeIn(tween(enterMillis, easing = TanukiMotion.EaseEnter))
        }
    }
    val leafPopExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (reduceMotion) {
            fadeOut(tween(exitMillis))
        } else {
            slideOutOfContainer(SlideDirection.Right, tween(exitMillis, easing = TanukiMotion.EaseExit)) +
                fadeOut(tween(exitMillis, easing = TanukiMotion.EaseExit))
        }
    }

    // Every destination renders through DestinationContent, the mapping the iOS bridge shares; this host
    // contributes the back stack and the transitions.
    val screen: @Composable (Destination) -> Unit = { destination ->
        DestinationContent(
            destination = destination,
            onNavigate = { navController.navigate(it) },
            onBack = { navController.popBackStack() }
        )
    }

    val currentEntry by navController.currentBackStackEntryAsState()
    val currentDestination = currentEntry?.destination
    val onTopLevel =
        currentDestination?.hierarchy?.any { node ->
            TopLevelDestination.entries.any { node.hasRoute(it::class) }
        } == true

    // The bar renders through its slide-out animation, by which point `currentDestination` is already the
    // leaf — so hold the last top-level destination to keep the correct tab highlighted while it leaves.
    var barDestination by remember { mutableStateOf(currentDestination) }
    if (onTopLevel) barDestination = currentDestination

    Scaffold(
        modifier = modifier,
        // Screens manage their own top (status-bar) insets, as they did before the Scaffold; keeping the
        // Scaffold's content insets at zero means `innerPadding` reflects only the bottom bar's height, so
        // there's no double status-bar padding. The bar itself still pads for the system nav bar.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            // Slide the bar down + fade as leaves are pushed (and back up on pop), synced with the screen
            // transition, instead of popping out abruptly. Collapses to a fade under reduce-motion.
            AnimatedVisibility(
                visible = onTopLevel,
                enter =
                    if (reduceMotion) {
                        fadeIn(tween(enterMillis))
                    } else {
                        slideInVertically(tween(enterMillis, easing = TanukiMotion.EaseEnter)) { it } +
                            fadeIn(tween(enterMillis, easing = TanukiMotion.EaseEnter))
                    },
                exit =
                    if (reduceMotion) {
                        fadeOut(tween(exitMillis))
                    } else {
                        slideOutVertically(tween(exitMillis, easing = TanukiMotion.EaseExit)) { it } +
                            fadeOut(tween(exitMillis, easing = TanukiMotion.EaseExit))
                    }
            ) {
                TanukiBottomBar(
                    current = barDestination,
                    onSelect = { destination ->
                        navController.navigate(destination) {
                            // Preserve each tab's back stack; don't pile duplicate copies of a tab.
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.Home,
            modifier = Modifier.padding(innerPadding),
            // Default (inherited by the top-level tabs): crossfade.
            enterTransition = tabEnter,
            exitTransition = tabExit,
            popEnterTransition = tabEnter,
            popExitTransition = tabExit
        ) {
            composable<TopLevelDestination.Library> { screen(TopLevelDestination.Library) }
            composable<TopLevelDestination.Activity> { screen(TopLevelDestination.Activity) }
            composable<TopLevelDestination.Home> { screen(TopLevelDestination.Home) }
            composable<TopLevelDestination.Settings> { screen(TopLevelDestination.Settings) }
            composable<TopLevelDestination.Search> { screen(TopLevelDestination.Search) }
            composable<SettingsAppearance>(
                enterTransition = leafEnter,
                exitTransition = leafExit,
                popEnterTransition = leafPopEnter,
                popExitTransition = leafPopExit
            ) { screen(SettingsAppearance) }
            composable<SettingsPrivacy>(
                enterTransition = leafEnter,
                exitTransition = leafExit,
                popEnterTransition = leafPopEnter,
                popExitTransition = leafPopExit
            ) { screen(SettingsPrivacy) }
            composable<SettingsCategories>(
                enterTransition = leafEnter,
                exitTransition = leafExit,
                popEnterTransition = leafPopEnter,
                popExitTransition = leafPopExit
            ) { screen(SettingsCategories) }
            composable<SettingsDownloads>(
                enterTransition = leafEnter,
                exitTransition = leafExit,
                popEnterTransition = leafPopEnter,
                popExitTransition = leafPopExit
            ) { screen(SettingsDownloads) }
            composable<SettingsSources>(
                enterTransition = leafEnter,
                exitTransition = leafExit,
                popEnterTransition = leafPopEnter,
                popExitTransition = leafPopExit
            ) { screen(SettingsSources) }
            composable<Downloads>(
                enterTransition = leafEnter,
                exitTransition = leafExit,
                popEnterTransition = leafPopEnter,
                popExitTransition = leafPopExit
            ) { screen(Downloads) }
            composable<Bookmarks>(
                enterTransition = leafEnter,
                exitTransition = leafExit,
                popEnterTransition = leafPopEnter,
                popExitTransition = leafPopExit
            ) { screen(Bookmarks) }
            composable<SourceBrowse>(
                enterTransition = leafEnter,
                exitTransition = leafExit,
                popEnterTransition = leafPopEnter,
                popExitTransition = leafPopExit
            ) { entry -> screen(entry.toRoute<SourceBrowse>()) }
            composable<SourceCatalogGrid>(
                enterTransition = leafEnter,
                exitTransition = leafExit,
                popEnterTransition = leafPopEnter,
                popExitTransition = leafPopExit
            ) { entry -> screen(entry.toRoute<SourceCatalogGrid>()) }
            composable<GlobalSearch>(
                enterTransition = leafEnter,
                exitTransition = leafExit,
                popEnterTransition = leafPopEnter,
                popExitTransition = leafPopExit
            ) { entry -> screen(entry.toRoute<GlobalSearch>()) }
            composable<MangaDetail>(
                typeMap = uuidTypeMap,
                enterTransition = leafEnter,
                exitTransition = leafExit,
                popEnterTransition = leafPopEnter,
                popExitTransition = leafPopExit
            ) { entry -> screen(entry.toRoute<MangaDetail>()) }
            composable<Reader>(
                typeMap = uuidTypeMap,
                enterTransition = leafEnter,
                exitTransition = leafExit,
                popEnterTransition = leafPopEnter,
                popExitTransition = leafPopExit
            ) { entry -> screen(entry.toRoute<Reader>()) }
        }
    }
}