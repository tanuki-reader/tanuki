package app.tanuki.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.ActivityTabIcon
import app.tanuki.assets.designSystem.component.HomeTabIcon
import app.tanuki.assets.designSystem.component.LibraryTabIcon
import app.tanuki.assets.designSystem.component.SettingsTabIcon
import app.tanuki.core.navigation.TopLevelDestination
import app.tanuki.shared.resources.Res
import app.tanuki.shared.resources.tab_activity
import app.tanuki.shared.resources.tab_home
import app.tanuki.shared.resources.tab_library
import app.tanuki.shared.resources.tab_settings
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Height of the icon+label content band, excluding the system safe-area inset added below it. */
private val BottomBarContentHeight = 72.dp

/**
 * The app's bottom navigation bar — the one place the [TopLevelDestination] tabs are rendered, driven by
 * [TopLevelDestination.entries] so adding a tab there adds it here. Only top-level destinations can reach
 * this (the type forbids leaf screens like `MangaDetail`), so a pushed sub-screen never leaks into the bar.
 *
 * Selection is derived from the live back stack ([current]) rather than stored, so the highlighted tab
 * always matches whichever tab's stack is active. [onSelect] performs the tab switch in the host.
 *
 * @param current the active [NavDestination] (from the nav controller's back stack), or `null` pre-graph.
 * @param onSelect invoked with the tapped tab; the host navigates to it, preserving per-tab back stacks.
 */
@Composable
internal fun TanukiBottomBar(
    current: NavDestination?,
    onSelect: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    // Material's NavigationBar renders an 80dp content band *plus* the system safe-area inset below it.
    // We want a shorter 72dp content band, so add the inset back on top of it rather than letting the
    // fixed height swallow the inset (which would squeeze the icons toward the top of the bar).
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    NavigationBar(
        modifier = modifier.height(BottomBarContentHeight + bottomInset),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        TopLevelDestination.entries.forEach { destination ->
            val selected = current.isOn(destination)
            val tint = if (selected) TanukiTheme.colors.accent else MaterialTheme.colorScheme.onSurfaceVariant
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(destination) },
                icon = { destination.TabIcon(tint) },
                label = {
                    Text(
                        text = stringResource(destination.labelRes()),
                        style = TanukiTheme.typography.label
                    )
                },
                colors =
                    NavigationBarItemDefaults.colors(
                        selectedTextColor = TanukiTheme.colors.accent,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        indicatorColor = TanukiTheme.colors.accentTint
                    )
            )
        }
    }
}

/** True when [destination]'s route is anywhere in this destination's hierarchy (i.e. this tab is active). */
private fun NavDestination?.isOn(destination: TopLevelDestination): Boolean =
    this?.hierarchy?.any { it.hasRoute(destination::class) } == true

/** The design-system glyph for each tab. */
@Composable
private fun TopLevelDestination.TabIcon(tint: Color) {
    when (this) {
        TopLevelDestination.Library -> LibraryTabIcon(tint)
        TopLevelDestination.Home -> HomeTabIcon(tint)
        TopLevelDestination.Activity -> ActivityTabIcon(tint)
        TopLevelDestination.Settings -> SettingsTabIcon(tint)
    }
}

/** The localized label resource for each tab. */
private fun TopLevelDestination.labelRes(): StringResource =
    when (this) {
        TopLevelDestination.Library -> Res.string.tab_library
        TopLevelDestination.Home -> Res.string.tab_home
        TopLevelDestination.Activity -> Res.string.tab_activity
        TopLevelDestination.Settings -> Res.string.tab_settings
    }