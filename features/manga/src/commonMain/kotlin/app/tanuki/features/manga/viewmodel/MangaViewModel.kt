package app.tanuki.features.manga.viewmodel

import androidx.lifecycle.viewModelScope
import app.tanuki.core.domain.category.usecase.ObserveCategories
import app.tanuki.core.domain.category.usecase.SetMangaCategories
import app.tanuki.core.domain.chapter.model.Chapter
import app.tanuki.core.domain.chapter.model.isHiddenBy
import app.tanuki.core.domain.chapter.usecase.ObserveChapters
import app.tanuki.core.domain.chapter.usecase.SetChaptersRead
import app.tanuki.core.domain.chapter.usecase.SyncSourceChapters
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.download.model.DownloadState
import app.tanuki.core.domain.download.usecase.CancelChapterDownloads
import app.tanuki.core.domain.download.usecase.DeleteChapterDownloads
import app.tanuki.core.domain.download.usecase.DeleteMangaDownloads
import app.tanuki.core.domain.download.usecase.EnqueueChapterDownloads
import app.tanuki.core.domain.download.usecase.EnqueueNextChapters
import app.tanuki.core.domain.download.usecase.ObserveChapterDownloads
import app.tanuki.core.domain.manga.model.ReadingMode
import app.tanuki.core.domain.manga.model.ReadingStatus
import app.tanuki.core.domain.manga.usecase.AddToLibrary
import app.tanuki.core.domain.manga.usecase.ObserveManga
import app.tanuki.core.domain.manga.usecase.RemoveMangaFromLibrary
import app.tanuki.core.domain.manga.usecase.SetFilteredScanlators
import app.tanuki.core.domain.manga.usecase.SetReadingMode
import app.tanuki.core.domain.manga.usecase.SetReadingStatus
import app.tanuki.core.domain.manga.usecase.SyncSourceMangaDetails
import app.tanuki.core.domain.source.usecase.ObserveSources
import app.tanuki.core.navigation.Downloads
import app.tanuki.core.navigation.Reader
import app.tanuki.core.presentation.TanukiViewModel
import app.tanuki.features.manga.viewmodel.MangaState.Content
import app.tanuki.features.manga.viewmodel.MangaState.Loading
import app.tanuki.features.manga.viewmodel.MangaState.MangaCategoryChip
import app.tanuki.features.manga.viewmodel.MangaState.NotFound
import app.tanuki.features.manga.viewmodel.MangaState.ScanlatorChip
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.Double.Companion.MAX_VALUE
import kotlin.Double.Companion.MIN_VALUE
import kotlin.uuid.Uuid

/**
 * Presentation logic for the Manga Detail screen. Compose-free (per CLAUDE.md → MVVM): it exposes state
 * as a [StateFlow] and screen-scoped navigation through [TanukiViewModel.navEvents], so the same logic
 * drives the shared Compose UI on both Android and iOS and is unit-testable without a Compose runtime.
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
    observeChapterDownloads: ObserveChapterDownloads,
    private val enqueueChapterDownloads: EnqueueChapterDownloads,
    private val enqueueNextChapters: EnqueueNextChapters,
    private val cancelChapterDownloads: CancelChapterDownloads,
    private val deleteChapterDownloads: DeleteChapterDownloads,
    private val deleteMangaDownloads: DeleteMangaDownloads,
    private val setMangaCategories: SetMangaCategories,
    private val addToLibrary: AddToLibrary,
    private val removeMangaFromLibrary: RemoveMangaFromLibrary,
    private val setReadingStatus: SetReadingStatus,
    private val setReadingMode: SetReadingMode,
    private val setFilteredScanlators: SetFilteredScanlators,
    private val setChaptersRead: SetChaptersRead,
    private val syncSourceChapters: SyncSourceChapters,
    private val syncSourceMangaDetails: SyncSourceMangaDetails,
    dispatcher: DispatcherProvider,
    logger: Logger
) : TanukiViewModel(dispatcher, logger) {
    val uiState: StateFlow<MangaState>
        field = MutableStateFlow<MangaState>(Loading)

    /** Chapter ordering: `true` = ascending by chapter number (1 → N), `false` = descending. */
    private val chapterSortAscending = MutableStateFlow(false)

    /**
     * The chapters the user has picked for a bulk action. Held here rather than in the composable so a
     * rotation (or an iOS memory warning) can't silently drop a 200-chapter selection mid-decision, and
     * so the selection logic stays testable without a Compose runtime.
     *
     * May briefly hold an id the list no longer shows (a sync removed it, a filter hid it); the pipeline
     * below prunes those out on the way into the state, and every action works from the pruned set.
     */
    private val selectedChapterIds = MutableStateFlow<Set<Uuid>>(emptySet())

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
                val hidden = manga.filteredScanlators
                val visible = ordered.filterNot { it.isHiddenBy(hidden) }
                val categoryChips = categories.map { MangaCategoryChip(it, mangaId in it.associatedMangas) }
                val source = sources.firstOrNull { it.id == manga.source }
                Content(
                    manga = manga,
                    chapters = visible,
                    // Resumes within what's on screen: with a group filtered out, its chapters aren't
                    // offered by the list, so the Read button must not jump into one either.
                    resumeChapter = resumeChapter(visible),
                    chapterSortAscending = ascending,
                    categories = categoryChips,
                    scanlators = ordered.scanlatorChips(hidden),
                    source = source
                )
            }
        }.combine(observeChapterDownloads(mangaId)) { state, downloads ->
            if (state is Content) state.copy(downloads = downloads) else state
        }.combine(selectedChapterIds) { state, selected ->
            // Selection is folded in last rather than being another `combine` source: the chapter pipeline
            // above is what defines which rows exist, and the selection is only ever a subset of its
            // output — so it has to be resolved against the list the user is actually looking at. Download
            // records are folded in before it for the same reason in reverse: what a selection may *do*
            // depends on them, so they have to be there by the time the flags are computed.
            if (state is Content) state.copy(selection = state.selectionOf(selected)) else state
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
     * The failure is currently only logged (WARN — recoverable, the cached list stays up): surfacing it
     * needs a refresh affordance on the screen (a pull-to-refresh and an inline error), which doesn't
     * exist yet.
     */
    private fun refreshChapters() {
        viewModelScope.launch {
            tryIo("Chapter refresh failed for $mangaId") { syncSourceChapters(mangaId) }
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
            tryIo("Detail refresh failed for $mangaId") { syncSourceMangaDetails(mangaId) }
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
            io {
                if (!content.inLibrary && members.isNotEmpty()) addToLibrary(mangaId)
                setMangaCategories(mangaId, members)
            }
        }
    }

    /**
     * A reading status was picked in the status sheet — record it (live-apply). Setting a status on a
     * manga that isn't in the library **implicitly adds it first** (see [SetReadingStatus]), the same as
     * assigning a category: you can't be "reading" a series the library doesn't track. The reactive
     * pipeline re-emits with the new [Manga.readingStatus] (and `inLibrary = true` when it was an add).
     */
    fun onSetReadingStatus(status: ReadingStatus) {
        val content = uiState.value as? Content ?: return
        if (content.manga.readingStatus == status) return
        viewModelScope.launch {
            io {
                setReadingStatus(mangaId, status)
            }
        }
    }

    /**
     * A reading mode was picked in the reading-mode sheet — record it (live-apply). Reading mode is a
     * source-owned per-title preference on the manga row, so — unlike [onSetReadingStatus] — this never
     * adds the manga to the library. The reactive pipeline re-emits with the new [Manga.readingMode], and
     * the reader reads it back when opening a chapter.
     */
    fun onSetReadingMode(mode: ReadingMode) {
        val content = uiState.value as? Content ?: return
        if (content.manga.readingMode == mode) return
        viewModelScope.launch {
            io {
                setReadingMode(mangaId, mode)
            }
        }
    }

    /** The Info tab's "Add to library" action was tapped — record library membership (no-op if already in). */
    fun onAddToLibrary() {
        val content = uiState.value as? Content ?: return
        if (content.inLibrary) return
        viewModelScope.launch {
            io {
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
            io {
                removeMangaFromLibrary(mangaId)
            }
        }
    }

    /**
     * A group chip was tapped in the chapter filter sheet — show or hide that group's chapters.
     *
     * Persisted per manga (so the choice survives leaving the screen) as an *exclude* set, and applied
     * only when presenting: the hidden chapters stay in the database, because deleting them would
     * cascade away the reading progress the user built up on them.
     *
     * Hiding the last visible group would leave an empty list with no obvious way back, so the final
     * shown group can't be toggled off — the sheet's reset is the way out of a filter.
     */
    fun onToggleScanlator(name: String) {
        val content = uiState.value as? Content ?: return
        val hidden = content.manga.filteredScanlators.toMutableSet()
        if (name in hidden) {
            hidden.remove(name)
        } else {
            if (content.scanlators.count { it.isShown } <= 1) return
            hidden.add(name)
        }
        persistFilteredScanlators(hidden)
    }

    /** The filter sheet's reset was tapped — show every group again. */
    fun onResetChapterFilter() {
        val content = uiState.value as? Content ?: return
        if (content.manga.filteredScanlators.isEmpty()) return
        persistFilteredScanlators(emptySet())
    }

    private fun persistFilteredScanlators(hidden: Set<String>) {
        viewModelScope.launch {
            io { setFilteredScanlators(mangaId, hidden) }
        }
    }

    /** The chapter list's sort control was tapped — flip between ascending and descending order. */
    fun onToggleChapterSort() {
        chapterSortAscending.value = !chapterSortAscending.value
    }

    /** The back arrow was tapped — pop this screen off the back stack. */
    fun onBack() {
        navigateBack()
    }

    /**
     * A chapter row was tapped. While a selection is running the tap toggles that row instead of opening
     * it — the standard multi-select contract, and the only way to pick apart chapters that aren't
     * neighbours without a modifier key the phone doesn't have. With no selection running it opens the
     * reader, which stays the one-tap default.
     */
    fun onChapterClick(chapterId: Uuid) {
        if (selection().isActive) {
            toggleSelection(chapterId)
        } else {
            navigateTo(Reader(mangaId, chapterId))
        }
    }

    /**
     * A chapter row was long-pressed — the way *into* selection mode, and a toggle once inside it. Long
     * press rather than a permanently visible checkbox column: bulk marking is the rare errand next to
     * reading, and a row of checkboxes would tax every ordinary visit to make the rare one cheaper.
     */
    fun onChapterLongClick(chapterId: Uuid) {
        toggleSelection(chapterId)
    }

    /**
     * "Select above" — extends the selection to every chapter shown above the topmost picked one.
     *
     * Anchored on the topmost picked chapter (not the last one tapped) so the action is a pure extension
     * of the range in view: it only ever adds, it reads the same whether one or twenty rows are picked,
     * and running it twice is a no-op. "Above" means above *in the list as displayed* — flipping the sort
     * flips which chapters that is, which is what the user pointing at the screen means either way.
     */
    fun onSelectAbove() {
        val content = uiState.value as? Content ?: return
        val selected = content.selection.ids
        val top = content.chapters.indexOfFirst { it.id in selected }
        if (top <= 0) return
        selectedChapterIds.value = selected + content.chapters.take(top).map(Chapter::id)
    }

    /** "Select below" — [onSelectAbove]'s mirror: every chapter shown below the bottommost picked one. */
    fun onSelectBelow() {
        val content = uiState.value as? Content ?: return
        val selected = content.selection.ids
        val bottom = content.chapters.indexOfLast { it.id in selected }
        if (bottom < 0 || bottom == content.chapters.lastIndex) return
        selectedChapterIds.value = selected + content.chapters.drop(bottom + 1).map(Chapter::id)
    }

    /**
     * The select-all control was tapped — pick every shown chapter, or clear back to none when they are
     * all picked already. One control for both directions because "select all" and "undo that" are the
     * same thought, and the second tap is how a user recovers from the first.
     *
     * "Every shown chapter" excludes anything a scanlator filter is hiding: a bulk action must never
     * reach rows the screen isn't showing.
     */
    fun onToggleSelectAll() {
        val content = uiState.value as? Content ?: return
        selectedChapterIds.value =
            if (content.selection.allSelected) emptySet() else content.chapters.mapTo(mutableSetOf(), Chapter::id)
    }

    /** The selection was dismissed (X, system back, or leaving the Chapters tab) — back to reading chrome. */
    fun onClearSelection() {
        if (selectedChapterIds.value.isNotEmpty()) selectedChapterIds.value = emptySet()
    }

    /**
     * The selection bar's mark-read / mark-unread action — applies [SetChaptersRead] to everything picked.
     *
     * The selection is dropped immediately, before the write lands: the mode exists to stage this action,
     * so leaving it is the acknowledgement that the action was taken, and the rows re-render read (or
     * unread) as the database emits. Nothing is queued behind the write, so a slow disk can't leave the
     * bar sitting there looking unresponsive.
     */
    fun onMarkSelectionRead(read: Boolean) {
        val selected = selection().ids
        if (selected.isEmpty()) return
        selectedChapterIds.value = emptySet()
        viewModelScope.launch {
            io { setChaptersRead(mangaId, selected, read) }
        }
    }

    /**
     * The download affordance on a chapter row was tapped. One control, and what it means depends on
     * where the chapter is — which is the point: the row shows a single state, and tapping it does the
     * only thing that state affords.
     *
     *  - never queued, or **failed** → queue it (a failed row's tap is the retry).
     *  - **queued** or **downloading** → cancel it, keeping whatever it fetched for a later resume.
     *  - **downloaded** → nothing. Removing files is destructive and has its own menu item
     *    ([onDeleteChapterDownload]), so a mistaken tap on a finished chapter can never lose it.
     */
    fun onChapterDownloadClick(chapterId: Uuid) {
        val content = uiState.value as? Content ?: return
        when (content.downloads[chapterId]?.state) {
            null, DownloadState.Failed -> viewModelScope.launch { io { enqueueChapterDownloads(listOf(chapterId)) } }

            DownloadState.Queued, DownloadState.Downloading ->
                viewModelScope.launch { io { cancelChapterDownloads(listOf(chapterId)) } }

            DownloadState.Downloaded -> Unit
        }
    }

    /** A row's "delete download" was chosen — remove that chapter's files and its record. */
    fun onDeleteChapterDownload(chapterId: Uuid) {
        viewModelScope.launch {
            io { deleteChapterDownloads(listOf(chapterId)) }
        }
    }

    /**
     * The selection bar's download action — queues everything picked.
     *
     * The selection is dropped before the write lands, exactly as [onMarkSelectionRead] does and for the
     * same reason: the mode exists to stage the action, so leaving it *is* the acknowledgement, and the
     * rows take on their queued state as the database emits.
     */
    fun onDownloadSelection() {
        val selected = selection().ids
        if (selected.isEmpty()) return
        selectedChapterIds.value = emptySet()
        viewModelScope.launch {
            io { enqueueChapterDownloads(selected) }
        }
    }

    /** The selection bar's delete-download action — removes files and records for everything picked. */
    fun onDeleteSelectionDownloads() {
        val selected = selection().ids
        if (selected.isEmpty()) return
        selectedChapterIds.value = emptySet()
        viewModelScope.launch {
            io { deleteChapterDownloads(selected) }
        }
    }

    /**
     * The overflow's "download next N" — queues the next [count] unread chapters, or every unread one
     * when [count] is `null`.
     *
     * The picking is [EnqueueNextChapters]' job, not this screen's: "next" has to mean the same thing
     * here as it does anywhere else that offers it, and the rules that define it (reading order, unread
     * only, the scanlator filter) are already written down there once.
     */
    fun onDownloadNextChapters(count: Int?) {
        viewModelScope.launch {
            io { enqueueNextChapters(mangaId, count) }
        }
    }

    /**
     * The overflow's "download all" — queues every chapter the list is *showing*, read ones included.
     *
     * Deliberately the shown list rather than the whole series: a group the user filtered out is not
     * something they asked to keep, and on an aggregator where three groups publish the same chapter it
     * would be three times the bytes for one chapter's worth of reading.
     */
    fun onDownloadAllChapters() {
        val content = uiState.value as? Content ?: return
        val ids = content.chapters.map(Chapter::id)
        if (ids.isEmpty()) return
        viewModelScope.launch {
            io { enqueueChapterDownloads(ids) }
        }
    }

    /**
     * The overflow's "delete all downloads" — clears this series from the device. Destructive, so the UI
     * confirms before calling it.
     *
     * A whole-series call rather than a loop over the shown chapters: it has to reach a chapter the list
     * is currently hiding, and a directory whose rows a sync already pruned, which is exactly what
     * [DeleteMangaDownloads] is for.
     */
    fun onDeleteAllDownloads() {
        viewModelScope.launch {
            io { deleteMangaDownloads(mangaId) }
        }
    }

    /**
     * The overflow's link to the download queue.
     *
     * A screen-scoped push, so the queue lands on whichever tab the user reached this series from and
     * back returns them to it — rather than an [app.tanuki.core.navigation.AppNavigator] jump, which
     * would send them to a tab they weren't in and lose the series behind them.
     */
    fun onOpenDownloadQueue() = navigateTo(Downloads)

    /** Adds or removes one chapter, working from the pruned selection the state exposes. */
    private fun toggleSelection(chapterId: Uuid) {
        val selected = selection().ids
        selectedChapterIds.value =
            if (chapterId in selected) selected - chapterId else selected + chapterId
    }

    /** The selection as currently *shown* — stale ids have already been pruned out of it. */
    private fun selection(): MangaState.ChapterSelection =
        (uiState.value as? Content)?.selection ?: MangaState.ChapterSelection.None

    /**
     * Resolves the raw picked ids against this content's chapter list: drops ids the list no longer shows,
     * and works out what the selection bar may offer for what's left. Done here, once per emission, rather
     * than in the composables — each flag is a scan of a list that runs to thousands of rows.
     */
    private fun Content.selectionOf(selected: Set<Uuid>): MangaState.ChapterSelection {
        if (selected.isEmpty()) return MangaState.ChapterSelection.None
        val shown = chapters.filterTo(mutableSetOf()) { it.id in selected }
        if (shown.isEmpty()) return MangaState.ChapterSelection.None
        val ids = shown.mapTo(mutableSetOf(), Chapter::id)
        return MangaState.ChapterSelection(
            ids = ids,
            canSelectAbove = chapters.indexOfFirst { it.id in ids } > 0,
            canSelectBelow = chapters.indexOfLast { it.id in ids } < chapters.lastIndex,
            canMarkRead = shown.any { it.progress?.alreadyRead != true },
            // Unread has progress of its own to clear: a chapter left half-read keeps a page to rewind
            // even though it was never marked read.
            canMarkUnread = shown.any { it.progress?.alreadyRead == true || (it.progress?.lastPageRead ?: 0u) > 0u },
            // Never queued, or queued and failed — the two states downloading one would actually change.
            canDownload = shown.any { downloads[it.id].let { d -> d == null || d.state == DownloadState.Failed } },
            canDeleteDownload = shown.any { it.id in downloads },
            allSelected = ids.size == chapters.size
        )
    }

    /** The primary read/resume action was tapped — open the reader at the [Content.resumeChapter]. */
    fun onReadClick() {
        val content = uiState.value as? Content ?: return
        val target = content.resumeChapter ?: return
        navigateTo(Reader(mangaId, target.id))
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
                .filter { it.progress == null || it.progress?.alreadyRead == false }
                .minByOrNull { it.chapterNumber?.value ?: MAX_VALUE }
        return lastRead ?: chapters.maxByOrNull { it.chapterNumber?.value ?: MIN_VALUE }
    }

    /**
     * The groups that have published a chapter of this series, each with its chapter count and whether
     * it is currently shown, ordered by count (the prolific groups first) then by name.
     *
     * Chapters the source credits to no group are not a chip: with no name to key on there is nothing
     * to filter by, so they are always shown.
     */
    private fun List<Chapter>.scanlatorChips(hidden: Set<String>): List<ScanlatorChip> =
        mapNotNull { it.scanlator?.value }
            .groupingBy { it }
            .eachCount()
            .map { (name, count) -> ScanlatorChip(name, count, isShown = name !in hidden) }
            .sortedWith(compareByDescending<ScanlatorChip> { it.chapterCount }.thenBy { it.name })

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