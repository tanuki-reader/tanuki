package app.tanuki.core.source

import app.tanuki.core.source.abi.SourceAbi
import app.tanuki.core.source.abi.SourceCodec
import app.tanuki.core.source.abi.Wire
import app.tanuki.core.source.fixture.MANGADEX_WASM
import app.tanuki.core.source.runtime.SourceHost
import app.tanuki.core.source.runtime.provideWasmEngine
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Phase 1 proof: run the **real** `mangadex.wasm` through the **real** engine and exercise the one
 * thing `hello` didn't — a guest reaching back through the host `http_request` import. A [CapturingHost]
 * returns canned MangaDex JSON instead of hitting the network, so the test is deterministic while still
 * driving the full path: guest builds a URL → host import → guest parses the reply → domain-shaped
 * [Wire.MangaPageWire] comes back. Runs on both engines (Chicory / wasmi) via [provideWasmEngine].
 *
 * This does NOT test live MangaDex — that's an untrusted, changing dependency and doesn't belong in the
 * suite. It tests our guest's request-building and response-parsing against a fixed contract.
 *
 * Skips itself when [MANGADEX_WASM] is empty (no Rust toolchain at build time).
 */
@OptIn(ExperimentalEncodingApi::class)
class MangaDexSourceEngineTest {
    @Test
    fun `manga_list fetches through the host import and parses the catalog`() {
        if (MANGADEX_WASM.isEmpty()) return

        val host = CapturingHost(CANNED_POPULAR)
        val engine = provideWasmEngine()
        val module = engine.instantiate("mangadex", MANGADEX_WASM, host)

        val request = SourceCodec.encode(Wire.MangaListRequest(listing = "popular", page = 1))
        val page =
            SourceCodec.decodeEnvelope<Wire.MangaPageWire>(
                module.call(SourceAbi.Exports.MANGA_LIST, request)
            )

        // The guest built a MangaDex URL and routed it through the host import — proof the bridge works.
        assertTrue(host.lastUrl.contains("api.mangadex.org/manga"), "url was ${host.lastUrl}")
        assertTrue(host.lastUrl.contains("order[followedCount]=desc"), "url was ${host.lastUrl}")

        assertEquals(2, page.entries.size)
        val onePiece = page.entries.first()
        assertEquals("op-id", onePiece.key)
        assertEquals("One Piece", onePiece.title)
        assertEquals(
            "https://uploads.mangadex.org/covers/op-id/cover.jpg.512.jpg",
            onePiece.coverUrl,
            "cover URL should be built from the manga id and the cover_art relationship's fileName"
        )
        assertEquals("Eiichiro Oda", onePiece.author)
        assertEquals("ongoing", onePiece.status)
        // offset 0 + 2 entries < total 50 → the guest reports more pages remain.
        assertTrue(page.hasNextPage)

        module.close()
    }

    @Test
    fun `latest listing selects the recent-chapter ordering`() {
        if (MANGADEX_WASM.isEmpty()) return

        val host = CapturingHost(CANNED_EMPTY)
        val engine = provideWasmEngine()
        val module = engine.instantiate("mangadex", MANGADEX_WASM, host)

        val request = SourceCodec.encode(Wire.MangaListRequest(listing = "latest", page = 2))
        SourceCodec.decodeEnvelope<Wire.MangaPageWire>(module.call(SourceAbi.Exports.MANGA_LIST, request))

        assertTrue(host.lastUrl.contains("order[latestUploadedChapter]=desc"), "url was ${host.lastUrl}")
        // page 2 with PAGE_SIZE 20 → offset 20.
        assertTrue(host.lastUrl.contains("offset=20"), "url was ${host.lastUrl}")

        module.close()
    }

    @Test
    fun `a search query is percent-encoded into the title parameter`() {
        if (MANGADEX_WASM.isEmpty()) return

        val host = CapturingHost(CANNED_EMPTY)
        val engine = provideWasmEngine()
        val module = engine.instantiate("mangadex", MANGADEX_WASM, host)

        val request = SourceCodec.encode(Wire.MangaListRequest(query = "one piece", page = 1))
        SourceCodec.decodeEnvelope<Wire.MangaPageWire>(module.call(SourceAbi.Exports.MANGA_LIST, request))

        // A search sends title= (not an order), with the space percent-encoded.
        assertTrue(host.lastUrl.contains("title=one%20piece"), "url was ${host.lastUrl}")

        module.close()
    }
}

/** A [SourceHost] that records the requested URL and answers with a fixed JSON body. */
@OptIn(ExperimentalEncodingApi::class)
private class CapturingHost(
    private val responseJson: String
) : SourceHost {
    var lastUrl: String = ""
        private set

    override suspend fun httpRequest(request: Wire.HttpRequestWire): Wire.HttpResponseWire {
        lastUrl = request.url
        return Wire.HttpResponseWire(
            status = 200,
            bodyBase64 = Base64.encode(responseJson.encodeToByteArray())
        )
    }

    override fun log(
        level: Wire.LogLevel,
        message: String
    ) = Unit
}

/** Two-entry MangaDex `/manga` collection, trimmed to the fields the source reads. */
private val CANNED_POPULAR =
    """
    {"result":"ok","response":"collection","data":[
      {"id":"op-id","type":"manga",
       "attributes":{"title":{"en":"One Piece"},"description":{"en":"Pirates."},"status":"ongoing","originalLanguage":"ja"},
       "relationships":[
         {"type":"cover_art","attributes":{"fileName":"cover.jpg"}},
         {"type":"author","attributes":{"name":"Eiichiro Oda"}}
       ]},
      {"id":"brk-id","type":"manga",
       "attributes":{"title":{"en":"Berserk"},"status":"hiatus"},
       "relationships":[]}
    ],"limit":20,"offset":0,"total":50}
    """.trimIndent()

/** Empty collection, for the tests that only assert on the outgoing URL. */
private val CANNED_EMPTY =
    """{"result":"ok","response":"collection","data":[],"limit":20,"offset":0,"total":0}"""