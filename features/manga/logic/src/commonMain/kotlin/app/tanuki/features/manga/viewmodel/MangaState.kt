package app.tanuki.features.manga.viewmodel

import app.tanuki.core.domain.category.model.Category
import app.tanuki.core.domain.chapter.model.Chapter
import app.tanuki.core.domain.download.model.ChapterDownload
import app.tanuki.core.domain.download.model.DownloadState
import app.tanuki.core.domain.manga.model.Manga
import app.tanuki.core.domain.manga.model.PublishStatus
import app.tanuki.core.domain.manga.model.ReadingMode
import app.tanuki.core.domain.manga.model.ReadingStatus
import app.tanuki.core.domain.metadata.model.CoverOption
import app.tanuki.core.domain.metadata.model.MetadataSearchResult
import app.tanuki.core.domain.metadata.model.SeriesMetadata
import app.tanuki.core.domain.metadata.usecase.MangaMetadata
import app.tanuki.core.domain.source.model.Source
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** UI state for the manga detail screen. */
sealed interface MangaState {
    /** Initial state while the manga and its chapters are first read from the database. */
    data object Loading : MangaState

    /** The requested manga id resolved to no row (deleted, or an invalid deep link). */
    data object NotFound : MangaState

    /**
     * The manga and its chapters to render.
     *
     * @param manga the series metadata shown in the hero and description.
     * @param chapters the chapters to render — already ordered by [chapterSortAscending] and with any
     *   hidden scanlation group removed, so the UI renders the list as given.
     * @param resumeChapter the chapter the "Read" action targets — the last chapter the user read, or the
     *   first chapter when none has been read yet; `null` when the manga has no chapters. Both the button
     *   label and its tap use this, so they can never point at different chapters.
     * @param chapterSortAscending whether [chapters] is ordered by ascending chapter number (1 → N);
     *   `false` is descending (N → 1). Reflected by the chapter list's sort toggle.
     * @param categories every library category paired with whether this manga belongs to it — the hero
     *   shows the members, the category sheet shows all of them with their selection.
     * @param scanlators every group that has published a chapter of this series, with its chapter count
     *   and whether it is currently shown — the chapter filter sheet's chips. Fewer than two entries
     *   means there is nothing to choose between, and the filter affordance stays hidden.
     * @param source the source this manga belongs to, shown in the Info tab; `null` when the source is
     *   not (yet) persisted locally, in which case the Info tab shows an "unknown source" fallback.
     * @param selection which chapters the user has picked for a bulk action, and what that selection can
     *   do. Empty means no selection mode is running and the screen is in its normal reading chrome.
     * @param inLibrary whether the manga is in the user's library (has a `date_added`). Drives the Info
     *   tab's add/remove toggle and whether category edits implicitly add it (see the ViewModel).
     * @param downloads the download record of every chapter of this series that has one, keyed by chapter
     *   id. A map rather than a field on each row because a row does a single lookup into it: the list
     *   runs to thousands of rows, and anything per-row — a flow, or a scan — is exactly what makes
     *   scrolling one expensive. A chapter absent from it has never been queued, which is the
     *   overwhelmingly common case and does not deserve a record.
     * @param refresh what a *user-requested* refresh (pull-to-refresh) is doing — see [Refresh]. The
     *   refreshes the screen asks for on open are not reported here: they are speculative and
     *   staleness-gated, so a spinner for them would spin on an open that never asks the source anything.
     */
    data class Content(
        val manga: Manga,
        val chapters: List<Chapter>,
        val resumeChapter: Chapter?,
        val chapterSortAscending: Boolean,
        val categories: List<MangaCategoryChip>,
        val scanlators: List<ScanlatorChip>,
        val source: Source?,
        val selection: ChapterSelection = ChapterSelection.None,
        val downloads: Map<Uuid, ChapterDownload> = emptyMap(),
        val refresh: Refresh = Refresh.Idle,
        val sheet: MangaSheet? = null,
        val metadata: MangaMetadata? = null,
        val editor: MetadataEditor = MetadataEditor()
    ) : MangaState {
        /**
         * The matched metadata, when it should take over the header: linked, fetched, and the user hasn't
         * asked for the source's info. `null` shows the source's data.
         */
        private val preferred: SeriesMetadata?
            get() = metadata?.metadata?.takeIf { metadata.link?.useSourceInfo != true }

        /** The matched series' metadata, whether or not it is preferred — score, tags, rails. */
        val series: SeriesMetadata? get() = metadata?.metadata

        /** Whether the series is matched to the provider (for "Matched on AniList · Change"). */
        val isMatched: Boolean get() = metadata?.link?.anilistId != null

        /** AniList's 0–100 average as a 0–10 score with one decimal ("8.8"), `null` when unrated. */
        val scoreLabel: String? get() = series?.score?.let { "${it / 10}.${it % 10}" }

        /** The user's "Use source info" switch. */
        val useSourceInfo: Boolean get() = metadata?.link?.useSourceInfo == true

        /** The page's header, in plain values for Swift (the domain's `Text`/`Url` reach it untyped). */
        val header: MangaHeader by lazy {
            val preferred = preferred
            // The user's pick wins; else the metadata's cover (when preferred); else the source's. Only the
            // source's cover needs its headers — a provider CDN takes none.
            val chosenCover = metadata?.link?.coverChoice ?: preferred?.coverUrl
            MangaHeader(
                title = preferred?.title?.takeIf { it.isNotBlank() } ?: manga.title.value,
                author = manga.author?.value,
                coverUrl = chosenCover ?: manga.cover.url?.value,
                coverHeaders = if (chosenCover == null) manga.cover.headers else emptyMap(),
                synopsis = preferred?.synopsis ?: manga.description?.value,
                publishStatus =
                    preferred?.status?.takeIf { it != PublishStatus.UNKNOWN } ?: manga.publishStatus,
                genres = preferred?.genres.orEmpty(),
                chapterCount = chapters.size,
                sourceName = source?.name?.value,
                sourceLang = source?.lang?.value,
                readingStatus = manga.readingStatus,
                readingMode = manga.readingMode,
                inLibrary = inLibrary,
                url = manga.url.value
            )
        }

        /** [chapters] as list rows, in plain values for Swift. Built once per state. */
        val rows: List<ChapterRow> by lazy {
            chapters.map { chapter ->
                val download = downloads[chapter.id]
                ChapterRow(
                    id = chapter.id,
                    numberLabel = chapter.chapterNumber?.display,
                    name = chapter.name?.value,
                    scanlator = chapter.scanlator?.value,
                    dateUploaded = chapter.dateUpload,
                    isRead = chapter.progress?.alreadyRead == true,
                    lastPageRead = chapter.progress?.lastPageRead?.toInt()?.takeIf { it > 0 && chapter.progress?.alreadyRead != true },
                    download = download?.state,
                    downloadProgress =
                        download?.pageCount?.takeIf { it > 0 }?.let { download.downloadedPages.toFloat() / it },
                    isSelected = chapter.id in selection.ids
                )
            }
        }

        /** The floating Read button: the chapter it resumes, `null` when there is none to read. */
        val resumeNumberLabel: String? get() = resumeChapter?.chapterNumber?.display

        /** Whether any chapter has been started — "Continue" rather than "Start" copy. */
        val hasResume: Boolean get() = resumeChapter != null

        val inLibrary: Boolean get() = manga.dateAdded != null

        /**
         * Whether this series has anything downloaded or queued — what gates the overflow's "delete all
         * downloads", so a destructive action is never offered when there is nothing to destroy.
         */
        val hasDownloads: Boolean get() = downloads.isNotEmpty()

        /**
         * Whether the queue still has something to say about this series — a chapter waiting, one being
         * fetched, or one that stopped with an error. Gates the overflow's link to the queue screen, so
         * the link is offered exactly when there is a queue worth opening.
         */
        val hasPendingDownloads: Boolean
            get() =
                downloads.values.any {
                    it.isPending || it.state == DownloadState.Failed
                }

        /** Whether the chapter list is worth offering a group filter for — i.e. more than one group. */
        val canFilterScanlators: Boolean get() = scanlators.size > 1

        /** Whether any group is currently hidden, which the filter affordance marks with a dot. */
        val isChapterFilterActive: Boolean get() = scanlators.any { !it.isShown }
    }

    /**
     * A user-requested refresh of this series from its source — the pull-to-refresh gesture.
     *
     * Two flags rather than a sealed state because they are not alternatives: pulling again while the
     * previous attempt's message is still up is both `inProgress` and — until the new attempt lands —
     * still showing what the last one said.
     *
     * @param inProgress whether a refresh is running, which drives the pull indicator. Only ever true for
     *   a refresh the user asked for: the ones fired on open are speculative and usually suppressed by the
     *   syncs' staleness gate, so showing them would spin over a screen that isn't fetching anything.
     * @param failed whether the last user-requested chapter refresh failed — the screen offers a retry.
     *   Only the chapter sync counts: it is what the gesture is *for*, while the details sync is a
     *   best-effort fill-in for fields a catalog listing omits, and a source that doesn't implement it
     *   would otherwise fail every pull forever.
     */
    data class Refresh(
        val inProgress: Boolean,
        val failed: Boolean
    ) {
        companion object {
            /** Nothing running, nothing to report — the state a screen opens in. */
            val Idle = Refresh(inProgress = false, failed = false)
        }
    }

    /**
     * One scanlation group in the chapter filter: its name, how many of this manga's chapters it
     * published, and whether those chapters are currently shown.
     *
     * @param name the group as the source credits it. Content, not copy — never localized.
     * @param chapterCount how many chapters this group published, shown on the chip.
     * @param isShown `false` when the group is in [app.tanuki.core.domain.manga.model.Manga.filteredScanlators].
     */
    data class ScanlatorChip(
        val name: String,
        val chapterCount: Int,
        val isShown: Boolean
    )

    /**
     * The chapter multi-select: which chapters are picked, plus what the bulk-action bar may offer for
     * them. The four `can*` flags are computed once by the ViewModel — each is a scan of the chapter list
     * (which runs to thousands of rows on a long series), so they are state, not something the bar
     * re-derives on every recomposition.
     *
     * @param ids the picked chapters. Only ever ids the list is currently *showing*: a chapter that a
     *   scanlator filter hides or a sync removes drops out of the selection with it, so no bulk action
     *   can touch a row the user can't see.
     * @param canSelectAbove whether any chapter sits above the topmost picked one in the displayed order
     *   — i.e. whether "select above" would add anything.
     * @param canSelectBelow the same, below the bottommost picked chapter.
     * @param canMarkRead whether any picked chapter is not read yet.
     * @param canMarkUnread whether any picked chapter has reading state to clear — read, or merely opened
     *   and left partway through.
     * @param canDownload whether any picked chapter is worth queueing: one that has never been queued, or
     *   one that failed and can be retried. Chapters already queued, downloading or downloaded are not —
     *   the action would be a no-op on them (see `EnqueueChapterDownloads`).
     * @param canDeleteDownload whether any picked chapter has a download record at all. Deliberately
     *   broader than "is downloaded": the same verb removes a finished chapter's files and takes an
     *   unfinished one out of the queue, so that a selection spanning both does the obvious thing rather
     *   than needing the user to work out which of two verbs applies to which row.
     * @param allSelected whether every shown chapter is picked, which flips the select-all control into
     *   its "clear" meaning.
     */
    data class ChapterSelection(
        val ids: Set<Uuid>,
        val canSelectAbove: Boolean,
        val canSelectBelow: Boolean,
        val canMarkRead: Boolean,
        val canMarkUnread: Boolean,
        val canDownload: Boolean,
        val canDeleteDownload: Boolean,
        val allSelected: Boolean
    ) {
        /** Whether a selection is running — the screen swaps to its selection chrome exactly when true. */
        val isActive: Boolean get() = ids.isNotEmpty()

        val count: Int get() = ids.size

        companion object {
            /** No selection: the screen's normal reading chrome. */
            val None =
                ChapterSelection(
                    ids = emptySet(),
                    canSelectAbove = false,
                    canSelectBelow = false,
                    canMarkRead = false,
                    canMarkUnread = false,
                    canDownload = false,
                    canDeleteDownload = false,
                    allSelected = false
                )
        }
    }

    /** A [Category] paired with whether the current manga is a member of it. */
    data class MangaCategoryChip(
        val category: Category,
        val isMember: Boolean
    ) {
        /** For Swift, which can't read the domain's `Text`. */
        val name: String get() = category.name.value
    }
}

/** A modal over the manga page. Screen state like any other, so both platforms show the same one. */
enum class MangaSheet { Categories, ChapterFilter, ReadingStatus, ReadingMode, ConfirmDeleteDownloads, CoverPicker, MatchSearch }

/**
 * Working state of the metadata sheets. [coverOptions] / [matchResults] are `null` while loading.
 */
data class MetadataEditor(
    val coverOptions: List<CoverOption>? = null,
    val matchQuery: String = "",
    val matchResults: List<MetadataSearchResult>? = null
)

/**
 * The manga page's header in plain values. [publishStatus], [readingStatus] and [readingMode] stay enums:
 * each platform maps them to its own copy.
 */
data class MangaHeader(
    val title: String,
    val author: String?,
    val coverUrl: String?,
    val coverHeaders: Map<String, String>,
    val synopsis: String?,
    val publishStatus: PublishStatus,
    val genres: List<String>,
    val chapterCount: Int,
    val sourceName: String?,
    val sourceLang: String?,
    val readingStatus: ReadingStatus?,
    val readingMode: ReadingMode,
    val inLibrary: Boolean,
    val url: String
)

/**
 * One chapter row. [numberLabel] ("12", "10.5") or [name] make the title; each platform formats
 * [dateUploaded]. [lastPageRead] is set only for a chapter started but not finished ("Page 12").
 * [download] is `null` when nothing is on disk or queued; [downloadProgress] is 0–1 while downloading.
 */
data class ChapterRow(
    val id: Uuid,
    val numberLabel: String?,
    val name: String?,
    val scanlator: String?,
    val dateUploaded: Instant?,
    val isRead: Boolean,
    val lastPageRead: Int?,
    val download: DownloadState?,
    val downloadProgress: Float?,
    val isSelected: Boolean
)

