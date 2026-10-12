package app.tanuki.navigation

import androidx.compose.runtime.Composable
import app.tanuki.core.navigation.Destination
import app.tanuki.core.navigation.Bookmarks
import app.tanuki.core.navigation.Downloads
import app.tanuki.core.navigation.MangaDetail
import app.tanuki.core.navigation.Reader
import app.tanuki.core.navigation.SettingsAppearance
import app.tanuki.core.navigation.SettingsCategories
import app.tanuki.core.navigation.SettingsDownloads
import app.tanuki.core.navigation.SettingsPrivacy
import app.tanuki.core.navigation.SettingsSources
import app.tanuki.core.navigation.SourceBrowse
import app.tanuki.core.navigation.SourceCatalogGrid
import app.tanuki.core.navigation.GlobalSearch
import app.tanuki.features.search.GlobalSearchRoute
import app.tanuki.core.navigation.TopLevelDestination
import app.tanuki.features.activity.ActivityRoute
import app.tanuki.features.bookmarks.BookmarksRoute
import app.tanuki.features.downloads.DownloadsRoute
import app.tanuki.features.home.HomeRoute
import app.tanuki.features.library.LibraryRoute
import app.tanuki.features.manga.MangaRoute
import app.tanuki.features.reader.ReaderRoute
import app.tanuki.features.settings.SettingsRoute
import app.tanuki.features.settings.appearance.AppearanceRoute
import app.tanuki.features.settings.categories.CategoriesRoute
import app.tanuki.features.settings.downloads.DownloadSettingsRoute
import app.tanuki.features.settings.privacy.PrivacyRoute
import app.tanuki.features.settings.sources.SourcesRoute
import app.tanuki.features.source.SourceHomeRoute
import app.tanuki.features.source.SourceRoute

/**
 * The Compose screen for [destination]: the one place a [Destination] is mapped to its `*Route`.
 *
 * [TanukiNavHost] renders it, owning the back stack and the transitions, and performs [onNavigate] (push
 * a leaf) and [onBack] (pop).
 *
 * Exhaustive by construction (`when` over a sealed type), so a new [Destination] fails to compile here
 * until it has a screen.
 */
@Composable
fun DestinationContent(
    destination: Destination,
    onNavigate: (Destination) -> Unit,
    onBack: () -> Unit
) {
    when (destination) {
        TopLevelDestination.Library -> LibraryRoute(onNavigate = onNavigate)
        TopLevelDestination.Home -> HomeRoute(onNavigate = onNavigate)
        TopLevelDestination.Activity -> ActivityRoute(onNavigate = onNavigate)
        TopLevelDestination.Settings -> SettingsRoute(onNavigate = onNavigate)
        TopLevelDestination.Search -> GlobalSearchRoute(initialQuery = null, onNavigate = onNavigate, onBack = null)
        is GlobalSearch -> GlobalSearchRoute(initialQuery = destination.query, onNavigate = onNavigate, onBack = onBack)
        SettingsAppearance -> AppearanceRoute(onBack = onBack)
        SettingsPrivacy -> PrivacyRoute(onBack = onBack)
        SettingsCategories -> CategoriesRoute(onBack = onBack)
        SettingsDownloads -> DownloadSettingsRoute(onNavigate = onNavigate, onBack = onBack)
        SettingsSources -> SourcesRoute(onBack = onBack)
        Downloads -> DownloadsRoute(onNavigate = onNavigate, onBack = onBack)
        Bookmarks -> BookmarksRoute(onNavigate = onNavigate, onBack = onBack)
        is SourceBrowse -> SourceHomeRoute(sourceId = destination.sourceId, onNavigate = onNavigate, onBack = onBack)
        is SourceCatalogGrid ->
            SourceRoute(
                sourceId = destination.sourceId,
                listing = destination.listing,
                query = destination.query,
                onNavigate = onNavigate,
                onBack = onBack
            )
        is MangaDetail -> MangaRoute(mangaId = destination.mangaId, onNavigate = onNavigate, onBack = onBack)
        is Reader ->
            ReaderRoute(
                mangaId = destination.mangaId,
                chapterId = destination.chapterId,
                page = destination.page,
                onBack = onBack
            )
    }
}
