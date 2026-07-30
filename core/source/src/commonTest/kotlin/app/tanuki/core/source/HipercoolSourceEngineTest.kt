package app.tanuki.core.source

import app.tanuki.core.source.abi.SourceAbi
import app.tanuki.core.source.abi.SourceAbiException
import app.tanuki.core.source.abi.SourceCodec
import app.tanuki.core.source.abi.Wire
import app.tanuki.core.source.fixture.HIPERCOOL_WASM
import app.tanuki.core.source.runtime.SourceHost
import app.tanuki.core.source.runtime.provideWasmEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Runs the real `hipercool.wasm` on both engines (Chicory / wasmi) against canned tRPC replies.
 *
 * Two behaviours here are specific to this source and worth pinning: the **session handshake** — a
 * `401` sends the guest to the site root to be issued a cookie, then it retries the same call — and the
 * `x-flux-node` header, without which the live site refuses to serve page images at all. Neither is
 * visible in the returned data, so both are asserted on what the guest *asked the host for*.
 *
 * Skips itself when [HIPERCOOL_WASM] is empty (no Rust toolchain at build time).
 */
class HipercoolSourceEngineTest {
    @Test
    fun `source_meta describes an adult pt-BR source`() {
        if (HIPERCOOL_WASM.isEmpty()) return

        val engine = provideWasmEngine()
        val module = engine.instantiate("hipercool", HIPERCOOL_WASM, ScriptedHipercoolHost(HIPERCOOL_EMPTY_SEARCH))

        val meta = SourceCodec.decode<Wire.SourceMetaWire>(module.call(SourceAbi.Exports.SOURCE_META, ByteArray(0)))

        assertEquals("hipercool", meta.id)
        assertEquals("Hipercool", meta.name)
        assertEquals("pt-BR", meta.lang)
        assertEquals("https://lerhentais.com", meta.baseUrl)
        assertTrue(meta.nsfw, "the site is an adult catalog")

        module.close()
    }

    @Test
    fun `manga_list asks search_query for the requested sort and packs id and slug into the key`() {
        if (HIPERCOOL_WASM.isEmpty()) return

        val host = ScriptedHipercoolHost(HIPERCOOL_SEARCH)
        val engine = provideWasmEngine()
        val module = engine.instantiate("hipercool", HIPERCOOL_WASM, host)

        val request = SourceCodec.encode(Wire.MangaListRequest(listing = "latest", page = 1))
        val page = SourceCodec.decodeEnvelope<Wire.MangaPageWire>(module.call(SourceAbi.Exports.MANGA_LIST, request))

        assertTrue(host.lastUrl.contains("/api/trpc/search.query"), "url was ${host.lastUrl}")
        // Latest maps onto the site's "recent" ordering (percent-encoded inside the tRPC input blob).
        assertTrue(host.lastUrl.contains("%22sort%22%3A%22recent%22"), "url was ${host.lastUrl}")
        assertEquals("G2ZsDdWhUwdU82Vw", host.lastHeaders["x-flux-node"])

        assertEquals(1, page.entries.size)
        val entry = page.entries.first()
        // Pages are addressed by slug and chapters by id, so the key has to carry both.
        assertEquals("29538|stop-smoking", entry.key)
        assertEquals("Stop Smoking", entry.title)
        assertEquals("https://lerhentais.com/manga/stop-smoking", entry.url)
        assertEquals("ongoing", entry.status?.lowercase())
        // Covers sit on the same hotlink-guarded CDN as the pages: without this the whole source's
        // grid renders as empty tiles.
        assertEquals("https://lerhentais.com/", entry.coverHeaders["Referer"])
        // totalHits (2) is beyond this page, so there is another one.
        assertTrue(page.hasNextPage, "totalHits should drive pagination")

        module.close()
    }

    @Test
    fun `a 401 sends the guest to the site root for a session and then retries`() {
        if (HIPERCOOL_WASM.isEmpty()) return

        val host = ScriptedHipercoolHost(
            Reply(status = 401, body = """{"error":"Unauthorized: Invalid or missing API Key"}"""),
            Reply(status = 200, body = "<html>the site root, which sets the cookie</html>"),
            Reply(status = 200, body = HIPERCOOL_SEARCH),
        )
        val engine = provideWasmEngine()
        val module = engine.instantiate("hipercool", HIPERCOOL_WASM, host)

        val request = SourceCodec.encode(Wire.MangaListRequest(page = 1))
        val page = SourceCodec.decodeEnvelope<Wire.MangaPageWire>(module.call(SourceAbi.Exports.MANGA_LIST, request))

        assertEquals(3, host.urls.size, "challenged call, root fetch, retry — no more, no less")
        assertTrue(host.urls[0].contains("/api/trpc/"), "first call is the API call that gets refused")
        // The root has to be asked for as a *page*, or it answers as JSON and sets no cookie. The
        // cookie itself never reaches the guest — the host's jar keeps it.
        assertEquals("https://lerhentais.com", host.urls[1])
        assertTrue(
            host.headersAt(1)["Accept"]?.contains("text/html") == true,
            "the session fetch must look like a page load, was ${host.headersAt(1)["Accept"]}",
        )
        assertEquals(host.urls[0], host.urls[2], "the retry repeats the original call")
        assertEquals(1, page.entries.size, "the retry's data is what comes back")

        module.close()
    }

    @Test
    fun `chapter_list keys each chapter by series slug and number`() {
        if (HIPERCOOL_WASM.isEmpty()) return

        val host = ScriptedHipercoolHost(HIPERCOOL_CHAPTERS)
        val engine = provideWasmEngine()
        val module = engine.instantiate("hipercool", HIPERCOOL_WASM, host)

        val request = SourceCodec.encode(Wire.ChapterListRequest(mangaKey = "29538|stop-smoking"))
        val chapters =
            SourceCodec.decodeEnvelope<List<Wire.ChapterWire>>(module.call(SourceAbi.Exports.CHAPTER_LIST, request))

        assertTrue(host.lastUrl.contains("series.chapters"), "url was ${host.lastUrl}")
        // The id half of the manga key is what this call takes.
        assertTrue(host.lastUrl.contains("%22seriesId%22%3A29538"), "url was ${host.lastUrl}")

        assertEquals(2, chapters.size)
        // A whole number must not become "103.0" — the key is what the page call sends back as a number.
        assertEquals("stop-smoking|103", chapters.first().key)
        assertEquals(103.0, chapters.first().number)
        // "2026-07-22T19:56:20.000Z" — an RFC 3339 stamp with a fractional part to skip.
        assertEquals(1_784_750_180_000L, chapters.first().dateUpload)
        // A fractional chapter keeps its decimal.
        assertEquals("stop-smoking|102.5", chapters[1].key)

        module.close()
    }

    @Test
    fun `chapter_pages prefers avif and carries the referer the CDN demands`() {
        if (HIPERCOOL_WASM.isEmpty()) return

        val host = ScriptedHipercoolHost(HIPERCOOL_PAGES)
        val engine = provideWasmEngine()
        val module = engine.instantiate("hipercool", HIPERCOOL_WASM, host)

        val request = SourceCodec.encode(Wire.ChapterPagesRequest(chapterKey = "stop-smoking|103"))
        val pages =
            SourceCodec.decodeEnvelope<List<Wire.PageWire>>(module.call(SourceAbi.Exports.CHAPTER_PAGES, request))

        assertTrue(host.lastUrl.contains("reader.chapterPages"), "url was ${host.lastUrl}")
        assertTrue(host.lastUrl.contains("%22seriesSlug%22%3A%22stop-smoking%22"), "url was ${host.lastUrl}")
        // Without this header the live site answers FORBIDDEN for pages specifically.
        assertEquals("G2ZsDdWhUwdU82Vw", host.lastHeaders["x-flux-node"])

        assertEquals(2, pages.size)
        // pageOrder is 1-based on the wire, the ABI index is 0-based.
        assertEquals(0, pages.first().index)
        assertEquals("https://cdn.example/0001.avif", pages.first().imageUrl, "avif wins where it exists")
        assertEquals("https://cdn.example/0002.webp", pages[1].imageUrl, "webp when it doesn't")
        assertEquals("https://lerhentais.com/", pages.first().headers["Referer"])

        module.close()
    }

    @Test
    fun `the sites own message survives a non-2xx status`() {
        if (HIPERCOOL_WASM.isEmpty()) return

        // A series still in the search index but deleted: `404`, and the only useful part is the body.
        val host =
            ScriptedHipercoolHost(
                Reply(
                    status = 404,
                    body =
                        """[{"error":{"json":{"message":"Série não encontrada","code":-32004,""" +
                            """"data":{"code":"NOT_FOUND","httpStatus":404}}}}]""",
                ),
            )
        val engine = provideWasmEngine()
        val module = engine.instantiate("hipercool", HIPERCOOL_WASM, host)

        val request = SourceCodec.encode(Wire.MangaDetailsRequest(mangaKey = "29538|stop-smoking"))
        val bytes = module.call(SourceAbi.Exports.MANGA_DETAILS, request)

        val failure =
            assertFailsWith<SourceAbiException> { SourceCodec.decodeEnvelope<Wire.MangaWire>(bytes) }
        // "Hipercool returned HTTP 404" would tell the user nothing about which of the several ways
        // this call can fail actually happened.
        assertTrue(
            failure.message?.contains("Série não encontrada") == true,
            "expected the site's own message, got ${failure.message}",
        )
        assertEquals("not_found", failure.code)

        module.close()
    }

    @Test
    fun `a tRPC error element surfaces as a source error`() {
        if (HIPERCOOL_WASM.isEmpty()) return

        val engine = provideWasmEngine()
        val module = engine.instantiate("hipercool", HIPERCOOL_WASM, ScriptedHipercoolHost(HIPERCOOL_FORBIDDEN))

        val request = SourceCodec.encode(Wire.ChapterPagesRequest(chapterKey = "stop-smoking|103"))
        val bytes = module.call(SourceAbi.Exports.CHAPTER_PAGES, request)

        // The site's own refusal ("use the official app") has to reach the user, not be swallowed as
        // an empty page list — that is the symptom of the x-flux-node value having rotated.
        val failure =
            assertFailsWith<SourceAbiException> {
                SourceCodec.decodeEnvelope<List<Wire.PageWire>>(bytes)
            }
        assertTrue(
            failure.message?.contains("aplicativo oficial") == true,
            "expected the site's message, got ${failure.message}",
        )

        module.close()
    }
}

/** One scripted host reply. */
private class Reply(
    val status: Int,
    val body: String
)

/**
 * A [SourceHost] that answers with a scripted sequence of replies and records every request. The last
 * reply repeats once the script runs out, so a test that only cares about one response can give one.
 */
private class ScriptedHipercoolHost(
    private vararg val replies: Reply
) : SourceHost {
    constructor(body: String) : this(Reply(status = 200, body = body))

    val urls = mutableListOf<String>()
    private val headers = mutableListOf<Map<String, String>>()
    private var call = 0

    val lastUrl: String get() = urls.last()
    val lastHeaders: Map<String, String> get() = headers.last()

    fun headersAt(index: Int): Map<String, String> = headers[index]

    override suspend fun httpRequest(request: Wire.HttpRequestWire): Wire.HttpResponseWire {
        urls += request.url
        headers += request.headers
        val reply = replies[minOf(call, replies.lastIndex)]
        call++
        return Wire.HttpResponseWire(status = reply.status, body = reply.body.encodeToByteArray())
    }

    override fun log(
        level: Wire.LogLevel,
        message: String
    ) = Unit
}

/** A tRPC batch reply is an array of `{"result":{"data":{"json": …}}}`, one element per procedure. */
private val HIPERCOOL_SEARCH =
    """
    [{"result":{"data":{"json":{
      "hits":[{"id":29538,"slug":"stop-smoking","title":"Stop Smoking","synopsis":"Sinopse.",
               "coverUrl":"https://cdn.example/cover.png","status":"ongoing","authors":["RA"]}],
      "totalHits":2}}}}]
    """.trimIndent()

private val HIPERCOOL_EMPTY_SEARCH = """[{"result":{"data":{"json":{"hits":[],"totalHits":0}}}}]"""

private val HIPERCOOL_CHAPTERS =
    """
    [{"result":{"data":{"json":[
      {"id":429735,"number":103,"title":null,"createdAt":"2026-07-22T19:56:20.000Z"},
      {"id":429726,"number":102.5,"title":"Extra","createdAt":"2026-07-21T19:56:20.000Z"}
    ]}}}]
    """.trimIndent()

private val HIPERCOOL_PAGES =
    """
    [{"result":{"data":{"json":[
      {"pageOrder":1,"webpUrl":"https://cdn.example/0001.webp","avifUrl":"https://cdn.example/0001.avif"},
      {"pageOrder":2,"webpUrl":"https://cdn.example/0002.webp","avifUrl":null}
    ]}}}]
    """.trimIndent()

/** What the site returns for pages when it doesn't believe the caller — the `x-flux-node` gate. */
private val HIPERCOOL_FORBIDDEN =
    """
    [{"error":{"json":{"message":"Use o aplicativo oficial em lerhentais.com","code":-32003,
      "data":{"code":"FORBIDDEN","httpStatus":403,"path":"reader.chapterPages"}}}}]
    """.trimIndent()