package app.tanuki.features.reader.viewmodel

import app.tanuki.core.domain.chapter.model.ReaderPage
import kotlin.uuid.Uuid

/**
 * The reader's sliding window: which chapter is being read, whose pages are held, and which fetches are
 * in flight. One value rather than three flows, because the index and the page cache must never be
 * observed apart.
 *
 * The asymmetry that forces it: a cache *larger* than the window implies is harmless — that is simply a
 * window that has not been trimmed yet. A cache *smaller* than it implies is not: dropping the chapter
 * the viewport is standing in makes `buildItems` return an empty list, which the UI renders as a "no
 * pages" empty state in the middle of a read. Holding both in one value makes that unrepresentable,
 * since every transition below leaves a consistent pair.
 *
 * ## Why a chapter id and not a position
 *
 * [currentChapterId] names the chapter being read; its *position* is resolved against a [ReaderSession]
 * only where a radius has to be measured. A position would be meaningful only against one spine, and the
 * spine is live — a background library update can insert or drop a chapter mid-read, shifting every index
 * under the reader. An id survives that untouched, and it is also what every caller already has in hand:
 * a focused page carries its own `chapterId`, so the hot path needs no lookup at all.
 *
 * ## What is retained, and when
 *
 * At most three chapters — [RADIUS] on either side of [index], i.e. previous · current · next. Two rules
 * keep that honest:
 *
 * - **Moving and evicting are separate transitions.** [movedTo] only moves; [evicted] only drops. They
 *   are split because the index moves *mid-gesture*, not on settle: a pager's `currentPage` flips once a
 *   drag passes the snap halfway point, and a strip's `firstVisibleItemIndex` changes throughout a fling.
 *   Evicting on the move would pull items out from above a viewport the user still has a finger on. So
 *   the ViewModel debounces eviction on a quiet index (`EVICT_QUIET_MS`), which also makes oscillating
 *   across a boundary free — it never drops what it is about to need again.
 * - **[withPages] ignores an insert from outside the radius.** A fetch started before the window moved
 *   on must not put the chapter back, or the window would grow by one every time the user out-ran a load.
 *
 * ## The invariant `buildItems` depends on
 *
 * The retained chapters stay **contiguous**, which is what lets `buildItems` recover the run by walking
 * outward from [index]. Loads only ever expand the run from the current chapter, and [evicted]
 * intersects a contiguous set with an interval that contains [index] — still contiguous. Note this is
 * *not* the invariant the reader used to rely on ("the list only grows"): a forward crossing now removes
 * the chapter behind, so item indices shift down and the viewport has to be re-anchored.
 *
 * @property index Index into the session's sibling chapters of the chapter being read.
 * @property pages Fetched pages of the retained chapters, keyed by chapter id.
 * @property loading Chapters whose page fetch is in flight — so a transition slide can show a spinner
 *   and the same chapter is never fetched twice.
 */
internal data class ReaderWindow(
    val currentChapterId: Uuid?,
    val pages: Map<Uuid, List<ReaderPage>>,
    val loading: Set<Uuid>
) {
    /** Whether this chapter's pages are held right now. */
    fun holds(chapterId: Uuid): Boolean = pages.containsKey(chapterId)

    /** Moves onto another chapter. Deliberately does **not** evict — see the class KDoc. */
    fun movedTo(chapterId: Uuid): ReaderWindow =
        if (chapterId == currentChapterId) this else copy(currentChapterId = chapterId)

    fun loadingStarted(chapterId: Uuid): ReaderWindow =
        if (chapterId in loading) this else copy(loading = loading + chapterId)

    /** Runs unconditionally, so a result dropped by [withPages] never strands its spinner. */
    fun loadingFinished(chapterId: Uuid): ReaderWindow =
        if (chapterId !in loading) this else copy(loading = loading - chapterId)

    /** Takes a completed fetch, unless the window has moved on past that chapter in the meantime. */
    fun withPages(
        session: ReaderSession,
        chapterId: Uuid,
        pages: List<ReaderPage>
    ): ReaderWindow =
        if (chapterId in retainedIds(session)) copy(pages = this.pages + (chapterId to pages)) else this

    /**
     * Drops every chapter that has fallen outside the radius. Returns `this` when nothing goes, so the
     * `MutableStateFlow` holding it conflates the no-op and the content pipeline does not re-run.
     */
    fun evicted(session: ReaderSession): ReaderWindow {
        val retained = retainedIds(session)
        if (retained.isEmpty()) return this
        val kept = pages.filterKeys { it in retained }
        return if (kept.size == pages.size) this else copy(pages = kept)
    }

    /**
     * The ids the window may hold. Empty when [currentChapterId] isn't part of [session]'s spine — a
     * window with no anchor has nothing to measure a radius from, and evicting against it would drop the
     * chapter on screen. That is also the state a spine change lands in when it removes the chapter being
     * read, so returning "retain everything" here is what keeps that case harmless.
     */
    private fun retainedIds(session: ReaderSession): Set<Uuid> {
        val index = session.positionOf(currentChapterId)
        if (index < 0) return emptySet()
        val siblings = session.siblings
        val lo = (index - RADIUS).coerceAtLeast(0)
        val hi = (index + RADIUS).coerceAtMost(siblings.lastIndex)
        return (lo..hi).mapTo(HashSet(hi - lo + 1)) { siblings[it].id }
    }

    internal companion object {
        /** Nothing read yet — what the window resets to on a (re)load. */
        val EMPTY = ReaderWindow(currentChapterId = null, pages = emptyMap(), loading = emptySet())

        /** Chapters kept on each side of the current one: one back, one forward, so three in all. */
        const val RADIUS = 1
    }
}
