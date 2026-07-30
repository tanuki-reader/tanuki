package app.tanuki.core.source

import app.tanuki.core.source.abi.SourceAbi
import app.tanuki.core.source.abi.SourceCodec
import app.tanuki.core.source.abi.Wire
import app.tanuki.core.source.fixture.MANGAFIRE_WASM
import app.tanuki.core.source.runtime.SourceHost
import app.tanuki.core.source.runtime.provideWasmEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the real `mangafire.wasm` on both engines (Chicory / wasmi) with a [CannedHost] serving canned
 * JSON, so the whole path — guest builds a **VRF-signed** URL → host import → guest parses the reply — is
 * exercised deterministically without hitting live MangaFire (an untrusted, Cloudflare-fronted, changing
 * dependency). The `vrf` token's *correctness* can only be checked against the live server; here we assert
 * it is attached and the request/parse contract holds.
 *
 * Skips itself when [MANGAFIRE_WASM] is empty (no Rust toolchain at build time).
 */
class MangaFireSourceEngineTest {
    @Test
    fun `source_meta describes the source and its listings`() {
        if (MANGAFIRE_WASM.isEmpty()) return

        val engine = provideWasmEngine()
        val module = engine.instantiate("mangafire", MANGAFIRE_WASM, CannedHost(CANNED_EMPTY))

        val meta = SourceCodec.decode<Wire.SourceMetaWire>(module.call(SourceAbi.Exports.SOURCE_META, ByteArray(0)))

        // The fixture is the crate's default (English) language build; the pt-BR one differs only in
        // these three strings and the `language` request parameter.
        assertEquals("mangafire", meta.id)
        assertEquals("MangaFire (EN)", meta.name)
        assertEquals("en", meta.lang)
        assertEquals("https://mangafire.to", meta.baseUrl)
        assertTrue(meta.listings.containsAll(listOf("popular", "latest")), "serves popular + latest")

        module.close()
    }

    @Test
    fun `manga_list signs the request and parses the catalog`() {
        if (MANGAFIRE_WASM.isEmpty()) return

        val host = CannedHost(CANNED_TITLES)
        val engine = provideWasmEngine()
        val module = engine.instantiate("mangafire", MANGAFIRE_WASM, host)

        val request = SourceCodec.encode(Wire.MangaListRequest(listing = "popular", page = 1))
        val page = SourceCodec.decodeEnvelope<Wire.MangaPageWire>(module.call(SourceAbi.Exports.MANGA_LIST, request))

        // A signed /api/titles request: the Popular ordering, and a vrf token attached (value checked live).
        assertTrue(host.lastUrl.contains("mangafire.to/api/titles"), "url was ${host.lastUrl}")
        assertTrue(host.lastUrl.contains("order[views_30d]=desc"), "url was ${host.lastUrl}")
        assertTrue(Regex("[?&]vrf=[^&]+").containsMatchIn(host.lastUrl), "no vrf token in ${host.lastUrl}")

        assertEquals(1, page.entries.size)
        val onePiece = page.entries.first()
        assertEquals("op-hid", onePiece.key)
        assertEquals("One Piece", onePiece.title)
        assertEquals("https://mangafire.to/title/op-hid-one-piece", onePiece.url)
        // Cover picks the largest poster variant.
        assertEquals("https://mangafire.to/poster/large.jpg", onePiece.coverUrl)
        assertTrue(page.hasNextPage, "meta.hasNext should drive pagination")

        module.close()
    }

    @Test
    fun `chapter_pages returns image urls carrying the referer header`() {
        if (MANGAFIRE_WASM.isEmpty()) return

        val host = CannedHost(CANNED_PAGES)
        val engine = provideWasmEngine()
        val module = engine.instantiate("mangafire", MANGAFIRE_WASM, host)

        val request = SourceCodec.encode(Wire.ChapterPagesRequest(chapterKey = "12345"))
        val pages =
            SourceCodec.decodeEnvelope<List<Wire.PageWire>>(module.call(SourceAbi.Exports.CHAPTER_PAGES, request))

        assertTrue(host.lastUrl.contains("/api/chapters/12345"), "url was ${host.lastUrl}")
        assertEquals(2, pages.size)
        assertEquals("https://cdn.mangafire.to/1.jpg", pages.first().imageUrl)
        // The Referer MangaFire's CDN requires rides along on every page (the ABI's per-page headers).
        assertEquals("https://mangafire.to/", pages.first().headers["Referer"])

        module.close()
    }
}

/** A [SourceHost] that records the requested URL and answers with a fixed JSON body. */
private class CannedHost(
    private val responseJson: String
) : SourceHost {
    var lastUrl: String = ""
        private set

    override suspend fun httpRequest(request: Wire.HttpRequestWire): Wire.HttpResponseWire {
        lastUrl = request.url
        return Wire.HttpResponseWire(status = 200, body = responseJson.encodeToByteArray())
    }

    override fun log(
        level: Wire.LogLevel,
        message: String
    ) = Unit
}

/** One-title catalog page with a next page, trimmed to the fields the source reads. */
private val CANNED_TITLES =
    """
    {"items":[
      {"hid":"op-hid","slug":"one-piece","title":"One Piece",
       "poster":{"small":"https://mangafire.to/poster/small.jpg",
                 "medium":"https://mangafire.to/poster/medium.jpg",
                 "large":"https://mangafire.to/poster/large.jpg"}}
    ],"meta":{"lastPage":10,"hasNext":true}}
    """.trimIndent()

/** Empty catalog, for the tests that only need the source to load / not parse entries. */
private val CANNED_EMPTY = """{"items":[],"meta":{"lastPage":1,"hasNext":false}}"""

/** A two-page chapter (`/api/chapters/{id}` is wrapped in `data`). */
private val CANNED_PAGES =
    """{"data":{"pages":[{"url":"https://cdn.mangafire.to/1.jpg"},{"url":"https://cdn.mangafire.to/2.jpg"}]}}"""
