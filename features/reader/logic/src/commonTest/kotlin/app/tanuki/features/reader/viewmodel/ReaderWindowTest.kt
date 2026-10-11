package app.tanuki.features.reader.viewmodel

import app.tanuki.core.domain.chapter.model.Chapter
import app.tanuki.core.domain.chapter.model.ChapterNumber
import app.tanuki.core.domain.chapter.model.PageImage
import app.tanuki.core.domain.chapter.model.ReaderPage
import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.common.model.Url
import app.tanuki.core.domain.manga.model.ReadingMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The window's radius arithmetic on its own — the part `buildItems` and both reader layouts trust.
 *
 * Worth testing here rather than only through `ReaderViewModelTest`: the two properties that matter
 * (the chapter being read is never dropped, and what survives stays contiguous) are statements about
 * this type, and reading them through a ViewModel, three pipelines and a debounce would say much less.
 *
 * The window names its chapter by **id**; positions come from the [ReaderSession] passed in, which is
 * what lets the spine change under an open reader without any of this needing to be remapped.
 */
class ReaderWindowTest {
    @Test
    fun `eviction keeps only the chapters adjacent to the one being read`() {
        val session = session(6)
        val window = windowOver(session, at = 3, held = 0..5)

        val evicted = window.evicted(session)

        assertEquals(session.ids(2..4), evicted.pages.keys)
    }

    @Test
    fun `eviction never drops the chapter being read`() {
        val session = session(6)

        for (index in session.siblings.indices) {
            val evicted = windowOver(session, at = index, held = 0..5).evicted(session)
            val current = session.siblings[index].id
            assertTrue(evicted.holds(current), "dropped the chapter being read, at position $index")
        }
    }

    @Test
    fun `eviction clamps at both ends of the series`() {
        val session = session(6)

        assertEquals(session.ids(0..1), windowOver(session, at = 0, held = 0..5).evicted(session).pages.keys)
        assertEquals(session.ids(4..5), windowOver(session, at = 5, held = 0..5).evicted(session).pages.keys)
    }

    @Test
    fun `evicting an already bounded window returns the same instance`() {
        val session = session(6)
        val window = windowOver(session, at = 3, held = 2..4)

        assertSame(window, window.evicted(session))
    }

    @Test
    fun `evicting against a chapter that has left the spine changes nothing`() {
        val session = session(6)
        // Exactly the shape a spine change lands in when it removes the chapter being read: the window has
        // no anchor, so there is no radius to measure and nothing may be dropped.
        val window = windowOver(session, at = 3, held = 2..4).movedTo(Uuid.random())

        assertSame(window, window.evicted(session))
    }

    @Test
    fun `what survives an eviction stays contiguous`() {
        val session = session(8)

        for (index in session.siblings.indices) {
            val evicted = windowOver(session, at = index, held = 0..7).evicted(session)
            val positions = session.siblings.indices.filter { session.siblings[it].id in evicted.pages }
            assertEquals(positions.last() - positions.first() + 1, positions.size, "held a gap: $positions")
        }
    }

    @Test
    fun `pages for a chapter outside the radius are ignored`() {
        val session = session(6)
        val window = windowOver(session, at = 1, held = 0..2)
        val distant = session.siblings[5].id

        val after = window.withPages(session, distant, listOf(page()))

        assertSame(window, after)
        assertFalse(after.holds(distant))
    }

    @Test
    fun `a fetch dropped as out of window still clears its loading flag`() {
        val session = session(6)
        val distant = session.siblings[5].id
        val window = windowOver(session, at = 1, held = 0..2).loadingStarted(distant)

        val after = window.loadingFinished(distant).withPages(session, distant, listOf(page()))

        assertFalse(after.holds(distant))
        assertTrue(after.loading.isEmpty())
    }

    @Test
    fun `moving to the chapter already being read returns the same instance`() {
        val session = session(6)
        val window = windowOver(session, at = 2, held = 1..3)

        assertSame(window, window.movedTo(session.siblings[2].id))
    }
}

private fun session(count: Int): ReaderSession =
    ReaderSession(
        siblings =
            List(count) { index ->
                Chapter(
                    id = Uuid.random(),
                    mangaId = MANGA_ID,
                    chapterNumber = ChapterNumber(index + 1),
                    name = Text("Chapter ${index + 1}"),
                    scanlator = null,
                    sourceChapterKey = Text("ch-${index + 1}"),
                    url = Url("https://example.com/chapter/${index + 1}"),
                    dateUpload = null,
                    dateFetch = Instant.DISTANT_PAST,
                    progress = null
                )
            },
        mangaTitle = "Berserk",
        readingMode = ReadingMode.RIGHT_TO_LEFT,
        initialItemIndex = 0
    )

private fun ReaderSession.ids(range: IntRange): Set<Uuid> = range.mapTo(mutableSetOf()) { siblings[it].id }

private fun windowOver(
    session: ReaderSession,
    at: Int,
    held: IntRange
): ReaderWindow =
    ReaderWindow(
        currentChapterId = session.siblings[at].id,
        pages = held.associate { session.siblings[it].id to listOf(page()) },
        loading = emptySet()
    )

private fun page(): ReaderPage = ReaderPage(PageImage.Remote(Url("https://example.com/page.png")))

private val MANGA_ID: Uuid = Uuid.random()
