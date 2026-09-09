package app.tanuki.features.reader.viewmodel

import androidx.compose.runtime.Immutable
import app.tanuki.core.domain.chapter.model.ChapterNumber
import app.tanuki.core.domain.chapter.model.ReaderPage
import app.tanuki.core.domain.manga.model.ReadingMode
import kotlin.uuid.Uuid

/**
 * Immutable UI state for the reader, collected by `ReaderScreen`. Compose-free (per CLAUDE.md → MVVM):
 * every field is data the UI renders, never a Compose type.
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
     * the next — no navigation, no reload. The chrome fields ([currentPage], [chapterNumber], …) always
     * describe the chapter the visible page belongs to, updating as the window slides past a boundary.
     *
     * @property items The window's pages and the transition slides between them, in reading order.
     * @property initialItemIndex Where the pager/list should park on first composition (the resume page,
     *   offset by any leading transition). Read once: later window shifts re-index [items] under the
     *   viewport, and both layouts hold their place through that by item [ReaderItem.key], not by index.
     * @property readingMode How pages are laid out (paged L→R / R→L / single-vertical, or a strip).
     * @property chromeVisible Whether the top/bottom reader chrome is shown; toggled by tapping.
     * @property currentChapterId Local id of the chapter the visible page belongs to — changes as the
     *   reader flows across a boundary, so the UI can confirm the crossing (a pill, a haptic tick).
     * @property currentPage Zero-based index of the visible page **within its chapter** (drives the scrubber).
     * @property currentPageCount Page count of the chapter the visible page belongs to.
     * @property chapterNumber Recognized number of the current chapter; `null` when unnumbered.
     * @property chapterName The current chapter's title, or `null` (the UI falls back to "Chapter N").
     * @property hasPreviousChapter Whether a lower-numbered sibling chapter exists.
     * @property hasNextChapter Whether a higher-numbered sibling chapter exists.
     */
    @Immutable
    data class Content(
        val items: List<ReaderItem>,
        val initialItemIndex: Int,
        val readingMode: ReadingMode,
        val chromeVisible: Boolean,
        val currentChapterId: Uuid,
        val currentPage: Int,
        val currentPageCount: Int,
        val chapterNumber: ChapterNumber?,
        val chapterName: String?,
        val hasPreviousChapter: Boolean,
        val hasNextChapter: Boolean
    ) : ReaderState
}

/**
 * The bare identity of a chapter — enough to title a transition slide ("Up next: Chapter 12") without
 * pulling the whole chapter into UI state.
 *
 * @property id Local chapter id.
 * @property number Recognized chapter number, or `null` when the source didn't number it.
 * @property name The chapter's source title, or `null` (the slide falls back to "Chapter N").
 */
@Immutable
data class ChapterRef(
    val id: Uuid,
    val number: ChapterNumber?,
    val name: String?
)

/**
 * One slot in the reader's continuous scroll: either a chapter [PageItem] or a [Transition] slide
 * bridging two adjacent chapters. Modelling the slides as items (rather than an out-of-band overlay)
 * lets the same pager/list carry them, so crossing into the next chapter feels like reading one more
 * page. [key] is stable per slot for lazy-list reuse and for re-anchoring the viewport when the window
 * slides.
 *
 * `@Immutable` throughout, including on [ReaderState.Content] which holds a `List` of these. That is a
 * promise the compiler does not verify, and the code keeps it: the list comes from `ReaderViewModel`'s
 * `buildItems`, which owns it and never mutates it after handing it over, and every field below is an
 * immutable scalar or a data class of them. Without the promise Compose infers `List<ReaderItem>` — and
 * so `Content` — as **unstable**, and nothing in the reader's page tree can ever skip a recomposition.
 */
@Immutable
sealed interface ReaderItem {
    val key: String

    /**
     * A single page of a specific chapter.
     *
     * @property chapterId The chapter this page belongs to (the window holds several).
     * @property pageNumber Zero-based index of this page **within its chapter**.
     * @property pageCount Total pages in that chapter (so a focused page can tell if it's the last).
     * @property page Where this page's image comes from — the source, or a downloaded copy on disk. The
     *   reader does not distinguish the two beyond building the image request; see [ReaderPage].
     */
    data class PageItem(
        val chapterId: Uuid,
        val pageNumber: Int,
        val pageCount: Int,
        val page: ReaderPage
    ) : ReaderItem {
        override val key: String get() = "page:$chapterId:$pageNumber"
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
 */
sealed interface ReaderCommand {
    /** Target item index to scroll to. */
    val index: Int

    /**
     * Keep the viewport on the same logical page after the item list was re-indexed above it — an adjacent
     * chapter's pages inserted, or the chapter two behind evicted.
     *
     * Mostly belt-and-braces: both layouts are keyed by [ReaderItem.key] and restore their own position
     * during measure. But Compose builds its key→index map only over a window of roughly a hundred items
     * around the last known position, and outside it the lookup silently keeps the stale index — so a
     * shift larger than that (a long webtoon chapter leaving the window) would jump. This index is
     * computed against the freshly emitted list and is subject to no such bound. Paged layouts apply it;
     * the strip ignores it, since re-issuing a scroll it has already made itself would fight the user.
     */
    data class Reanchor(override val index: Int) : ReaderCommand

    /** An explicit user jump (scrubber, or the prev/next-chapter buttons). Every layout applies it. */
    data class Jump(override val index: Int) : ReaderCommand
}