package app.tanuki.features.reader.viewmodel

import app.tanuki.core.domain.chapter.model.Chapter
import app.tanuki.core.domain.chapter.model.ChapterNumber
import app.tanuki.core.domain.chapter.model.PageImage
import app.tanuki.core.domain.chapter.model.ReaderPage
import app.tanuki.core.domain.chapter.model.ReadingProgress
import app.tanuki.core.domain.chapter.usecase.GetReaderPages
import app.tanuki.core.domain.chapter.usecase.ObserveChapters
import app.tanuki.core.domain.chapter.usecase.UpdateReadingProgress
import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.common.model.Url
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.common.service.LogPriority
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.manga.model.Manga
import app.tanuki.core.domain.manga.model.MangaCover
import app.tanuki.core.domain.manga.model.PublishStatus
import app.tanuki.core.domain.manga.model.ReadingMode
import app.tanuki.core.domain.manga.model.ReadingStatus
import app.tanuki.core.domain.manga.usecase.ObserveManga
import app.tanuki.features.reader.viewmodel.ReaderItem.PageItem
import app.tanuki.features.reader.viewmodel.ReaderState.Content
import app.tanuki.features.reader.viewmodel.ReaderState.Error
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The reader's window bookkeeping — the part item 4.1 rewrote.
 *
 * Two groups. The first pins the contract that existed *before* eviction (resume, retry, warming,
 * transition spinners, progress writes) and is deliberately unchanged by it: those cases were written
 * first and had to stay green through the refactor. The second is eviction itself.
 *
 * `uiState` is a plain [kotlinx.coroutines.flow.MutableStateFlow] here, not `WhileSubscribed`, so unlike
 * the other feature ViewModels no collector is needed to make it run. [ReaderViewModel.commands] *is*
 * single-consumer ([kotlinx.coroutines.channels.Channel]), so a case that asserts commands attaches one
 * collector and cancels it at the end.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelTest {
    private val mainDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---- The contract eviction had to preserve --------------------------------------------------

    @Test
    fun `the reader opens on the resume page of the requested chapter`() = runTest {
        val world = World(resumePageOfStart = 2)

        val viewModel = world.viewModel()
        runCurrent()

        val content = viewModel.content()
        assertEquals(world.chapterIds[START], content.currentChapterId)
        assertEquals(2, content.currentPage)
        assertEquals(PAGES, content.currentPageCount)
        // One leading transition sits above the pages, so the resume page is offset by one.
        assertEquals(3, content.initialItemIndex)
    }

    @Test
    fun `a failed first page fetch shows the error state and retry reloads`() = runTest {
        val world = World()
        val gate = world.pages.gate(world.chapterIds[START])

        val viewModel = world.viewModel()
        runCurrent()
        world.pages.failing += world.chapterIds[START]
        gate.complete(Unit)
        runCurrent()
        assertEquals(Error, viewModel.uiState.value)

        world.pages.failing.clear()
        viewModel.onRetry()
        runCurrent()

        assertEquals(world.chapterIds[START], viewModel.content().currentChapterId)
    }

    @Test
    fun `a load cancelled while it is resolving does not paint an error`() = runTest {
        val world = World(cancelWhileLoading = true)

        val viewModel = world.viewModel()
        runCurrent()

        // The load coroutine unwound; nothing replaced the Loading the pipeline put up. Reporting an
        // error here would paint over a screen that is already going away.
        assertEquals(ReaderState.Loading, viewModel.uiState.value)
    }

    @Test
    fun `the next chapter is warmed as soon as the reader opens`() = runTest {
        val world = World()

        val viewModel = world.viewModel()
        runCurrent()

        assertEquals(world.chapterIds.slice(START..START + 1), viewModel.content().loadedChapterIds)
    }

    @Test
    fun `a transition slide spins while the chapter behind it loads`() = runTest {
        val world = World()
        val gate = world.pages.gate(world.chapterIds[START + 1])

        val viewModel = world.viewModel()
        runCurrent()

        val trailing = viewModel.content().items.last()
        assertTrue(trailing is ReaderItem.Transition && trailing.loading)

        gate.complete(Unit)
        runCurrent()
        assertTrue(viewModel.content().items.none { it is ReaderItem.Transition && it.loading })
    }

    @Test
    fun `a burst of page turns writes progress once`() = runTest {
        val world = World()
        val viewModel = world.viewModel()
        runCurrent()
        val onOpen = world.progress.writes.size

        viewModel.onItemFocused(2)
        viewModel.onItemFocused(3)
        viewModel.onItemFocused(4)
        advanceTimeBy(ReaderViewModel.PROGRESS_PERSIST_DEBOUNCE_MS + 1)
        runCurrent()

        assertEquals(onOpen + 1, world.progress.writes.size)
        assertEquals(ProgressWrite(world.chapterIds[START], page = 3, read = true), world.progress.writes.last())
    }

    @Test
    fun `focusing the trailing transition marks the finished chapter read`() = runTest {
        val world = World()
        val viewModel = world.viewModel()
        runCurrent()

        viewModel.onItemFocused(viewModel.content().indexOfTrailingTransition())
        advanceTimeBy(ReaderViewModel.PROGRESS_PERSIST_DEBOUNCE_MS + 1)
        runCurrent()

        assertEquals(
            ProgressWrite(world.chapterIds[START], page = PAGES - 1, read = true),
            world.progress.writes.last()
        )
    }

    @Test
    fun `focusing the leading transition does not mark the previous chapter read`() = runTest {
        val world = World()
        val viewModel = world.viewModel()
        runCurrent()

        viewModel.onItemFocused(0)
        advanceTimeBy(ReaderViewModel.PROGRESS_PERSIST_DEBOUNCE_MS + 1)
        runCurrent()

        assertTrue(world.progress.writes.none { it.chapterId == world.chapterIds[START - 1] })
    }

    @Test
    fun `the scrubber jumps to the requested page of the current chapter`() = runTest {
        val world = World()
        val viewModel = world.viewModel()
        runCurrent()
        val commands = mutableListOf<ReaderCommand>()
        val job = collectCommands(viewModel, commands)

        viewModel.onScrubToPage(2)
        runCurrent()

        val jump = commands.single()
        assertTrue(jump is ReaderCommand.Jump)
        val landed = viewModel.content().items[jump.index]
        assertTrue(landed is PageItem)
        assertEquals(world.chapterIds[START], landed.chapterId)
        assertEquals(2, landed.pageNumber)
        job.cancel()
    }

    // ---- The item list is rebuilt only when the held chapters change --------------------------------

    @Test
    fun `a page turn inside a chapter does not rebuild the item list`() = runTest {
        val world = World()
        val viewModel = world.viewModel()
        runCurrent()
        val before = viewModel.content().items

        viewModel.onItemFocused(2)
        runCurrent()

        // Identity, not equality: an equal-but-fresh list would pass `assertEquals` while still costing
        // the allocation and, downstream, a recomposition of the whole page tree.
        assertSame(before, viewModel.content().items)
        assertEquals(1, viewModel.content().currentPage)
    }

    @Test
    fun `a chrome toggle does not rebuild the item list`() = runTest {
        val world = World()
        val viewModel = world.viewModel()
        runCurrent()
        val before = viewModel.content().items

        viewModel.onToggleChrome()
        runCurrent()

        assertSame(before, viewModel.content().items)
        assertFalse(viewModel.content().chromeVisible)
    }

    @Test
    fun `a crossing that needs no fetch keeps the item list it already had`() = runTest {
        val world = World()
        val viewModel = world.viewModel()
        runCurrent()
        viewModel.crossInto(world.chapterIds[START + 1])
        runCurrent()
        val before = viewModel.content().items

        // Crossing back: both neighbours are already held, so nothing loads and no spinner flips. The
        // window still moves, and the list is still rebuilt — but it comes out structurally identical and
        // the pipeline hands the previous instance on rather than the new one. (A *forward* crossing warms
        // the chapter after next, which genuinely does change the list.)
        viewModel.crossInto(world.chapterIds[START])
        runCurrent()

        assertSame(before, viewModel.content().items)
        assertEquals(world.chapterIds[START], viewModel.content().currentChapterId)
    }

    // ---- Eviction -------------------------------------------------------------------------------

    @Test
    fun `the window holds the opened chapter and its successor`() = runTest {
        val world = World()
        val viewModel = world.viewModel()

        runCurrent()
        advanceTimeBy(ReaderViewModel.EVICT_QUIET_MS + 1)
        runCurrent()

        assertEquals(world.chapterIds.slice(START..START + 1), viewModel.content().loadedChapterIds)
    }

    @Test
    fun `crossing forward drops the chapter two behind`() = runTest {
        val world = World()
        val viewModel = world.viewModel()
        runCurrent()
        // Page back into the leading transition so the previous chapter is loaded too.
        viewModel.onItemFocused(0)
        runCurrent()
        assertEquals(world.chapterIds.slice(START - 1..START + 1), viewModel.content().loadedChapterIds)

        viewModel.crossInto(world.chapterIds[START + 1])
        advanceTimeBy(ReaderViewModel.EVICT_QUIET_MS + 1)
        runCurrent()

        assertEquals(world.chapterIds.slice(START..START + 2), viewModel.content().loadedChapterIds)
    }

    @Test
    fun `crossing backward drops the chapter two ahead`() = runTest {
        val world = World()
        val viewModel = world.viewModel()
        runCurrent()
        viewModel.crossInto(world.chapterIds[START + 1])
        advanceTimeBy(ReaderViewModel.EVICT_QUIET_MS + 1)
        runCurrent()
        assertEquals(world.chapterIds.slice(START..START + 2), viewModel.content().loadedChapterIds)

        viewModel.crossInto(world.chapterIds[START])
        advanceTimeBy(ReaderViewModel.EVICT_QUIET_MS + 1)
        runCurrent()

        // The predecessor is loaded lazily (only when its leading slide is focused), so stepping back
        // leaves two held chapters, not three — what matters is that the one two ahead is gone.
        assertEquals(world.chapterIds.slice(START..START + 1), viewModel.content().loadedChapterIds)
    }

    @Test
    fun `the item list stays bounded while reading through the whole series`() = runTest {
        val world = World(chapterCount = 12)
        val viewModel = world.viewModel()
        runCurrent()

        for (index in START + 1..11) {
            viewModel.crossInto(world.chapterIds[index])
            advanceTimeBy(ReaderViewModel.EVICT_QUIET_MS + 1)
            runCurrent()

            val content = viewModel.content()
            // Three chapters of pages, plus their four boundary slides (leading, two interior, trailing).
            assertTrue(content.items.size <= 3 * PAGES + 4, "item list grew to ${content.items.size} at chapter $index")
            assertTrue(content.loadedChapterIds.size <= 3)
            assertTrue(content.currentChapterId in content.loadedChapterIds)
        }
    }

    @Test
    fun `pages that arrive for a chapter outside the window are dropped`() = runTest {
        val world = World()
        val viewModel = world.viewModel()
        runCurrent()
        // Start the previous chapter's fetch, then walk away from it before it lands.
        val gate = world.pages.gate(world.chapterIds[START - 1])
        viewModel.onItemFocused(0)
        runCurrent()

        viewModel.crossInto(world.chapterIds[START + 1])
        advanceTimeBy(ReaderViewModel.EVICT_QUIET_MS + 1)
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        val content = viewModel.content()
        assertFalse(world.chapterIds[START - 1] in content.loadedChapterIds)
        assertEquals(3, content.loadedChapterIds.size)
    }

    @Test
    fun `a fetch dropped as out of window still clears its spinner`() = runTest {
        val world = World()
        val viewModel = world.viewModel()
        runCurrent()
        val gate = world.pages.gate(world.chapterIds[START - 1])
        viewModel.onItemFocused(0)
        runCurrent()

        viewModel.crossInto(world.chapterIds[START + 1])
        advanceTimeBy(ReaderViewModel.EVICT_QUIET_MS + 1)
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertTrue(viewModel.content().items.none { it is ReaderItem.Transition && it.loading })
    }

    @Test
    fun `oscillating across a boundary re-fetches neither chapter`() = runTest {
        val world = World()
        val viewModel = world.viewModel()
        runCurrent()

        repeat(3) {
            viewModel.crossInto(world.chapterIds[START + 1])
            runCurrent()
            viewModel.crossInto(world.chapterIds[START])
            runCurrent()
        }

        assertEquals(1, world.pages.calls[world.chapterIds[START]])
        assertEquals(1, world.pages.calls[world.chapterIds[START + 1]])
    }

    @Test
    fun `eviction does not run while the reader is still moving`() = runTest {
        val world = World()
        val viewModel = world.viewModel()
        runCurrent()
        viewModel.onItemFocused(0)
        runCurrent()

        viewModel.crossInto(world.chapterIds[START + 1])
        runCurrent()
        assertEquals(4, viewModel.content().loadedChapterIds.size)

        advanceTimeBy(ReaderViewModel.EVICT_QUIET_MS + 1)
        runCurrent()
        assertEquals(3, viewModel.content().loadedChapterIds.size)
    }

    // ---- The spine is live ---------------------------------------------------------------------

    @Test
    fun `a chapter that lands while reading joins the spine`() = runTest {
        // Three chapters, opened on the middle one, so the last is warmed and can actually be paged into.
        val world = World(chapterCount = 3)
        val viewModel = world.viewModel()
        runCurrent()
        viewModel.crossInto(world.chapterIds.last())
        runCurrent()
        assertFalse(viewModel.content().hasNextChapter)

        world.appendChapter()
        runCurrent()

        assertTrue(viewModel.content().hasNextChapter, "a library update's new chapter never arrived")
    }

    @Test
    fun `a chapter pruned elsewhere in the series leaves the spine`() = runTest {
        val world = World()
        val viewModel = world.viewModel()
        runCurrent()
        viewModel.crossInto(world.chapterIds[START + 1])
        runCurrent()
        assertTrue(viewModel.content().hasNextChapter)

        // Everything after the one being read is delisted.
        world.chapterIds.drop(START + 2).forEach(world::removeChapter)
        runCurrent()

        assertFalse(viewModel.content().hasNextChapter)
        assertEquals(world.chapterIds[START + 1], viewModel.content().currentChapterId)
    }

    @Test
    fun `a spine that drops the chapter being read is ignored`() = runTest {
        val world = World()
        val viewModel = world.viewModel()
        runCurrent()
        val before = viewModel.content()

        world.removeChapter(world.chapterIds[START])
        runCurrent()

        // Keeping a delisted chapter on screen beats emptying the reader under someone mid-chapter.
        val after = viewModel.content()
        assertEquals(before.currentChapterId, after.currentChapterId)
        assertTrue(after.items.isNotEmpty())
    }

    @Test
    fun `the reader's own progress writes do not re-project the spine`() = runTest {
        val world = World()
        val viewModel = world.viewModel()
        runCurrent()
        val before = viewModel.content().items

        viewModel.onItemFocused(2)
        advanceTimeBy(ReaderViewModel.PROGRESS_PERSIST_DEBOUNCE_MS + 1)
        runCurrent()

        // The write re-emitted the chapter list (see World.progress). Only `progress` changed, which no
        // session reads — so the spine gate must swallow it. Without that, every page turn would rebuild
        // the session and the item list under the user.
        assertTrue(world.progress.writes.isNotEmpty(), "the write never happened, so this proves nothing")
        assertSame(before, viewModel.content().items)
    }

    // ---- Re-anchoring ---------------------------------------------------------------------------

    @Test
    fun `a forward crossing re-anchors to the same page`() = runTest {
        val world = World()
        val viewModel = world.viewModel()
        runCurrent()
        viewModel.onItemFocused(0)
        runCurrent()
        val commands = mutableListOf<ReaderCommand>()
        val job = collectCommands(viewModel, commands)

        viewModel.crossInto(world.chapterIds[START + 1])
        runCurrent()
        val focusedKey = viewModel.content().items[viewModel.indexOfFirstPage(world.chapterIds[START + 1])].key
        advanceTimeBy(ReaderViewModel.EVICT_QUIET_MS + 1)
        runCurrent()

        val reanchor = commands.filterIsInstance<ReaderCommand.Reanchor>().last()
        assertEquals(focusedKey, viewModel.content().items[reanchor.index].key)
        job.cancel()
    }

    @Test
    fun `no command is sent for a page turn inside a chapter`() = runTest {
        val world = World()
        val viewModel = world.viewModel()
        runCurrent()
        val commands = mutableListOf<ReaderCommand>()
        val job = collectCommands(viewModel, commands)

        viewModel.onItemFocused(2)
        viewModel.onItemFocused(3)
        advanceTimeBy(ReaderViewModel.EVICT_QUIET_MS + 1)
        runCurrent()

        assertTrue(commands.isEmpty(), "unexpected commands: $commands")
        job.cancel()
    }

    @Test
    fun `no command is sent when eviction changes nothing`() = runTest {
        val world = World()
        val viewModel = world.viewModel()
        runCurrent()
        val commands = mutableListOf<ReaderCommand>()
        val job = collectCommands(viewModel, commands)

        advanceTimeBy(ReaderViewModel.EVICT_QUIET_MS * 4)
        runCurrent()

        assertTrue(commands.isEmpty(), "unexpected commands: $commands")
        job.cancel()
    }

    @Test
    fun `the leading transition still loads its predecessor after an eviction`() = runTest {
        val world = World()
        val viewModel = world.viewModel()
        runCurrent()
        viewModel.crossInto(world.chapterIds[START + 1])
        advanceTimeBy(ReaderViewModel.EVICT_QUIET_MS + 1)
        runCurrent()

        // A flung strip can reach the leading slide with every page in between conflated away.
        viewModel.onItemFocused(0)
        runCurrent()

        val content = viewModel.content()
        assertTrue(
            world.chapterIds[START - 1] in content.loadedChapterIds,
            "predecessor never loaded: ${content.loadedChapterIds}"
        )
    }

    @Test
    fun `a pending jump whose target leaves the window is abandoned`() = runTest {
        val world = World()
        val viewModel = world.viewModel()
        runCurrent()
        viewModel.crossInto(world.chapterIds[START + 1])
        advanceTimeBy(ReaderViewModel.EVICT_QUIET_MS + 1)
        runCurrent()
        val gate = world.pages.gate(world.chapterIds[START + 3])

        viewModel.crossInto(world.chapterIds[START + 2])
        runCurrent()
        viewModel.onNextChapter() // arms a jump to START + 3, whose fetch is gated
        runCurrent()
        viewModel.crossInto(world.chapterIds[START])
        advanceTimeBy(ReaderViewModel.EVICT_QUIET_MS + 1)
        runCurrent()
        val commands = mutableListOf<ReaderCommand>()
        val job = collectCommands(viewModel, commands)
        gate.complete(Unit)
        runCurrent()

        assertNull(commands.filterIsInstance<ReaderCommand.Jump>().firstOrNull())
        job.cancel()
    }

    // ---- Helpers --------------------------------------------------------------------------------

    private fun ReaderViewModel.content(): Content {
        val state = uiState.value
        assertTrue(state is Content, "expected content, was $state")
        return state
    }

    /** Focuses the first page of [chapterId], the way the viewport reports crossing a boundary. */
    private fun ReaderViewModel.crossInto(chapterId: Uuid) = onItemFocused(indexOfFirstPage(chapterId))

    private fun ReaderViewModel.indexOfFirstPage(chapterId: Uuid): Int =
        content().items.indexOfFirst { it is PageItem && it.chapterId == chapterId && it.pageNumber == 0 }

    private fun TestScope.collectCommands(
        viewModel: ReaderViewModel,
        into: MutableList<ReaderCommand>
    ): Job {
        val job = launch { viewModel.commands.collect { into += it } }
        runCurrent()
        return job
    }

    private companion object {
        const val PAGES = 4

        /** The chapter the reader opens on — deliberately not the first, so it has a predecessor. */
        const val START = 1
    }

    /** The fakes one case needs, built together so each reads as its own scenario. */
    private class World(
        chapterCount: Int = 6,
        resumePageOfStart: Int = 0,
        private val cancelWhileLoading: Boolean = false
    ) {
        val chapterIds: List<Uuid> = List(chapterCount) { Uuid.random() }
        private val mangaId = Uuid.random()
        val pages = FakeGetReaderPages(PAGES)

        /**
         * Re-emits the chapter list on every write, the way Room does: `ChapterDao.observeChapters` joins
         * `reading_progress`, so the reader's own page turns invalidate the very query its spine is
         * projected from. A fake that swallowed the write would make the spine gate untestable — this is
         * the pressure the gate exists to withstand.
         */
        val progress =
            FakeUpdateReadingProgress { chapterId, page, read ->
                chapters.update { list ->
                    list.map { chapter ->
                        if (chapter.id != chapterId) {
                            chapter
                        } else {
                            chapter.copy(
                                progress =
                                    ReadingProgress(
                                        hasBookmark = false,
                                        lastPageRead = page.toUInt(),
                                        alreadyRead = read
                                    )
                            )
                        }
                    }
                }
            }

        val chapters =
            MutableStateFlow(
                chapterIds.mapIndexed { index, id ->
                    chapter(
                        id = id,
                        mangaId = mangaId,
                        number = index + 1,
                        progress =
                            if (index == START && resumePageOfStart > 0) {
                                ReadingProgress(
                                    hasBookmark = false,
                                    lastPageRead = resumePageOfStart.toUInt(),
                                    alreadyRead = false
                                )
                            } else {
                                null
                            }
                    )
                }
            )

        val manga = MutableStateFlow<Manga?>(manga(mangaId))

        /** Drops [id] from the spine, as a library update pruning a delisted chapter would. */
        fun removeChapter(id: Uuid) = chapters.update { list -> list.filterNot { it.id == id } }

        /** Appends a chapter after the last one, as a library update landing a new release would. */
        fun appendChapter(): Uuid {
            val id = Uuid.random()
            chapters.update { list -> list + chapter(id, mangaId, list.size + 1, progress = null) }
            return id
        }

        /**
         * The manga flow the ViewModel reads once in `load()` — or, with [cancelWhileLoading], one that
         * cancels there instead. A switch rather than a second fake: Kotlin/Native rejects two
         * identically-named private top-level declarations in the same package.
         */
        private fun mangaFlow(): Flow<Manga?> =
            if (cancelWhileLoading) flow { throw CancellationException("reader closed") } else manga

        fun viewModel(): ReaderViewModel =
            ReaderViewModel(
                mangaId = mangaId,
                initialChapterId = chapterIds[START],
                getReaderPages = pages,
                updateReadingProgress = progress,
                observeChapters = FakeObserveChapters(chapters),
                observeManga = FakeObserveManga(mangaFlow()),
                dispatcher = ImmediateDispatchers,
                logger = SilentLogger
            )
    }
}

private val Content.loadedChapterIds: List<Uuid>
    get() = items.filterIsInstance<PageItem>().map { it.chapterId }.distinct()

private fun Content.indexOfTrailingTransition(): Int =
    items.indexOfLast { it is ReaderItem.Transition && it.from.id == currentChapterId }

private data class ProgressWrite(
    val chapterId: Uuid,
    val page: Int,
    val read: Boolean
)

/**
 * Hands back [pagesPerChapter] synthetic pages, counting calls and optionally holding one open.
 *
 * Always suspends at least once. The real use case reaches the network or the filesystem, so its
 * `withContext(io)` genuinely dispatches; a fake that answered inline would let `load()` publish its
 * result *before* the content pipeline has emitted its first `Loading`, which no real fetch can do.
 */
private class FakeGetReaderPages(
    private val pagesPerChapter: Int
) : GetReaderPages {
    val calls = mutableMapOf<Uuid, Int>()
    val failing = mutableSetOf<Uuid>()
    private val gates = mutableMapOf<Uuid, CompletableDeferred<Unit>>()

    fun gate(chapterId: Uuid): CompletableDeferred<Unit> =
        CompletableDeferred<Unit>().also { gates[chapterId] = it }

    override suspend fun invoke(chapterId: Uuid): List<ReaderPage> {
        calls[chapterId] = (calls[chapterId] ?: 0) + 1
        yield()
        gates[chapterId]?.await()
        check(chapterId !in failing) { "page fetch refused for $chapterId" }
        return List(pagesPerChapter) { page ->
            ReaderPage(PageImage.Remote(Url("https://example.com/$chapterId/$page.png")))
        }
    }
}

private class FakeUpdateReadingProgress(
    private val onWrite: (Uuid, Int, Boolean) -> Unit
) : UpdateReadingProgress {
    val writes = mutableListOf<ProgressWrite>()

    override suspend fun invoke(
        chapterId: Uuid,
        lastPageRead: UInt,
        read: Boolean
    ) {
        writes += ProgressWrite(chapterId, lastPageRead.toInt(), read)
        onWrite(chapterId, lastPageRead.toInt(), read)
    }
}

private class FakeObserveChapters(
    private val chapters: Flow<List<Chapter>>
) : ObserveChapters {
    override fun invoke(mangaId: Uuid): Flow<List<Chapter>> = chapters
}

private class FakeObserveManga(
    private val manga: Flow<Manga?>
) : ObserveManga {
    override fun invoke(mangaId: Uuid): Flow<Manga?> = manga
}

private object ImmediateDispatchers : DispatcherProvider {
    override val default: CoroutineDispatcher get() = Dispatchers.Main
    override val io: CoroutineDispatcher get() = Dispatchers.Main
    override val main: CoroutineDispatcher get() = Dispatchers.Main
}

private object SilentLogger : Logger {
    override fun log(
        priority: LogPriority,
        tag: String?,
        message: String?,
        throwable: Throwable?,
        analyticsEvent: String?,
        addToBreadcrumbs: Boolean
    ) = Unit

    override fun addReceiver(receiver: Logger.LogReceiver) = Unit

    override fun removeReceiver(receiver: Logger.LogReceiver) = Unit
}

private fun chapter(
    id: Uuid,
    mangaId: Uuid,
    number: Int,
    progress: ReadingProgress?
): Chapter =
    Chapter(
        id = id,
        mangaId = mangaId,
        chapterNumber = ChapterNumber(number),
        name = Text("Chapter $number"),
        scanlator = null,
        sourceChapterKey = Text("ch-$number"),
        url = Url("https://example.com/chapter/$number"),
        dateUpload = null,
        dateFetch = Instant.DISTANT_PAST,
        progress = progress
    )

private fun manga(id: Uuid): Manga =
    Manga(
        id = id,
        source = Uuid.random(),
        title = Text("Berserk"),
        author = null,
        description = null,
        cover = MangaCover(mangaId = id, url = null, lastModified = Instant.DISTANT_PAST),
        url = Url("https://example.com/berserk"),
        dateAdded = null,
        readingMode = ReadingMode.RIGHT_TO_LEFT,
        publishStatus = PublishStatus.ONGOING,
        readingStatus = ReadingStatus.READING
    )
