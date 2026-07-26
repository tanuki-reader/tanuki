package app.tanuki.features.reader.viewmodel

import app.tanuki.core.domain.chapter.model.ChapterNumber
import app.tanuki.core.domain.manga.model.ReadingMode
import app.tanuki.core.domain.source.model.Page

/**
 * Immutable UI state for the reader, collected by `ReaderScreen`. Compose-free (per CLAUDE.md → MVVM):
 * every field is data the UI renders, never a Compose type.
 *
 * Deliberately three states rather than one struct with nullable everything: the reader is either
 * fetching its page list, showing pages, or reporting that the fetch failed, and those are mutually
 * exclusive. A page list can be legitimately empty ([Content] with no [Content.pages]) — that is a
 * source serving no pages, distinct from [Error] (we couldn't ask).
 */
sealed interface ReaderState {
    /** Pages are being fetched — the first thing shown when the reader opens. */
    data object Loading : ReaderState

    /** The page fetch failed (no network, source error). The UI offers a retry. */
    data object Error : ReaderState

    /**
     * Pages are ready.
     *
     * @property pages The chapter's pages in source order. May be empty.
     * @property currentPage Zero-based index of the visible page; where the UI's pager/list is parked.
     * @property readingMode How pages are laid out (paged L→R / R→L, or a vertical strip).
     * @property chromeVisible Whether the top/bottom reader chrome is shown; toggled by tapping.
     * @property chapterNumber Recognized number of the current chapter, for the "Chapter N" title;
     *   `null` when the source didn't number it.
     * @property chapterName The current chapter's title, or `null` when the source gives none (the UI
     *   falls back to "Chapter N").
     * @property hasPreviousChapter Whether a lower-numbered sibling chapter exists to page back into.
     * @property hasNextChapter Whether a higher-numbered sibling chapter exists to page forward into.
     */
    data class Content(
        val pages: List<Page>,
        val currentPage: Int,
        val readingMode: ReadingMode,
        val chromeVisible: Boolean,
        val chapterNumber: ChapterNumber?,
        val chapterName: String?,
        val hasPreviousChapter: Boolean,
        val hasNextChapter: Boolean
    ) : ReaderState
}