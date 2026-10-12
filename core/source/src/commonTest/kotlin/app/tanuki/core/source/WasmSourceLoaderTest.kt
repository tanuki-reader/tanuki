package app.tanuki.core.source

import app.tanuki.core.domain.common.service.ConnectionObserver
import app.tanuki.core.domain.common.service.ConnectionObserver.ConnectionStatus
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.common.service.OfflineException
import app.tanuki.core.domain.manga.model.PublishStatus
import app.tanuki.core.domain.settings.usecase.ObserveShowSensitiveContent
import app.tanuki.core.domain.source.SourceBundleStore
import app.tanuki.core.domain.source.model.Source
import app.tanuki.core.domain.source.model.SourceListing
import app.tanuki.core.domain.source.repository.SourceRepository
import app.tanuki.core.source.abi.SourceAbi
import app.tanuki.core.source.abi.SourceAbiException
import app.tanuki.core.source.abi.SourceCodec
import app.tanuki.core.source.abi.Wire
import app.tanuki.core.source.runtime.SourceHost
import app.tanuki.core.source.runtime.WasmEngine
import app.tanuki.core.source.runtime.WasmModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tests [WasmSourceLoader]'s loader logic — bundle-store enumeration, the ABI call/decode pipeline,
 * source install-on-load, and resilience — against a fake [WasmEngine], in-memory [SourceBundleStore],
 * and in-memory [SourceRepository], so it's exercised independently of the platform engines (Chicory /
 * wasmi) and the Room-backed repository.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WasmSourceLoaderTest {
    @Test
    fun `observeInstalledSources reports each installed bundle's metadata`() =
        runTest {
            val store = FakeBundleStore("mangadex", "weebcentral")
            val engine =
                FakeWasmEngine(
                    metas =
                        mapOf(
                            "mangadex" to metaWire("mangadex", "MangaDex"),
                            "weebcentral" to metaWire("weebcentral", "WeebCentral")
                        )
                )
            val loader = loader(engine, store)

            val sources = loader.observeInstalledSources().first().sortedBy { it.id }

            assertEquals(listOf("mangadex", "weebcentral"), sources.map { it.id })
            assertEquals("MangaDex", sources.first().name.value)
        }

    @Test
    fun `observeInstalledSources skips sources that fail to load`() =
        runTest {
            val store = FakeBundleStore("good", "broken")
            // Mismatched ABI version → moduleFor's version check fails for every source; both are skipped
            // rather than blowing up the whole listing.
            val engine =
                FakeWasmEngine(
                    metas = mapOf("good" to metaWire("good", "Good")),
                    abiVersion = SourceAbi.VERSION + 1
                )
            val loader = loader(engine, store)

            assertEquals(emptyList(), loader.observeInstalledSources().first())
        }

    @Test
    fun `observeInstalledSources re-emits when a source is installed or removed`() =
        runTest {
            val store = FakeBundleStore("mangadex")
            val engine =
                FakeWasmEngine(
                    metas =
                        mapOf(
                            "mangadex" to metaWire("mangadex", "MangaDex"),
                            "weebcentral" to metaWire("weebcentral", "WeebCentral")
                        )
                )
            val loader = loader(engine, store)

            val emissions = mutableListOf<List<String>>()
            val collecting =
                launch { loader.observeInstalledSources().collect { emissions += it.map { m -> m.id } } }
            runCurrent()

            store.writeBundle("weebcentral", byteArrayOf(0))
            runCurrent()
            store.removeBundle("mangadex")
            runCurrent()
            collecting.cancel()

            assertEquals(
                listOf(listOf("mangadex"), listOf("mangadex", "weebcentral"), listOf("weebcentral")),
                emissions
            )
        }

    @Test
    fun `observeInstalledSources drops the cached module of a removed source`() =
        runTest {
            val store = FakeBundleStore("mangadex")
            val engine = FakeWasmEngine(metas = mapOf("mangadex" to metaWire("mangadex", "MangaDex")))
            val loader = loader(engine, store)

            // Fetching is what caches a module (enumeration deliberately doesn't); removing the bundle
            // must retire it, or the loader would keep serving a source that is no longer installed.
            loader.fetchMangaList("mangadex")
            assertEquals(listOf("mangadex"), engine.openIds())

            store.removeBundle("mangadex")
            assertEquals(emptyList(), loader.observeInstalledSources().first())
            assertEquals(emptyList(), engine.openIds())
            // Not "is not installed" from a stale cache hit — the module was dropped, so the reload is
            // what fails, and the bundle read is what reports it.
            assertFailsWith<IllegalStateException> { loader.loadSource("mangadex") }
        }

    @Test
    fun `replacing a bundle re-reports it and its next call runs the new bundle`() =
        runTest {
            val store = FakeBundleStore("mangadex")
            val engine = FakeWasmEngine(metas = mapOf("mangadex" to metaWire("mangadex", "MangaDex")))
            val loader = loader(engine, store)
            loader.fetchMangaList("mangadex")
            val original = engine.instantiated.last { it.first == "mangadex" }.second

            // An update writes over the installed bundle: the id set is unchanged, yet the enumeration must
            // run again (to report the new version) and the old instance must not keep serving calls.
            val emissions = mutableListOf<List<String>>()
            val collecting = launch { loader.observeInstalledSources().collect { emissions += it.map { m -> m.id } } }
            runCurrent()
            store.writeBundle("mangadex", byteArrayOf(1))
            runCurrent()
            collecting.cancel()
            loader.fetchMangaList("mangadex")

            assertEquals(2, emissions.size, "the rewrite re-emits even though the ids are the same")
            assertTrue(original.closed, "the module built from the old bundle is retired")
            assertEquals(listOf("mangadex"), engine.openIds())
        }

    @Test
    fun `a new subscriber does not evict cached modules`() =
        runTest {
            val store = FakeBundleStore("mangadex")
            val engine = FakeWasmEngine(metas = mapOf("mangadex" to metaWire("mangadex", "MangaDex")))
            val loader = loader(engine, store)
            loader.fetchMangaList("mangadex")

            // A screen opening reads the installed sources once (GetSourceMeta); that must not cost every
            // cached source a cold re-instantiation.
            loader.observeInstalledSources().first()
            loader.fetchMangaList("mangadex")

            assertEquals(1, engine.instantiated.count { (id, module) -> id == "mangadex" && module.closed.not() })
            assertEquals(listOf("mangadex"), engine.openIds())
        }

    @Test
    fun `the module cache holds at most three sources - closing the least recently used`() =
        runTest {
            val engine = FakeWasmEngine()
            val loader = loader(engine, FakeBundleStore("a", "b", "c", "d"))

            listOf("a", "b", "c").forEach { loader.fetchMangaList(it) }
            assertEquals(listOf("a", "b", "c"), engine.openIds())

            loader.fetchMangaList("d")

            // "a" is the oldest borrow, so it is what pays for "d" — and it is closed, not just dropped:
            // on iOS that call is what returns the instance's memory.
            assertEquals(listOf("b", "c", "d"), engine.openIds())
        }

    @Test
    fun `eviction is by recency - not by insertion order`() =
        runTest {
            val engine = FakeWasmEngine()
            val loader = loader(engine, FakeBundleStore("a", "b", "c", "d"))

            listOf("a", "b", "c", "a").forEach { loader.fetchMangaList(it) }
            loader.fetchMangaList("d")

            // Touching "a" again moved it to the young end, so "b" is now the oldest.
            assertEquals(listOf("a", "c", "d"), engine.openIds())
            assertEquals(1, engine.instantiations("a"))
        }

    @Test
    fun `a source evicted from the cache is instantiated again on its next call`() =
        runTest {
            val engine = FakeWasmEngine()
            val loader = loader(engine, FakeBundleStore("a", "b", "c", "d"))

            listOf("a", "b", "c", "d").forEach { loader.fetchMangaList(it) }
            val page = loader.fetchMangaList("a")

            // The cap is a cost, not a failure: coming back to an evicted source re-reads its bundle and
            // instantiates a second module, and the call answers normally.
            assertEquals(2, engine.instantiations("a"))
            assertTrue(page.entries.isEmpty())
            assertEquals(listOf("c", "d", "a"), engine.openIds())
        }

    @Test
    fun `enumerating installed sources leaves nothing cached`() =
        runTest {
            val ids = listOf("a", "b", "c", "d", "e")
            val engine = FakeWasmEngine(metas = ids.associateWith { metaWire(it, it.uppercase()) })
            val loader = loader(engine, FakeBundleStore(*ids.toTypedArray()))

            assertEquals(ids, loader.observeInstalledSources().first().map { it.id })

            // SOURCE_META is asked of every installed bundle, so caching it would pin an instance per
            // installed source — for sources the user may never open. Each is closed on the way out.
            assertEquals(emptyList(), engine.openIds())
            assertEquals(ids.size, engine.instantiated.size)
        }

    @Test
    fun `loadSource does not cache a module`() =
        runTest {
            val engine = FakeWasmEngine(metas = mapOf("mangadex" to metaWire("mangadex", "MangaDex")))
            val loader = loader(engine, FakeBundleStore("mangadex"))

            loader.loadSource("mangadex")

            assertEquals(emptyList(), engine.openIds())
        }

    @Test
    fun `a call that fails still frees its module for eviction`() =
        runTest {
            val engine = FakeWasmEngine()
            val loader = loader(engine, FakeBundleStore("a", "b", "c", "d"))

            // A guest-reported error propagates out of the call, so the release has to happen from a
            // finally — a module left marked in-use would be pinned in the cache for the whole process.
            assertFailsWith<SourceAbiException> { loader.fetchMangaDetails("a", "ghost") }
            listOf("b", "c", "d").forEach { loader.fetchMangaList(it) }

            assertEquals(listOf("b", "c", "d"), engine.openIds())
        }

    @Test
    fun `two calls to one source run at once - each on its own instance`() =
        inFlightTest { io ->
            val engine = FakeWasmEngine(parking = setOf("a"))
            val loader = loader(engine, FakeBundleStore("a"), dispatchers = io)

            val calls = List(2) { async { loader.fetchMangaList("a") } }

            // Under a per-source lock the second call would queue behind the first's whole round-trip
            // (the engines hold a call across the guest's HTTP), and this would time out.
            engine.parked.first { it == 2 }
            assertEquals(2, engine.instantiations("a"))

            engine.gate.complete(Unit)
            calls.awaitAll()
        }

    @Test
    fun `a call past the per-source limit waits for an instance rather than making one`() =
        inFlightTest { io ->
            val engine = FakeWasmEngine(parking = setOf("a"))
            val loader = loader(engine, FakeBundleStore("a"), dispatchers = io)

            val calls = List(2) { async { loader.fetchMangaList("a") } }.toMutableList()
            engine.parked.first { it == 2 }
            calls += async { loader.fetchMangaList("a") }

            // Real time, not virtual: the call being waited on is on another thread. Nothing can make
            // this pass wrongly — a third instance would show up in the count below.
            delay(200.milliseconds)
            assertEquals(2, engine.parked.value)
            assertEquals(2, engine.instantiations("a"))

            engine.gate.complete(Unit)
            calls.awaitAll()
            // The waiter ran on an instance one of the first two handed back.
            assertEquals(3, engine.parked.value)
            assertEquals(2, engine.instantiations("a"))
        }

    @Test
    fun `a burst leaves one instance open per source`() =
        inFlightTest { io ->
            val engine = FakeWasmEngine(parking = setOf("a"))
            val loader = loader(engine, FakeBundleStore("a"), dispatchers = io)

            val calls = List(2) { async { loader.fetchMangaList("a") } }
            engine.parked.first { it == 2 }
            engine.gate.complete(Unit)
            calls.awaitAll()

            // The second instance exists for the overlap only; keeping it would double what every
            // source the user touched in parallel costs for the rest of the session.
            assertEquals(listOf("a"), engine.openIds())
        }

    @Test
    fun `a source mid-call is not closed to make room in the cache`() =
        inFlightTest { io ->
            val engine = FakeWasmEngine(parking = setOf("a"))
            val loader = loader(engine, FakeBundleStore("a", "b", "c", "d"), dispatchers = io)

            val call = async { loader.fetchMangaList("a") }
            engine.parked.first { it == 1 }
            listOf("b", "c", "d").forEach { loader.fetchMangaList(it) }

            // "a" is the least recently used, but closing it now would free an instance the guest is
            // executing in — so the oldest *idle* source pays instead.
            assertEquals(listOf("a", "c", "d"), engine.openIds())

            engine.gate.complete(Unit)
            call.await()
            assertEquals(listOf("a", "c", "d"), engine.openIds())
        }

    @Test
    fun `uninstalling a source mid-call closes its instance when the call returns`() =
        inFlightTest { io ->
            val store = FakeBundleStore("a")
            val engine = FakeWasmEngine(parking = setOf("a"))
            val loader = loader(engine, store, dispatchers = io)

            val call = async { loader.fetchMangaList("a") }
            engine.parked.first { it == 1 }
            store.removeBundle("a")
            assertEquals(emptyList(), loader.observeInstalledSources().first())

            // Retired, but still executing: on iOS closing it here is a use-after-free.
            assertEquals(listOf("a"), engine.openIds())

            engine.gate.complete(Unit)
            call.await()
            assertEquals(emptyList(), engine.openIds())
        }

    @Test
    fun `fetchChapterPages decodes and maps to domain`() =
        runTest {
            val store = FakeBundleStore("mangadex")
            val engine =
                FakeWasmEngine(
                    pages =
                        mapOf(
                            "ch1" to
                                listOf(
                                    Wire.PageWire(index = 0, imageUrl = "https://img.test/0.jpg"),
                                    Wire.PageWire(index = 1, url = "https://img.test/p1")
                                )
                        )
                )
            val loader = loader(engine, store)

            val pages = loader.fetchChapterPages("mangadex", "ch1")

            assertEquals(2, pages.size)
            assertEquals(0u, pages[0].index)
            assertEquals("https://img.test/0.jpg", pages[0].imageUrl?.value)
            assertEquals("https://img.test/p1", pages[1].url?.value)
        }

    @Test
    fun `fetchMangaList decodes and maps to listings`() =
        runTest {
            val store = FakeBundleStore("mangadex")
            val engine =
                FakeWasmEngine(
                    catalog =
                        mapOf(
                            "mangadex" to
                                listOf(
                                    Wire.MangaWire(
                                        key = "one-piece",
                                        title = "One Piece",
                                        coverUrl = "https://img.test/op.jpg",
                                        author = "Oda",
                                        status = "ONGOING"
                                    ),
                                    // Unknown status name falls back to UNKNOWN rather than failing the whole list.
                                    Wire.MangaWire(key = "berserk", title = "Berserk", status = "on-a-boat")
                                )
                        )
                )
            val loader = loader(engine, store)

            val page = loader.fetchMangaList("mangadex")

            assertEquals(listOf("one-piece", "berserk"), page.entries.map { it.sourceMangaKey })
            val onePiece = page.entries.first()
            assertEquals("One Piece", onePiece.title)
            assertEquals("https://img.test/op.jpg", onePiece.coverUrl?.value)
            assertEquals("Oda", onePiece.author)
            assertEquals(PublishStatus.ONGOING, onePiece.status)
            assertEquals(PublishStatus.UNKNOWN, page.entries[1].status)
        }

    @Test
    fun `fetchMangaList passes listing - query and page through to the source`() =
        runTest {
            val engine = FakeWasmEngine(catalog = mapOf("mangadex" to emptyList()))
            val loader = loader(engine, FakeBundleStore("mangadex"))

            loader.fetchMangaList(
                "mangadex",
                listing = SourceListing.POPULAR,
                query = "berserk",
                page = 3
            )

            assertEquals(1, engine.requests.size)
            val request = engine.requests.single()
            assertEquals("popular", request.listing)
            assertEquals("berserk", request.query)
            assertEquals(3, request.page)
        }

    @Test
    fun `fetchMangaList tells the source whether sensitive content is allowed`() =
        runTest {
            val engine = FakeWasmEngine(catalog = mapOf("mangadex" to emptyList()))
            val hidden = loader(engine, FakeBundleStore("mangadex"))
            val allowed = loader(engine, FakeBundleStore("mangadex"), sensitive = ShowSensitive)

            hidden.fetchMangaList("mangadex")
            allowed.fetchMangaList("mangadex")

            assertEquals(listOf(false, true), engine.requests.map { it.allowSensitive })
        }

    @Test
    fun `fetchMangaList normalises a blank query to null so a cleared search browses`() =
        runTest {
            val engine = FakeWasmEngine(catalog = mapOf("mangadex" to emptyList()))
            val loader = loader(engine, FakeBundleStore("mangadex"))

            loader.fetchMangaList("mangadex", query = "   ")

            assertNull(engine.requests.single().query)
        }

    @Test
    fun `fetchMangaList reports hasNextPage so paging can terminate`() =
        runTest {
            val engine =
                FakeWasmEngine(
                    catalog = mapOf("mangadex" to listOf(Wire.MangaWire(key = "one-piece", title = "One Piece")))
                )
            val loader = loader(engine, FakeBundleStore("mangadex"))

            val first = loader.fetchMangaList("mangadex", page = 1)
            val second = loader.fetchMangaList("mangadex", page = 2)

            assertTrue(first.hasNextPage)
            assertEquals(1, first.entries.size)
            assertFalse(second.hasNextPage)
            assertEquals(emptyList(), second.entries)
        }

    @Test
    fun `fetchMangaList rejects a page index below one`() =
        runTest {
            val loader = loader(FakeWasmEngine(), FakeBundleStore("mangadex"))

            assertFailsWith<IllegalArgumentException> { loader.fetchMangaList("mangadex", page = 0) }
        }

    @Test
    fun `every source fetch is refused offline without entering the engine`() =
        runTest {
            // Refused *before* the module runs: on iOS the guest's HTTP import is a C callback, and an
            // exception thrown inside it aborts the process instead of unwinding — so an offline fetch
            // must never get as far as the engine.
            val engine = FakeWasmEngine(catalog = mapOf("mangadex" to emptyList()))
            val loader =
                loader(
                    engine,
                    FakeBundleStore("mangadex"),
                    connection = FakeConnectionObserver(ConnectionStatus.OFFLINE)
                )

            assertFailsWith<OfflineException> { loader.fetchMangaList("mangadex") }
            assertFailsWith<OfflineException> { loader.fetchMangaDetails("mangadex", "one-piece") }
            assertFailsWith<OfflineException> { loader.fetchChapterList("mangadex", "one-piece") }
            assertFailsWith<OfflineException> { loader.fetchChapterPages("mangadex", "c1") }
            assertEquals(emptyList(), engine.requests)
        }

    @Test
    fun `a low-speed connection still fetches`() =
        runTest {
            // Being frugal on a constrained link is a policy the caller may adopt; the loader refusing
            // outright is not the same thing, and would strand a user on hotel Wi-Fi.
            val engine = FakeWasmEngine(catalog = mapOf("mangadex" to emptyList()))
            val loader =
                loader(
                    engine,
                    FakeBundleStore("mangadex"),
                    connection = FakeConnectionObserver(ConnectionStatus.LOW_SPEED)
                )

            loader.fetchMangaList("mangadex")

            assertEquals(1, engine.requests.size)
        }

    @Test
    fun `fetchMangaDetails fills in the full record`() =
        runTest {
            val engine =
                FakeWasmEngine(
                    details =
                        mapOf(
                            "one-piece" to
                                Wire.MangaWire(
                                    key = "one-piece",
                                    title = "One Piece",
                                    originalTitle = "ワンピース",
                                    description = "Pirates.",
                                    author = "Eiichiro Oda",
                                    status = "ONGOING"
                                )
                        )
                )
            val loader = loader(engine, FakeBundleStore("mangadex"))

            val details = loader.fetchMangaDetails("mangadex", "one-piece")

            assertEquals("one-piece", details.sourceMangaKey)
            assertEquals("ワンピース", details.originalTitle)
            assertEquals("Pirates.", details.description)
            assertEquals(PublishStatus.ONGOING, details.status)
        }

    @Test
    fun `a guest-reported error surfaces as a SourceAbiException`() =
        runTest {
            // The fake answers an unknown key with an in-band Envelope.error rather than trapping — the
            // path a well-behaved guest takes for a recoverable failure.
            val loader = loader(FakeWasmEngine(), FakeBundleStore("mangadex"))

            val error =
                assertFailsWith<SourceAbiException> {
                    loader.fetchMangaDetails("mangadex", "ghost")
                }
            assertEquals("not_found", error.code)
        }

    @Test
    fun `fetchChapterList maps names - numbers and dates leniently`() =
        runTest {
            val engine =
                FakeWasmEngine(
                    chapters =
                        mapOf(
                            "one-piece" to
                                listOf(
                                    Wire.ChapterWire(
                                        key = "ch-1",
                                        name = "Romance Dawn",
                                        number = 1.0,
                                        dateUpload = 1_700_000_000_000,
                                        scanlator = "Scans"
                                    ),
                                    // Blank name, fractional number, and a 0 date — the "unknown" sentinel sources send.
                                    Wire.ChapterWire(key = "ch-1-5", name = "  ", number = 1.5, dateUpload = 0),
                                    // Negative number is not a valid ChapterNumber; it degrades to null, not a throw.
                                    Wire.ChapterWire(key = "ch-x", number = -1.0)
                                )
                        )
                )
            val loader = loader(engine, FakeBundleStore("mangadex"))

            val chapters = loader.fetchChapterList("mangadex", "one-piece")

            assertEquals(listOf("ch-1", "ch-1-5", "ch-x"), chapters.map { it.chapterKey })
            assertEquals("Romance Dawn", chapters[0].name)
            assertEquals(1.0, chapters[0].number?.value)
            assertEquals("Scans", chapters[0].scanlator)
            assertEquals(1_700_000_000_000, chapters[0].dateUpload?.toEpochMilliseconds())
            // Blank name and the 0 date both become absent rather than "" and 1970.
            assertNull(chapters[1].name)
            assertNull(chapters[1].dateUpload)
            assertEquals(1.5, chapters[1].number?.value)
            assertNull(chapters[2].number)
        }

    @Test
    fun `observeInstalledSources carries version and resolves declared listings against the contract`() =
        runTest {
            val engine =
                FakeWasmEngine(
                    metas =
                        mapOf(
                            "mangadex" to
                                Wire.SourceMetaWire(
                                    id = "mangadex",
                                    name = "MangaDex",
                                    lang = "en",
                                    baseUrl = "https://mangadex.test",
                                    version = 7,
                                    // Declared out of contract order, with a duplicate and a listing
                                    // this build has no concept of.
                                    listings = listOf("latest", "nonsense", "popular", "latest")
                                )
                        )
                )
            val loader = loader(engine, FakeBundleStore("mangadex"))

            val meta = loader.observeInstalledSources().first().single()

            assertEquals(7, meta.version)
            // Unknown dropped, duplicate collapsed, and the order is the contract's — not the source's.
            assertEquals(listOf(SourceListing.POPULAR, SourceListing.LATEST), meta.listings)
        }

    @Test
    fun `loadSource persists the bundle's reported version`() =
        runTest {
            val engine =
                FakeWasmEngine(
                    metas =
                        mapOf(
                            "mangadex" to
                                Wire.SourceMetaWire(
                                    id = "mangadex",
                                    name = "MangaDex",
                                    lang = "en",
                                    baseUrl = "https://mangadex.test",
                                    version = 7
                                )
                        )
                )
            val repository = FakeSourceRepository()
            val loader = loader(engine, FakeBundleStore("mangadex"), repository)

            assertEquals(7, loader.loadSource("mangadex").version)
            assertEquals(7, repository.getSourceByNativeId("mangadex")?.version)
        }

    @Test
    fun `loadSource installs with local identity on first load`() =
        runTest {
            val store = FakeBundleStore("mangadex")
            val engine = FakeWasmEngine(metas = mapOf("mangadex" to metaWire("mangadex", "MangaDex")))
            val repository = FakeSourceRepository()
            val loader = loader(engine, store, repository)

            val source = loader.loadSource("mangadex")

            assertEquals("mangadex", source.sourceId)
            assertEquals("MangaDex", source.name.value)
            assertEquals("en", source.lang.value)
            assertEquals("https://mangadex.test", source.url.value)
            // The source was persisted, so its local identity is now discoverable by native id.
            assertEquals(source, repository.getSourceByNativeId("mangadex"))
        }

    @Test
    fun `loadSource reuses existing local identity and skips reinstall`() =
        runTest {
            val store = FakeBundleStore("mangadex")
            val engine = FakeWasmEngine(metas = mapOf("mangadex" to metaWire("mangadex", "MangaDex")))
            val repository = FakeSourceRepository()
            val loader = loader(engine, store, repository)

            val first = loader.loadSource("mangadex")
            val second = loader.loadSource("mangadex")

            assertEquals(first.id, second.id)
            assertEquals(1, repository.addCount)
        }

    @Test
    fun `calling an uninstalled source fails`() =
        runTest {
            val loader = loader(FakeWasmEngine(), FakeBundleStore())

            val error =
                assertFailsWith<IllegalStateException> {
                    loader.fetchChapterPages("ghost", "ch1")
                }
            assertTrue(error.message?.contains("ghost") == true)
        }

    private fun testDispatchers() =
        object : DispatcherProvider {
            val dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher()
            override val default get() = dispatcher
            override val io get() = dispatcher
            override val main get() = dispatcher
        }

    /**
     * Runs [body] on real threads, for the tests that need a call held *inside* the engine while others
     * start. [WasmModule.call] isn't suspend, so under a test dispatcher a call never yields; parking it in
     * `runBlocking` is exactly what the real engines do across a guest's HTTP. The loader gets its own
     * pool (passed to [body]) so that parked calls can never starve the test of a thread.
     */
    @OptIn(DelicateCoroutinesApi::class)
    private fun inFlightTest(body: suspend CoroutineScope.(DispatcherProvider) -> Unit) =
        runTest {
            val pool = newFixedThreadPoolContext(4, "wasm-loader-test")
            val dispatchers =
                object : DispatcherProvider {
                    override val default get() = pool
                    override val io get() = pool
                    override val main get() = pool
                }
            try {
                // Off the test scheduler, so the timeout is real time rather than skipped virtual time.
                withContext(Dispatchers.Default) { withTimeout(IN_FLIGHT_LIMIT) { body(dispatchers) } }
            } finally {
                pool.close()
            }
        }

    private fun loader(
        engine: WasmEngine,
        store: SourceBundleStore,
        repository: SourceRepository = FakeSourceRepository(),
        connection: ConnectionObserver = FakeConnectionObserver(),
        dispatchers: DispatcherProvider = testDispatchers(),
        sensitive: ObserveShowSensitiveContent = HideSensitive
    ) = WasmSourceLoader(engine, NoOpSourceHost, store, repository, dispatchers, connection, sensitive)

    private fun metaWire(
        id: String,
        name: String
    ) = Wire.SourceMetaWire(id = id, name = name, lang = "en", baseUrl = "https://$id.test")
}

/** How long an in-flight test may take — well short of [PARK_LIMIT], so a failure reports as a timeout. */
private val IN_FLIGHT_LIMIT = 5.seconds

/** The longest a parked fake call waits for its gate before giving up on its own. */
private val PARK_LIMIT = 15.seconds

/** A [SourceHost] that does nothing; the loader tests never exercise a real HTTP/log import. */
private object NoOpSourceHost : SourceHost {
    override suspend fun httpRequest(request: Wire.HttpRequestWire) =
        Wire.HttpResponseWire(status = 200, body = ByteArray(0))

    override fun log(
        level: Wire.LogLevel,
        message: String
    ) = Unit
}

/**
 * In-memory [SourceBundleStore]; bundle bytes are dummies since [FakeWasmEngine] ignores them.
 *
 * Mirrors the real store's change signalling — a revision counter bumped by every mutation, which
 * [observeInstalledIds] re-reads on — so tests can install and uninstall against a live enumeration.
 */
private class FakeBundleStore(
    vararg installed: String
) : SourceBundleStore {
    private val bundles = installed.associateWith { byteArrayOf(0) }.toMutableMap()
    private val revision = MutableStateFlow(0)
    private val generations = mutableMapOf<String, Int>()

    override fun generation(sourceId: String): Int = generations[sourceId] ?: 0

    override suspend fun installedIds() = bundles.keys.toList()

    override fun observeInstalledIds(): Flow<List<String>> =
        revision.map { installedIds() }

    override suspend fun readBundle(sourceId: String) = bundles[sourceId]

    override suspend fun writeBundle(
        sourceId: String,
        bytes: ByteArray
    ) {
        bundles[sourceId] = bytes
        generations[sourceId] = generation(sourceId) + 1
        revision.value++
    }

    override suspend fun removeBundle(sourceId: String) {
        bundles.remove(sourceId)
        revision.value++
    }

    override suspend fun isInstalled(sourceId: String) = sourceId in bundles
}

/** In-memory [SourceRepository]; tracks [addCount] so install-once behaviour can be asserted. */
private class FakeSourceRepository : SourceRepository {
    private val sources = mutableMapOf<String, Source>()
    var addCount = 0
        private set

    override fun observeSources(): Flow<List<Source>> = flowOf(sources.values.toList())

    override suspend fun getSource(id: Uuid): Source? = sources.values.firstOrNull { it.id == id }

    override suspend fun getSourceByNativeId(sourceId: String): Source? = sources[sourceId]

    override suspend fun addSource(source: Source) {
        sources[source.sourceId] = source
        addCount++
    }

    override suspend fun deleteSource(source: Source) {
        sources.remove(source.sourceId)
    }
}

/** A [WasmEngine] whose modules answer ABI calls from canned data instead of running real wasm. */
private class FakeWasmEngine(
    private val metas: Map<String, Wire.SourceMetaWire> = emptyMap(),
    private val pages: Map<String, List<Wire.PageWire>> = emptyMap(),
    private val catalog: Map<String, List<Wire.MangaWire>> = emptyMap(),
    private val details: Map<String, Wire.MangaWire> = emptyMap(),
    private val chapters: Map<String, List<Wire.ChapterWire>> = emptyMap(),
    private val abiVersion: Int = SourceAbi.VERSION,
    /** Records the last MANGA_LIST request each module received, so arg plumbing can be asserted. */
    val requests: MutableList<Wire.MangaListRequest> = mutableListOf(),
    /**
     * Sources whose export calls park until [gate] completes — the engines' `runBlocking` bridge held
     * open, so a test can have a call in flight while it starts others.
     */
    private val parking: Set<String> = emptySet()
) : WasmEngine {
    /** Releases every parked call. */
    val gate = CompletableDeferred<Unit>()

    /** How many calls have entered a parking module — updated from whichever thread made the call. */
    val parked = MutableStateFlow(0)

    /**
     * Every [instantiate] in order, with the module it produced — the module cache's whole observable
     * surface: how often a source was instantiated, and which instances the loader has closed.
     */
    val instantiated: MutableList<Pair<String, FakeWasmModule>> = mutableListOf()

    override fun instantiate(
        sourceId: String,
        wasmBytes: ByteArray,
        host: SourceHost
    ): WasmModule =
        FakeWasmModule(
            meta = metas[sourceId],
            pages = pages,
            catalog = catalog[sourceId].orEmpty(),
            details = details,
            chapters = chapters,
            abiVersion = abiVersion,
            requests = requests,
            park = if (sourceId in parking) ::park else null
        ).also { instantiated += sourceId to it }

    private fun park() {
        parked.update { it + 1 }
        // Bounded, and longer than inFlightTest's own timeout: a test that fails leaves this call parked,
        // and cancelling the test has to wait for the blocked thread — unbounded, a failure is a hang.
        runBlocking { withTimeoutOrNull(PARK_LIMIT) { gate.await() } }
    }

    /** Source ids with a module the loader instantiated and hasn't closed — i.e. what it is holding. */
    fun openIds(): List<String> = instantiated.filterNot { (_, module) -> module.closed }.map { it.first }

    /** How many times [sourceId] was instantiated — a second one means it had been evicted. */
    fun instantiations(sourceId: String): Int = instantiated.count { (id, _) -> id == sourceId }
}

private class FakeWasmModule(
    private val meta: Wire.SourceMetaWire?,
    private val pages: Map<String, List<Wire.PageWire>>,
    private val catalog: List<Wire.MangaWire>,
    private val details: Map<String, Wire.MangaWire>,
    private val chapters: Map<String, List<Wire.ChapterWire>>,
    private val abiVersion: Int,
    private val requests: MutableList<Wire.MangaListRequest>,
    private val park: (() -> Unit)?
) : WasmModule {
    override fun call(
        export: String,
        input: ByteArray
    ): ByteArray {
        park?.invoke()
        return answer(export, input)
    }

    private fun answer(
        export: String,
        input: ByteArray
    ): ByteArray =
        when (export) {
            SourceAbi.Exports.SOURCE_META -> {
                SourceCodec.encode(checkNotNull(meta) { "no metadata configured" })
            }

            SourceAbi.Exports.CHAPTER_PAGES -> {
                val request = SourceCodec.decode<Wire.ChapterPagesRequest>(input)
                SourceCodec.encode(Wire.Envelope(ok = pages[request.chapterKey].orEmpty()))
            }

            SourceAbi.Exports.MANGA_LIST -> {
                val request = SourceCodec.decode<Wire.MangaListRequest>(input)
                requests += request
                // Page 1 carries the canned catalog and reports more; later pages are empty and terminal,
                // so pagination plumbing is observable without configuring per-page fixtures.
                val terminal = request.page > 1
                SourceCodec.encode(
                    Wire.Envelope(
                        ok =
                            Wire.MangaPageWire(
                                entries = if (terminal) emptyList() else catalog,
                                hasNextPage = !terminal
                            )
                    )
                )
            }

            SourceAbi.Exports.MANGA_DETAILS -> {
                val request = SourceCodec.decode<Wire.MangaDetailsRequest>(input)
                val manga = details[request.mangaKey]
                SourceCodec.encode(
                    if (manga != null) {
                        Wire.Envelope(ok = manga)
                    } else {
                        Wire.Envelope<Wire.MangaWire>(
                            error = Wire.AbiError("no such manga '${request.mangaKey}'", code = "not_found")
                        )
                    }
                )
            }

            SourceAbi.Exports.CHAPTER_LIST -> {
                val request = SourceCodec.decode<Wire.ChapterListRequest>(input)
                SourceCodec.encode(Wire.Envelope(ok = chapters[request.mangaKey].orEmpty()))
            }

            else -> {
                error("unexpected export '$export'")
            }
        }

    override fun callReturningI32(export: String): Int = abiVersion

    /** Whether the loader has closed this instance; the real iOS module frees native memory here. */
    var closed = false
        private set

    override fun close() {
        closed = true
    }
}
/** The default setting: sensitive content hidden. */
private object HideSensitive : ObserveShowSensitiveContent {
    override fun invoke(): Flow<Boolean> = flowOf(false)
}

private object ShowSensitive : ObserveShowSensitiveContent {
    override fun invoke(): Flow<Boolean> = flowOf(true)
}
