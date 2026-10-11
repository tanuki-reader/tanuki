package app.tanuki.features.reader.viewmodel

import app.tanuki.core.domain.chapter.model.Chapter
import app.tanuki.core.domain.manga.model.ReadingMode
import kotlin.uuid.Uuid

/**
 * The chapter spine the reader pages along, plus the facts that describe how to render it.
 *
 * Paired with [ReaderWindow]: this is *what chapters exist and in what order*, the window is *which of
 * them is being read and whose pages are held*. Keeping them apart is what lets the spine change — a
 * background library update landing a chapter — without disturbing the window, since the window names
 * its chapter by id rather than by position.
 *
 * @property siblings All sibling chapters the reader may page through, ascending by number, with the
 *   scanlator groups the user filtered out already removed.
 * @property mangaTitle The series' title, for the chrome.
 * @property readingMode How pages are laid out.
 * @property initialItemKey The item a layout parks on when it is first composed: the resume page (or, after
 *   a reading-mode switch, the page that was on screen). A key rather than an index because the list can
 *   change before a layout parks — the previous chapter warmed in above it — and an index taken against
 *   the first list would then name a page of the wrong chapter. `Content.initialItemIndex` resolves it
 *   against the list being shown.
 */
internal data class ReaderSession(
    val siblings: List<Chapter>,
    val mangaTitle: String,
    val readingMode: ReadingMode,
    val initialItemKey: String?
) {
    /**
     * Sibling position by chapter id. Built once with the session rather than scanned per event: every
     * page focus, every transition and every eviction needs this lookup, and `siblings` is uncapped —
     * a long-running series is hundreds of chapters, which a linear scan would walk on each page turn.
     *
     * Derived, so it is declared in the body rather than as a constructor parameter: it cannot drift from
     * [siblings], and `equals` still compares only the spine itself.
     */
    private val positionById: Map<Uuid, Int> =
        siblings.withIndex().associate { (index, chapter) -> chapter.id to index }

    /** [chapterId]'s position in [siblings], or `-1` when it isn't part of this spine (or is `null`). */
    fun positionOf(chapterId: Uuid?): Int = chapterId?.let { positionById[it] } ?: -1
}
