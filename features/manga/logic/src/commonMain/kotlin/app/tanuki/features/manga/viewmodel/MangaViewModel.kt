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
import app.tanuki.core.domain.metadata.usecase.EditMangaMetadata
import app.tanuki.core.domain.metadata.usecase.EnrichManga
import app.tanuki.core.domain.metadata.usecase.ObserveMangaMetadata
import app.tanuki.core.domain.source.usecase.ObserveSources
import app.tanuki.core.navigation.GlobalSearch
import app.tanuki.core.navigation.Downloads
import app.tanuki.core.navigation.Reader
import app.tanuki.core.presentation.StateViewModel
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
import kotlin.Double.Companion.MAX_VALUE
import kotlin.Double.Companion.MIN_VALUE
import kotlin.uuid.Uuid
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
 * when it lands. That open-time refresh is staleness-gated by the sync itself and stays silent;
 * [onRefresh] is the user's way past the gate, and the only refresh that reports what it is doing.
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
    observeMangaMetadata: ObserveMangaMetadata,
    private val enrichManga: EnrichManga,
    private val editMangaMetadata: EditMangaMetadata,
    dispatcher: DispatcherProvider,
    logger: Logger
) : StateViewModel<MangaState>(dispatcher, logger, Loading) {
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

    /**
     * What a *user-requested* refresh is doing. Held apart from the reactive pipeline because it is the
     * one part of this screen's state that isn't derived from the database: it describes a request in
     * flight, which no query can report.
     */
    private val refresh = MutableStateFlow(MangaState.Refresh.Idle)

    /** The open modal, if any — screen state, so both platforms show the same one. */
    private val sheet = MutableStateFlow<MangaSheet?>(null)

    /** The cover picker's and the match search's working state; see [MetadataEditor]. */
    private val editor = MutableStateFlow(MetadataEditor())

    override val state: Flow<MangaState> =
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
        }.combine(refresh) { state, refreshing ->
            if (state is Content) state.copy(refresh = refreshing) else state
        }.combine(sheet) { state, sheet ->
            if (state is Content) state.copy(sheet = sheet) else state
        }.combine(
            // No `onStart` placeholder: the cache answers in one local read, so a series enriched before
            // opens with its metadata in the first frame instead of showing the source's and swapping. A
            // series never enriched still opens at once (no link yet emits empty) and changes when
            // enrichment lands, which each platform animates.
            observeMangaMetadata(mangaId)
        ) { state, metadata ->
            if (state is Content) state.copy(metadata = metadata) else state
        }.combine(editor) { state, editor ->
            if (state is Content) state.copy(editor = editor) else state
        }

    init {
        // Deliberately not part of [state]: these are the on-open syncs, and they must run because the
        // screen was opened, not because something is collecting it.
        refreshChapters()
        refreshDetails()
        enrich()
    }

    /**
     * Fetches this series' metadata in the background — its own coroutine, so neither the chapter list nor
     * the details wait on a third-party API. [EnrichManga] reads the cache first and only calls the
     * provider once the cached copy is older than its freshness window, so opening a page is normally no
     * network at all. It never throws; a failure leaves the page on the source's data.
     */
    private fun enrich(force: Boolean = false) {
        viewModelScope.launch { enrichManga(mangaId, force) }
    }

    /**
     * Refreshes this manga's chapters from its source, in the background.
     *
     * Fire-and-forget by design — the screen never blocks on it, because [ObserveChapters] has already
     * emitted whatever is cached and the refreshed rows arrive through that same flow. A failure
     * (offline, source error) therefore leaves the cached list on screen rather than emptying it.
     *
     * The failure is only logged (WARN — recoverable, the cached list stays up) and nothing is said on
     * screen: the user didn't ask for this refresh, so an error about it would be an interruption about
     * work they never requested. A refresh they *did* ask for reports itself — see [onRefresh].
     *
     * Asked for on every open, but **not** performed on every open: this view model is scoped to the
     * navigation entry, so browsing back and forth through ten series used to be ten chapter-list
     * requests. [SyncSourceChapters] drops the ones that fall inside its staleness window. The gate is
     * deliberately its own, not this screen's — it belongs to the sync, so a library run and a screen
     * open answer to the same rule. [onRefresh] passes `force` to bypass it.
     */
    private fun refreshChapters() {
        viewModelScope.launch { syncChapters(force = false) }
    }

    /**
     * Fills in the fields a browse listing doesn't carry (description, author, full status), in the
     * background — see [SyncSourceMangaDetails] for why the catalog deliberately omits them.
     *
     * A separate coroutine from [refreshChapters], not a step inside it: they hit different source
     * endpoints and neither is worth delaying for the other, so a slow chapter feed can't hold up the
     * description and a source that implements one but not the other still delivers what it has.
     * Fire-and-forget and failure-tolerant for the same reason: the cached row stays on screen. Also
     * staleness-gated like [refreshChapters], on a far longer window — a synopsis and an author credit
     * change on a scale of never, next to a chapter list.
     */
    private fun refreshDetails() {
        viewModelScope.launch { syncDetails(force = false) }
    }

    /**
     * The screen was pulled down to refresh — ask the source for this series again, ignoring both syncs'
     * staleness windows.
     *
     * This is the escape hatch those windows need: without it a user who knows a chapter just landed has
     * no way past a thirty-minute gate, and the day-long details gate would hold a fixed synopsis for a
     * day. It is the *only* refresh path that passes `force`, which is what keeps the gates worth having
     * — everything automatic still answers to them.
     *
     * Unlike the two on-open refreshes this one is reported: the user asked, so they are owed a spinner
     * while it runs and a reason when it doesn't work. Only the chapter sync's failure is reported,
     * though — see [MangaState.Refresh.failed]. The details sync runs concurrently rather than after,
     * for the same reason the two are separate coroutines on open: different endpoints, and a slow
     * synopsis must not hold the spinner over an already-updated chapter list.
     */
    /** The user's per-series switch: show the source's title, cover and synopsis instead of the metadata's. */
    fun onUseSourceInfoChange(useSourceInfo: Boolean) {
        viewModelScope.launch { tryIo("Couldn't save the metadata preference") { editMangaMetadata.setUseSourceInfo(mangaId, useSourceInfo) } }
    }

    /** Open the cover picker and load its choices (the metadata's cover, MangaDex volumes, the source's). */
    fun onOpenCoverPicker() {
        sheet.value = MangaSheet.CoverPicker
        editor.update { it.copy(coverOptions = null) }
        val sourceCover = (uiState.value as? Content)?.manga?.cover?.url?.value
        viewModelScope.launch {
            val options = tryIo("Couldn't load cover options") { editMangaMetadata.coverOptions(mangaId, sourceCover) }
            editor.update { it.copy(coverOptions = options.getOrDefault(emptyList())) }
        }
    }

    /** A cover was picked (`null` = back to the default). */
    fun onPickCover(url: String?) {
        sheet.value = null
        viewModelScope.launch { tryIo("Couldn't save the cover choice") { editMangaMetadata.setCoverChoice(mangaId, url) } }
    }

    /** Open the manual match search, pre-filled with the series' title and already searching it. */
    fun onOpenMatchSearch() {
        sheet.value = MangaSheet.MatchSearch
        val title = (uiState.value as? Content)?.manga?.title?.value.orEmpty()
        onMatchSearch(title)
    }

    /** Search the metadata provider for [query] in the match sheet. */
    fun onMatchSearch(query: String) {
        editor.update { it.copy(matchQuery = query, matchResults = null) }
        viewModelScope.launch {
            val results = tryIo("Metadata search failed") { editMangaMetadata.search(query) }
            // Only if the user hasn't typed something newer meanwhile.
            editor.update { if (it.matchQuery == query) it.copy(matchResults = results.getOrDefault(emptyList())) else it }
        }
    }

    /** The user confirmed a search result: link it and fetch its metadata now. */
    fun onConfirmMatch(anilistId: Long) {
        sheet.value = null
        viewModelScope.launch {
            tryIo("Couldn't link the series") { editMangaMetadata.link(mangaId, anilistId) }
            enrichManga(mangaId, force = true)
        }
    }

    /** Forget the match; the source's info shows again. */
    fun onUnlinkMetadata() {
        viewModelScope.launch { tryIo("Couldn't unlink the series") { editMangaMetadata.unlink(mangaId) } }
    }

    /** Open a modal over the page. */
    /** A recommended series: look for it in the installed sources (closing the About sheet if it's open). */
    fun onRecommendationClick(title: String) {
        sheet.value = null
        navigateTo(GlobalSearch(query = title))
    }

    fun onOpenSheet(sheet: MangaSheet) {
        this.sheet.value = sheet
    }

    /** The open modal was dismissed. */
    fun onDismissSheet() {
        sheet.value = null
    }

    fun onRefresh() {
        if (refresh.value.inProgress) return
        refresh.value = MangaState.Refresh(inProgress = true, failed = false)
        viewModelScope.launch {
            val details = async { syncDetails(force = true) }
            val chapters = syncChapters(force = true)
            details.await()
            refresh.value = MangaState.Refresh(inProgress = false, failed = chapters.isFailure)
        }
    }

    /** The refresh error was dismissed (tapped away, or it timed out) — drop it without retrying. */
    fun onDismissRefreshError() {
        if (refresh.value.failed) refresh.value = refresh.value.copy(failed = false)
    }

    /** Both syncs in one place so the on-open and pull-to-refresh paths can't drift on how they log. */
    private suspend fun syncChapters(force: Boolean) =
        tryIo("Chapter refresh failed for $mangaId") { syncSourceChapters(mangaId, force = force) }

    private suspend fun syncDetails(force: Boolean) =
        tryIo("Detail refresh failed for $mangaId") { syncSourceMangaDetails(mangaId, force = force) }

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
        sheet.value = null
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
        sheet.value = null
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
        sheet.value = null
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