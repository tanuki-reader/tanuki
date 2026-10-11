package app.tanuki

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.viewModelFactory
import app.tanuki.core.domain.source.model.SourceListing
import app.tanuki.features.activity.viewmodel.ActivityViewModel
import app.tanuki.features.bookmarks.viewmodel.BookmarksViewModel
import app.tanuki.features.downloads.viewmodel.DownloadsViewModel
import app.tanuki.features.home.viewmodel.HomeViewModel
import app.tanuki.features.library.viewmodel.LibraryViewModel
import app.tanuki.features.manga.viewmodel.MangaViewModel
import app.tanuki.features.reader.viewmodel.ReaderViewModel
import app.tanuki.features.search.viewmodel.GlobalSearchViewModel
import app.tanuki.features.settings.appearance.viewmodel.AppearanceViewModel
import app.tanuki.features.settings.categories.viewmodel.CategoriesViewModel
import app.tanuki.features.settings.downloads.viewmodel.DownloadSettingsViewModel
import app.tanuki.features.settings.privacy.viewmodel.PrivacyViewModel
import app.tanuki.features.settings.sources.viewmodel.SourcesViewModel
import app.tanuki.features.settings.viewmodel.SettingsViewModel
import app.tanuki.features.source.viewmodel.SourceBrowseViewModel
import app.tanuki.features.source.viewmodel.SourceHomeViewModel
import dev.zacsweers.metrox.viewmodel.MetroViewModelFactory
import kotlin.reflect.KClass
import kotlin.uuid.Uuid

/**
 * One SwiftUI screen's ViewModel lifetime — the iOS counterpart of a Compose `NavBackStackEntry`'s
 * `ViewModelStore`. Swift's `ScreenModel` creates one per screen and calls [close] when the screen goes
 * away, which clears the ViewModel exactly as popping a destination does on Android (`onCleared`, then
 * `viewModelScope` cancelled).
 *
 * ViewModels come from the same [MetroViewModelFactory] that backs `metroViewModel()` in Compose, so a
 * ViewModel registered for Android (`@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())`
 * + `@ViewModelKey`) is resolvable here with no extra wiring.
 *
 * Swift can't name a `KClass`, so each screen gets a one-line accessor below (`scope.homeViewModel()`).
 */
class ScreenScope internal constructor(
    private val factory: MetroViewModelFactory
) {
    constructor() : this(appGraph.metroViewModelFactory)

    private val store = ViewModelStore()

    /** The scope's [VM], created on first request. Asking again returns the same instance. */
    internal fun <VM : ViewModel> viewModel(kClass: KClass<VM>): VM = ViewModelProvider.create(store, factory)[kClass]

    /**
     * The scope's assisted [VM] (one with route args), created on first request by [create] from the
     * same factory's manual assisted factories — what `assistedMetroViewModel { create(…) }` does in
     * Compose.
     */
    internal fun <VM : ViewModel> assistedViewModel(
        kClass: KClass<VM>,
        create: MetroViewModelFactory.() -> VM
    ): VM = ViewModelProvider.create(store, viewModelFactory { addInitializer(kClass) { factory.create() } })[kClass]

    /** Clears every ViewModel this scope created. */
    fun close() = store.clear()
}

// One accessor per SwiftUI screen. An assisted ViewModel (route args) takes those args and goes through
// [ScreenScope.assistedViewModel].

fun ScreenScope.homeViewModel(): HomeViewModel = viewModel(HomeViewModel::class)

fun ScreenScope.activityViewModel(): ActivityViewModel = viewModel(ActivityViewModel::class)

fun ScreenScope.libraryViewModel(): LibraryViewModel = viewModel(LibraryViewModel::class)

fun ScreenScope.settingsViewModel(): SettingsViewModel = viewModel(SettingsViewModel::class)

fun ScreenScope.appearanceViewModel(): AppearanceViewModel = viewModel(AppearanceViewModel::class)

fun ScreenScope.privacyViewModel(): PrivacyViewModel = viewModel(PrivacyViewModel::class)

fun ScreenScope.categoriesViewModel(): CategoriesViewModel = viewModel(CategoriesViewModel::class)

fun ScreenScope.downloadSettingsViewModel(): DownloadSettingsViewModel = viewModel(DownloadSettingsViewModel::class)

fun ScreenScope.downloadsViewModel(): DownloadsViewModel = viewModel(DownloadsViewModel::class)

fun ScreenScope.bookmarksViewModel(): BookmarksViewModel = viewModel(BookmarksViewModel::class)

fun ScreenScope.sourcesViewModel(): SourcesViewModel = viewModel(SourcesViewModel::class)

fun ScreenScope.globalSearchViewModel(query: String?): GlobalSearchViewModel =
    assistedViewModel(GlobalSearchViewModel::class) {
        createManuallyAssistedFactory(GlobalSearchViewModel.Factory::class)().create(query)
    }

fun ScreenScope.sourceHomeViewModel(sourceId: String): SourceHomeViewModel =
    assistedViewModel(SourceHomeViewModel::class) {
        createManuallyAssistedFactory(SourceHomeViewModel.Factory::class)().create(sourceId)
    }

fun ScreenScope.sourceCatalogViewModel(
    sourceId: String,
    listing: String?,
    query: String?
): SourceBrowseViewModel =
    assistedViewModel(SourceBrowseViewModel::class) {
        createManuallyAssistedFactory(SourceBrowseViewModel.Factory::class)()
            .create(sourceId, listing?.let(SourceListing::fromWireName), query)
    }

fun ScreenScope.mangaViewModel(mangaId: Uuid): MangaViewModel =
    assistedViewModel(MangaViewModel::class) {
        createManuallyAssistedFactory(MangaViewModel.Factory::class)().create(mangaId)
    }

fun ScreenScope.readerViewModel(
    mangaId: Uuid,
    chapterId: Uuid,
    page: Int?
): ReaderViewModel =
    assistedViewModel(ReaderViewModel::class) {
        createManuallyAssistedFactory(ReaderViewModel.Factory::class)().create(mangaId, chapterId, page)
    }
