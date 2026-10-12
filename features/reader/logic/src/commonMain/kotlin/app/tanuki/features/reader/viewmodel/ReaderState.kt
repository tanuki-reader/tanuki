package app.tanuki.features.reader.viewmodel

import app.tanuki.core.domain.manga.model.ReadingMode
import app.tanuki.core.domain.settings.model.ReaderBackground
import kotlin.uuid.Uuid

/**
 * Immutable UI state for the reader, collected by `ReaderScreen` (Compose) and `ReaderScreen.swift`.
 * Compose-free (per CLAUDE.md → MVVM), and Swift-friendly: plain strings and numbers, no domain value
 * classes (`ChapterNumber`, `Url` reach Swift as an untyped `id`).
 *
 * Compose stability is declared in `features/reader/compose-stability.conf` rather than with
 * `@Immutable` here, which would put a Compose dependency in this module. The promise is the same: the
 * item list comes from `ReaderViewModel.buildItems`, which never mutates it after handing it over.
 *
 * Deliberately three states rather than one struct with nullable everything: the reader is either
 * fetching its first page list, showing content, or reporting that the fetch failed, and those are
 * mutually exclusive.
 */
sealed interface ReaderState {
    /** Pages are being fetched — the first thing shown when the reader opens. */
    data object Loading : ReaderState

    /** The initial page fetch failed (no network, source error). The UI offers a retry. */
    data object Error : ReaderState

    /**
     * Content is ready.
     *
     * The reader reads *continuously* across chapters: [items] is the pages of a sliding window of
     * chapters (previous · current · next) concatenated into one scroll, with a [ReaderItem.Transition]
     * slide between each. Reaching the end of the current chapter is therefore just "one more page" into
     * the next — no navigation, no reload. The chrome fields ([currentPage], [chapterNumberLabel], …) always
     * describe the chapter the visible page belongs to, updating as the window slides past a boundary.
     *
     * @property items The window's pages and the transition slides between them, in reading order.
     * @property initialItemKey The item a layout parks on when first composed: the resume page, or after a
     *   reading-mode switch the page that was on screen.
     * @property initialItemIndex [initialItemKey]'s index in *these* [items] — resolved per emission, since
     *   the list can change (the previous chapter warmed in above) before a layout parks. Read once, at
     *   first composition; later shifts are held by item [ReaderItem.key] and [ReaderCommand].
     * @property readingMode How pages are laid out (paged L→R / R→L / single-vertical, or a strip).
     * @property chromeVisible Whether the top/bottom reader chrome is shown; toggled by tapping.
     * @property currentChapterId Local id of the chapter the visible page belongs to — changes as the
     *   reader flows across a boundary, so the UI can confirm the crossing (a pill, a haptic tick).
     * @property currentPage Zero-based index of the visible page **within its chapter** (drives the scrubber).
     * @property currentPageCount Page count of the chapter the visible page belongs to.
     * @property chapterNumberLabel The current chapter's number for display ("12", "10.5"); `null` when
     *   unnumbered. Each platform words it ("Chapter 12").
     * @property chapterName The current chapter's title, or `null` (the UI falls back to "Chapter N").
     * @property hasPreviousChapter Whether a lower-numbered sibling chapter exists.
     * @property hasNextChapter Whether a higher-numbered sibling chapter exists.
     * @property mangaTitle The series' title, under the chapter title in the top bar.
     * @property chapterUrl The current chapter on the source's site (the ⋯ menu's "Open in browser").
     * @property background The page ground the user picked.
     * @property sheet The sheet open over the reader, if any.
     * @property chaptersTab Which list the Chapters sheet shows.
     * @property isCurrentPageBookmarked Whether the page on screen is bookmarked (the top bar's toggle).
     * @property chapters The Chapters sheet's rows; empty while that sheet is closed.
     * @property bookmarks This series' bookmarks for the sheet; empty while it is closed.
     */
    data class Content(
        val items: List<ReaderItem>,
        val initialItemKey: String?,
        val initialItemIndex: Int,
        val readingMode: ReadingMode,
        val chromeVisible: Boolean,
        val currentChapterId: Uuid,
        val currentPage: Int,
        val currentPageCount: Int,
        val chapterNumberLabel: String?,
        val chapterName: String?,
        val hasPreviousChapter: Boolean,
        val hasNextChapter: Boolean,
        val mangaTitle: String,
        val chapterUrl: String,
        val background: ReaderBackground,
        val sheet: ReaderSheet?,
        val chaptersTab: ReaderChaptersTab,
        val isCurrentPageBookmarked: Boolean,
        val chapters: List<ReaderChapterItem>,
        val bookmarks: List<ReaderBookmarkItem>
    ) : ReaderState
}

/** The sheets the reader's chrome opens. */
enum class ReaderSheet { Chapters, Settings }

/** The two lists of the Chapters sheet. */
enum class ReaderChaptersTab { Chapters, Bookmarks }

/**
 * One row of the Chapters sheet: the series' chapters, newest first like Manga detail, one per number
 * (the chapters the reader pages through).
 *
 * @property numberLabel "12", "10.5"; `null` when unnumbered (the row then uses [name]).
 * @property isCurrent the chapter on screen, which the sheet scrolls to and marks.
 * @property bookmarkCount how many of its pages are bookmarked.
 */
data class ReaderChapterItem(
    val id: Uuid,
    val numberLabel: String?,
    val name: String?,
    val scanlator: String?,
    val isRead: Boolean,
    val isDownloaded: Boolean,
    val isCurrent: Boolean,
    val bookmarkCount: Int
)

/**
 * One row of the Bookmarks list, in reading order. [page] is zero-based (the UI shows it plus one). The
 * thumbnail is [imageUrl] + [imageHeaders] or [localPath]; a source URL may have expired, and the UI then
 * shows the cover.
 */
data class ReaderBookmarkItem(
    val id: Uuid,
    val chapterId: Uuid,
    val chapterNumberLabel: String?,
    val chapterName: String?,
    val page: Int,
    val imageUrl: String?,
    val imageHeaders: Map<String, String>,
    val localPath: String?
)

/**
 * The bare identity of a chapter — enough to title a transition slide ("Up next: Chapter 12") without
 * pulling the whole chapter into UI state.
 *
 * @property id Local chapter id.
 * @property numberLabel The chapter number for display, or `null` when the source didn't number it.
 * @property name The chapter's source title, or `null` (the slide falls back to "Chapter N").
 */
data class ChapterRef(
    val id: Uuid,
    val numberLabel: String?,
    val name: String?
)

/**
 * One slot in the reader's continuous scroll: either a chapter [PageItem] or a [Transition] slide
 * bridging two adjacent chapters. Modelling the slides as items (rather than an out-of-band overlay)
 * lets the same pager/list carry them, so crossing into the next chapter feels like reading one more
 * page. [key] is stable per slot for lazy-list reuse and for re-anchoring the viewport when the window
 * slides.
 *
 * Declared stable to Compose (see `features/reader/compose-stability.conf`), including [ReaderState.Content]
 * which holds a `List` of these. That is a promise the compiler does not verify, and the code keeps it:
 * the list comes from `ReaderViewModel`'s `buildItems`, which owns it and never mutates it after handing
 * it over, and every field below is an immutable scalar or a data class of them. Without the promise
 * Compose infers `List<ReaderItem>` — and so `Content` — as **unstable**, and nothing in the reader's page
 * tree can ever skip a recomposition.
 */
sealed interface ReaderItem {
    val key: String

    /**
     * A single page of a specific chapter.
     *
     * @property chapterId The chapter this page belongs to (the window holds several).
     * @property pageNumber Zero-based index of this page **within its chapter**.
     * @property pageCount Total pages in that chapter (so a focused page can tell if it's the last).
     * @property imageUrl Where a source page is fetched from, with [imageHeaders]; `null` for a
     *   downloaded page, which is read from [localPath] instead. Exactly one of the two is set. The
     *   reader does not otherwise distinguish them (see `PageImage`).
     */
    data class PageItem(
        val chapterId: Uuid,
        val pageNumber: Int,
        val pageCount: Int,
        val imageUrl: String?,
        val imageHeaders: Map<String, String>,
        val localPath: String?
    ) : ReaderItem {
        override val key: String get() = pageKey(chapterId, pageNumber)
    }

    /**
     * The boundary between two adjacent chapters: [from] sits above (just finished), [to] below (up
     * next), matching reading order. Shown for every boundary in the window; [loading] is true while the
     * not-yet-fetched neighbour's pages are in flight, so the slide can show a spinner and the pages
     * appear the moment they resolve.
     */
    data class Transition(
        val from: ChapterRef,
        val to: ChapterRef,
        val loading: Boolean
    ) : ReaderItem {
        override val key: String get() = "transition:${from.id}:${to.id}"
    }

    /**
     * The terminal slide after the last page of the last chapter — there is no next chapter to flow into,
     * so instead of dead-ending on the final page the reader shows an explicit "no more chapters" end.
     */
    data object End : ReaderItem {
        override val key: String get() = "end"
    }
}

/**
 * An imperative scroll the ViewModel asks the UI to perform. The reader's pages are driven by user
 * gestures and by these commands; the UI never computes item indices itself.
 *
 * Commands name their target by [ReaderItem.key], never by index. An index is only meaningful against
 * one item list, and the list is re-indexed under the viewport whenever a neighbouring chapter is
 * prepended or evicted — so an index sent against the list in state can land N items away if the UI
 * applies it while still drawing the previous list. The UI resolves the key against the items it is
 * actually rendering, waiting for the key to appear when the list it was sent with hasn't been drawn yet.
 */
sealed interface ReaderCommand {
    /** Key of the item to scroll to. */
    val key: String

    /**
     * Keep the viewport on the same logical page after the item list was re-indexed above it — an adjacent
     * chapter's pages inserted, or the chapter two behind evicted.
     *
     * A fallback: the layouts are keyed by [ReaderItem.key] and hold their own place. Only sent while the
     * viewport is still (a scroll in progress defers it), and a layout applies it only if [key] has left
     * the screen. Android doesn't use it at all: it reaches the collector before the new list is composed,
     * so it would resolve against the old one; the Compose layouts pin their item before the next measure
     * instead (`PinOnReindex`).
     */
    data class Reanchor(override val key: String) : ReaderCommand

    /** An explicit user jump (scrubber, chapter buttons, chapter list). Every layout applies it. */
    data class Jump(override val key: String) : ReaderCommand
}

/** The [ReaderItem.key] of page [pageNumber] of [chapterId] — what jumps are addressed to. */
internal fun pageKey(
    chapterId: Uuid,
    pageNumber: Int
): String = "page:$chapterId:$pageNumber"
