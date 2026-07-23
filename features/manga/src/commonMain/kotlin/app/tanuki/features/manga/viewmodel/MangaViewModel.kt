package app.tanuki.features.manga.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tanuki.core.domain.category.usecase.ObserveCategories
import app.tanuki.core.domain.category.usecase.SetMangaCategories
import app.tanuki.core.domain.chapter.model.Chapter
import app.tanuki.core.domain.chapter.usecase.ObserveChapters
import app.tanuki.core.domain.chapter.usecase.SyncSourceChapters
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.manga.usecase.AddToLibrary
import app.tanuki.core.domain.manga.usecase.ObserveManga
import app.tanuki.core.domain.manga.usecase.RemoveMangaFromLibrary
import app.tanuki.core.domain.manga.usecase.SyncSourceMangaDetails
import app.tanuki.core.domain.source.usecase.ObserveSources
import app.tanuki.core.navigation.NavEvent
import app.tanuki.core.navigation.Reader
import app.tanuki.features.manga.viewmodel.MangaState.Content
import app.tanuki.features.manga.viewmodel.MangaState.Loading
import app.tanuki.features.manga.viewmodel.MangaState.MangaCategoryChip
import app.tanuki.features.manga.viewmodel.MangaState.NotFound
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

/**
 * Presentation logic for the Manga Detail screen. Compose-free (per CLAUDE.md → MVVM): it exposes state
 * as a [StateFlow] and screen-scoped navigation as one-shot [NavEvent]s, so the same logic drives the
 * shared Compose UI on both Android and iOS and is unit-testable without a Compose runtime.
 *
 * Unlike the Library ViewModel, this one is **assisted-injected**: it needs the [mangaId] the navigation
 * route carried. Metro builds it through [Factory] (a [ManualViewModelAssistedFactory] registered into the
 * app's factory multibinding), which the UI resolves with `assistedMetroViewModel { create(mangaId) }`.
 * The instance is scoped to the destination's `NavBackStackEntry`, so it lives and dies with the screen.
 *
 * The pipeline is reactive: [ObserveManga] and [ObserveChapters] are combined into [MangaState], so the
 * screen re-derives whenever the manga or its chapters change. A `null` manga (deleted row / bad deep
 * link) surfaces as [MangaState.NotFound].
 *
 * Chapters are refreshed from the source on open ([SyncSourceChapters]) but nothing waits on it: the
 * cached list renders immediately and the fetched one replaces it through the same reactive pipeline
 * when it lands.
 */
@AssistedInject
class MangaViewModel(
    @Assisted private val mangaId: Uuid,
    observeManga: ObserveManga,
    observeChapters: ObserveChapters,
    observeCategories: ObserveCategories,
    observeSources: ObserveSources,
    private val setMangaCategories: SetMangaCategories,
    private val addToLibrary: AddToLibrary,
    private val removeMangaFromLibrary: RemoveMangaFromLibrary,
    private val syncSourceChapters: SyncSourceChapters,
    private val syncSourceMangaDetails: SyncSourceMangaDetails,
    private val dispatcher: DispatcherProvider
) : ViewModel() {
    val uiState: StateFlow<MangaState>
        field = MutableStateFlow<MangaState>(Loading)

    /**
     * Screen-scoped navigation intents (open the reader for a chapter or go back). One-shot and buffered
     * so a tap emitted before the host collects isn't lost; the host maps these onto the back stack.
     */
    private val _navEvents = Channel<NavEvent>(Channel.BUFFERED)
    val navEvents: Flow<NavEvent> = _navEvents.receiveAsFlow()

    /** Chapter ordering: `true` = ascending by chapter number (1 → N), `false` = descending. */
    private val chapterSortAscending = MutableStateFlow(false)

    init {
        combine(
            observeManga(mangaId),
            observeChapters(mangaId),
            chapterSortAscending,
            observeCategories(),
            observeSources()
        ) { manga, chapters, ascending, categories, sources ->
            if (manga == null) {
                NotFound
            } else {
                val ordered = chapters.sortedByNumber(ascending)
                val categoryChips = categories.map { MangaCategoryChip(it, mangaId in it.associatedMangas) }
                val source = sources.firstOrNull { it.id == manga.source }
                Content(manga, ordered, resumeChapter(ordered), ascending, categoryChips, source)
            }
        }.flowOn(dispatcher.default)
            .onEach { uiState.value = it }
            .launchIn(viewModelScope)

        refreshChapters()
        refreshDetails()
    }

    /**
     * Refreshes this manga's chapters from its source, in the background.
     *
     * Fire-and-forget by design — the screen never blocks on it, because [ObserveChapters] has already
     * emitted whatever is cached and the refreshed rows arrive through that same flow. A failure
     * (offline, source error) therefore leaves the cached list on screen rather than emptying it.
     *
     * The failure is currently only logged: surfacing it needs a refresh affordance on the screen
     * (a pull-to-refresh and an inline error), which doesn't exist yet. `println` matches the stopgap
     * used elsewhere until a logging abstraction lands in `core/`.
     */
    private fun refreshChapters() {
        viewModelScope.launch {
            runCatching { withContext(dispatcher.io) { syncSourceChapters(mangaId) } }
                .onFailure { error -> println("[manga] Chapter refresh failed for $mangaId: ${error.message}") }
        }
    }

    /**
     * Fills in the fields a browse listing doesn't carry (description, author, full status), in the
     * background — see [SyncSourceMangaDetails] for why the catalog deliberately omits them.
     *
     * A separate coroutine from [refreshChapters], not a step inside it: they hit different source
     * endpoints and neither is worth delaying for the other, so a slow chapter feed can't hold up the
     * description and a source that implements one but not the other still delivers what it has.
     * Fire-and-forget and failure-tolerant for the same reason: the cached row stays on screen.
     */
    private fun refreshDetails() {
        viewModelScope.launch {
            runCatching { withContext(dispatcher.io) { syncSourceMangaDetails(mangaId) } }
                .onFailure { error -> println("[manga] Detail refresh failed for $mangaId: ${error.message}") }
        }
    }

    /**
     * A category chip was toggled in the sheet — add or remove this manga's membership and persist the new
     * set (live-apply). The reactive pipeline re-emits with the updated [Content.categories].
     *
     * Category memberships only exist for library titles (the join table foreign-keys to the library
     * row), so assigning a category to a manga that isn't in the library **implicitly adds it first** —
     * one tap organizes and adds, matching the upstream (Mihon) behavior. Toggling a category back off on
     * a not-yet-added manga (ending with no categories) leaves it untouched rather than adding it.
     */
    fun onToggleCategory(categoryId: Uuid) {
        val content = uiState.value as? Content ?: return
        val members =
            content.categories.filterTo(mutableSetOf()) { it.isMember }.mapTo(mutableSetOf()) { it.category.id }
        if (categoryId in members) members.remove(categoryId) else members.add(categoryId)
        viewModelScope.launch {
            withContext(dispatcher.io) {
                if (!content.inLibrary && members.isNotEmpty()) addToLibrary(mangaId)
                setMangaCategories(mangaId, members)
            }
        }
    }

    /** The Info tab's "Add to library" action was tapped — record library membership (no-op if already in). */
    fun onAddToLibrary() {
        val content = uiState.value as? Content ?: return
        if (content.inLibrary) return
        viewModelScope.launch {
            withContext(dispatcher.io) {
                addToLibrary(mangaId)
            }
        }
    }

    /**
     * The Info tab's "Remove from library" action was tapped — drop the library membership and every
     * category membership. The screen stays open (a removed manga can still be browsed/read); the
     * reactive pipeline re-emits with `inLibrary = false`, flipping the button back to "Add to library".
     */
    fun onRemoveFromLibrary() {
        val content = uiState.value as? Content ?: return
        if (!content.inLibrary) return
        viewModelScope.launch {
            withContext(dispatcher.io) {
                removeMangaFromLibrary(mangaId)
            }
        }
    }

    /** The chapter list's sort control was tapped — flip between ascending and descending order. */
    fun onToggleChapterSort() {
        chapterSortAscending.value = !chapterSortAscending.value
    }

    /** The back arrow was tapped — pop this screen off the back stack. */
    fun onBack() {
        _navEvents.trySend(NavEvent.NavBack)
    }

    /** A chapter row was tapped — open the reader at that chapter. */
    fun onChapterClick(chapterId: Uuid) {
        _navEvents.trySend(NavEvent.NavTo(Reader(mangaId, chapterId)))
    }

    /** The primary read/resume action was tapped — open the reader at the [Content.resumeChapter]. */
    fun onReadClick() {
        val content = uiState.value as? Content ?: return
        val target = content.resumeChapter ?: return
        _navEvents.trySend(NavEvent.NavTo(Reader(mangaId, target.id)))
    }

    /**
     * The chapter the "Read" action resumes at: the highest-numbered chapter the user has already read
     * (continue where they left off), or — when nothing is read yet — the lowest-numbered chapter (start
     * from the beginning). `null` only when there are no chapters. Chapters with an unrecognized number
     * are ranked last so a numbered chapter always wins.
     */
    private fun resumeChapter(chapters: List<Chapter>): Chapter? {
        val lastRead =
            chapters
                .filter { it.progress?.alreadyRead == true }
                .maxByOrNull { it.chapterNumber?.value ?: Double.NEGATIVE_INFINITY }
        return lastRead ?: chapters.minByOrNull { it.chapterNumber?.value ?: Double.MAX_VALUE }
    }

    /** Orders chapters by number; unrecognized-number chapters sort last (ascending) either way. */
    private fun List<Chapter>.sortedByNumber(ascending: Boolean): List<Chapter> {
        val byNumber = sortedWith(compareBy(nullsLast()) { it.chapterNumber })
        return if (ascending) byNumber else byNumber.asReversed()
    }

    /**
     * Assisted factory for [MangaViewModel], binding the runtime [mangaId] the route supplies. Registered
     * into the app's manual-assisted-factory multibinding so `assistedMetroViewModel` can resolve it.
     */
    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    fun interface Factory : ManualViewModelAssistedFactory {
        fun create(mangaId: Uuid): MangaViewModel
    }
}