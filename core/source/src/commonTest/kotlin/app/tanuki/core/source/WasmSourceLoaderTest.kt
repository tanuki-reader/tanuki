package app.tanuki.core.source

import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.manga.model.PublishStatus
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Tests [WasmSourceLoader]'s loader logic — bundle-store enumeration, the ABI call/decode pipeline,
 * source install-on-load, and resilience — against a fake [WasmEngine], in-memory [SourceBundleStore],
 * and in-memory [SourceRepository], so it's exercised independently of the platform engines (Chicory /
 * wasmi) and the Room-backed repository.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WasmSourceLoaderTest {
    @Test
    fun `listSources reports each installed bundle's metadata`() =
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

            val sources = loader.listSources().sortedBy { it.id }

            assertEquals(listOf("mangadex", "weebcentral"), sources.map { it.id })
            assertEquals("MangaDex", sources.first().name.value)
        }

    @Test
    fun `listSources skips sources that fail to load`() =
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

            assertEquals(emptyList(), loader.listSources())
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
    fun `listSources carries version and resolves declared listings against the contract`() =
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

            val meta = loader.listSources().single()

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

    private fun loader(
        engine: WasmEngine,
        store: SourceBundleStore,
        repository: SourceRepository = FakeSourceRepository()
    ) = WasmSourceLoader(engine, NoOpSourceHost, store, repository, testDispatchers())

    private fun metaWire(
        id: String,
        name: String
    ) = Wire.SourceMetaWire(id = id, name = name, lang = "en", baseUrl = "https://$id.test")
}

/** A [SourceHost] that does nothing; the loader tests never exercise a real HTTP/log import. */
private object NoOpSourceHost : SourceHost {
    override suspend fun httpRequest(request: Wire.HttpRequestWire) =
        Wire.HttpResponseWire(status = 200, body = ByteArray(0))

    override fun log(
        level: Wire.LogLevel,
        message: String
    ) = Unit
}

/** In-memory [SourceBundleStore]; bundle bytes are dummies since [FakeWasmEngine] ignores them. */
private class FakeBundleStore(
    vararg installed: String
) : SourceBundleStore {
    private val bundles = installed.associateWith { byteArrayOf(0) }.toMutableMap()

    override suspend fun installedIds() = bundles.keys.toList()

    override suspend fun readBundle(sourceId: String) = bundles[sourceId]

    override suspend fun writeBundle(
        sourceId: String,
        bytes: ByteArray
    ) {
        bundles[sourceId] = bytes
    }

    override suspend fun removeBundle(sourceId: String) {
        bundles.remove(sourceId)
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
    val requests: MutableList<Wire.MangaListRequest> = mutableListOf()
) : WasmEngine {
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
            requests = requests
        )
}

private class FakeWasmModule(
    private val meta: Wire.SourceMetaWire?,
    private val pages: Map<String, List<Wire.PageWire>>,
    private val catalog: List<Wire.MangaWire>,
    private val details: Map<String, Wire.MangaWire>,
    private val chapters: Map<String, List<Wire.ChapterWire>>,
    private val abiVersion: Int,
    private val requests: MutableList<Wire.MangaListRequest>
) : WasmModule {
    override fun call(
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

    override fun close() = Unit
}