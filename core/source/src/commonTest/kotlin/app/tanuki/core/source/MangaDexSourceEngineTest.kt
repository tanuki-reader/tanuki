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
    fun `source_meta describes the source and points at a decodable icon`() {
        if (MANGADEX_WASM.isEmpty()) return

        val engine = provideWasmEngine()
        val module = engine.instantiate("mangadex", MANGADEX_WASM, ScriptedHost(CANNED_EMPTY))

        val meta = SourceCodec.decode<Wire.SourceMetaWire>(module.call(SourceAbi.Exports.SOURCE_META, ByteArray(0)))

        // The fixture is the crate's default (English) language build; the pt-BR one differs only in its
        // id, its lang, and the tag it filters the API by. The display name is deliberately the *same*
        // across both — the language is the row's subtitle, so carrying it in the name too would repeat it.
        assertEquals("mangadex", meta.id)
        assertEquals("MangaDex", meta.name)
        assertEquals("en", meta.lang)
        // A raster icon, not the site's .svg logo or .ico favicon — Coil decodes neither, and a source
        // that reports one would silently show the lettered fallback forever.
        assertEquals("https://mangadex.org/pwa/icons/icon-180.png", meta.iconUrl)

        module.close()
    }

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
    fun `the default catalog is A to Z and explicit ratings need the user's consent`() {
        if (MANGADEX_WASM.isEmpty()) return

        val host = CapturingHost(CANNED_POPULAR)
        val module = provideWasmEngine().instantiate("mangadex", MANGADEX_WASM, host)

        module.call(SourceAbi.Exports.MANGA_LIST, SourceCodec.encode(Wire.MangaListRequest(page = 1)))
        assertTrue(host.lastUrl.contains("order[title]=asc"), "url was ${host.lastUrl}")
        assertTrue("erotica" !in host.lastUrl, "url was ${host.lastUrl}")

        module.call(SourceAbi.Exports.MANGA_LIST, SourceCodec.encode(Wire.MangaListRequest(page = 1, allowSensitive = true)))
        assertTrue(host.lastUrl.contains("contentRating[]=erotica"), "url was ${host.lastUrl}")

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

    @Test
    fun `chapter_list maps the feed and skips chapters it cannot open`() {
        if (MANGADEX_WASM.isEmpty()) return

        val host = ScriptedHost(CANNED_FEED)
        val engine = provideWasmEngine()
        val module = engine.instantiate("mangadex", MANGADEX_WASM, host)

        val request = SourceCodec.encode(Wire.ChapterListRequest(mangaKey = "op-id"))
        val chapters =
            SourceCodec.decodeEnvelope<List<Wire.ChapterWire>>(
                module.call(SourceAbi.Exports.CHAPTER_LIST, request)
            )

        val url = host.urls.single()
        assertTrue(url.contains("api.mangadex.org/manga/op-id/feed"), "url was $url")
        assertTrue(url.contains("translatedLanguage[]=en"), "url was $url")
        assertTrue(url.contains("includes[]=scanlation_group"), "url was $url")

        // Four entries in, two out: the externally-hosted chapter and the delisted (pages 0) one are
        // dropped, since neither has pages the reader could fetch.
        assertEquals(2, chapters.size, "got ${chapters.map(Wire.ChapterWire::key)}")

        val first = chapters.first()
        assertEquals("ch-1", first.key)
        assertEquals("Romance Dawn", first.name)
        assertEquals(1.0, first.number)
        assertEquals("TCB Scans, Vyrell", first.scanlator, "every credited group should be listed")
        // 2018-01-01T12:30:00+00:00
        assertEquals(1_514_809_800_000L, first.dateUpload)

        // A half-chapter with no title of its own: number parses, name stays null so the app derives
        // a display name, and the zone offset is applied (-03:00 → three hours later in UTC).
        val half = chapters[1]
        assertEquals("ch-1-5", half.key)
        assertEquals(null, half.name)
        assertEquals(1.5, half.number)
        assertEquals(1_614_913_200_000L, half.dateUpload)
        assertEquals(null, half.scanlator, "no scanlation group should stay absent, not empty")

        module.close()
    }

    @Test
    fun `chapter_list walks every feed page`() {
        if (MANGADEX_WASM.isEmpty()) return

        // total 501 with 500 per page: the guest must come back for the remainder, then stop.
        val host = ScriptedHost(feedPage(offset = 0, count = 500, total = 501), feedPage(offset = 500, count = 1, total = 501))
        val engine = provideWasmEngine()
        val module = engine.instantiate("mangadex", MANGADEX_WASM, host)

        val request = SourceCodec.encode(Wire.ChapterListRequest(mangaKey = "op-id"))
        val chapters =
            SourceCodec.decodeEnvelope<List<Wire.ChapterWire>>(
                module.call(SourceAbi.Exports.CHAPTER_LIST, request)
            )

        assertEquals(501, chapters.size)
        assertEquals(2, host.urls.size, "the walk should stop once it has passed `total`")
        assertTrue(host.urls[0].contains("limit=500&offset=0"), "url was ${host.urls[0]}")
        assertTrue(host.urls[1].contains("limit=500&offset=500"), "url was ${host.urls[1]}")

        module.close()
    }

    @Test
    fun `manga_details fetches the single entity and parses the fields browse omits`() {
        if (MANGADEX_WASM.isEmpty()) return

        val host = ScriptedHost(CANNED_DETAILS)
        val engine = provideWasmEngine()
        val module = engine.instantiate("mangadex", MANGADEX_WASM, host)

        val request = SourceCodec.encode(Wire.MangaDetailsRequest(mangaKey = "op-id"))
        val manga =
            SourceCodec.decodeEnvelope<Wire.MangaWire>(
                module.call(SourceAbi.Exports.MANGA_DETAILS, request)
            )

        // A single-entity lookup (`/manga/{id}`), with the cover/author relationships inlined.
        val url = host.urls.single()
        assertTrue(url.contains("api.mangadex.org/manga/op-id"), "url was $url")
        assertTrue(url.contains("includes[]=cover_art"), "url was $url")

        assertEquals("op-id", manga.key)
        assertEquals("https://mangadex.org/title/op-id", manga.url)
        assertEquals("One Piece", manga.title)
        // The whole reason details exists beyond the catalog entry: the synopsis browse deliberately drops.
        assertEquals("Pirates and dreams.", manga.description)
        assertEquals("https://uploads.mangadex.org/covers/op-id/cover.jpg.512.jpg", manga.coverUrl)
        assertEquals("Eiichiro Oda", manga.author)
        assertEquals("ongoing", manga.status)
        // The series' ids elsewhere, for exact metadata matching.
        assertEquals(30013L, manga.anilistId)
        assertEquals(13L, manga.malId)

        module.close()
    }

    @Test
    fun `chapter_pages resolves the at-home server into ordered full-quality image urls`() {
        if (MANGADEX_WASM.isEmpty()) return

        val host = ScriptedHost(CANNED_AT_HOME)
        val engine = provideWasmEngine()
        val module = engine.instantiate("mangadex", MANGADEX_WASM, host)

        val request = SourceCodec.encode(Wire.ChapterPagesRequest(chapterKey = "ch-1"))
        val pages =
            SourceCodec.decodeEnvelope<List<Wire.PageWire>>(
                module.call(SourceAbi.Exports.CHAPTER_PAGES, request)
            )

        assertTrue(host.urls.single().contains("api.mangadex.org/at-home/server/ch-1"), "url was ${host.urls.single()}")

        // Three file names → three pages, zero-based and in feed order, each a `{base}/data/{hash}/{file}`
        // URL. The base URL's trailing slash is trimmed (the canned one has one) so there's no `//data`.
        assertEquals(3, pages.size)
        assertEquals(0, pages.first().index)
        assertEquals("https://cdn.mangadex.org/node1/data/abc123/1.jpg", pages.first().imageUrl)
        assertEquals(2, pages.last().index)
        assertEquals("https://cdn.mangadex.org/node1/data/abc123/3.jpg", pages.last().imageUrl)
        // MangaDex's CDN needs no per-page headers; the field defaults to empty (the common path).
        assertTrue(pages.first().headers.isEmpty())

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
            body = responseJson.encodeToByteArray()
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
       "attributes":{"title":{"en":"One Piece"},"description":{"en":"Pirates."},"status":"ongoing","originalLanguage":"ja",
                     "links":{"al":"30013","mal":"13","mu":"pb8uwds"}},
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

/**
 * A single-entity `/manga/{id}` response — note `data` is one object, not a collection — carrying the
 * `description` browse omits plus the inlined cover_art/author relationships.
 */
private val CANNED_DETAILS =
    """
    {"result":"ok","response":"entity","data":
      {"id":"op-id","type":"manga",
       "attributes":{"title":{"en":"One Piece"},"description":{"en":"Pirates and dreams."},
                     "status":"ongoing","originalLanguage":"ja",
                     "links":{"al":"30013","mal":"13","mu":"pb8uwds"}},
       "relationships":[
         {"type":"cover_art","attributes":{"fileName":"cover.jpg"}},
         {"type":"author","attributes":{"name":"Eiichiro Oda"}}
       ]}}
    """.trimIndent()

/**
 * An `/at-home/server/{id}` response: a CDN base URL (with a trailing slash, to exercise the guest's
 * trimming) plus the chapter hash and three ordered page file names. `dataSaver` is present but unread.
 */
private val CANNED_AT_HOME =
    """
    {"result":"ok","baseUrl":"https://cdn.mangadex.org/node1/",
     "chapter":{"hash":"abc123","data":["1.jpg","2.jpg","3.jpg"],"dataSaver":["1s.jpg","2s.jpg","3s.jpg"]}}
    """.trimIndent()

/**
 * A [SourceHost] that answers successive requests with successive bodies (repeating the last one once
 * the script runs out) and records every URL it was asked for — enough to drive a paginated walk.
 */
@OptIn(ExperimentalEncodingApi::class)
private class ScriptedHost(vararg responses: String) : SourceHost {
    private val responses = responses.toList()
    private val _urls = mutableListOf<String>()
    val urls: List<String> get() = _urls

    override suspend fun httpRequest(request: Wire.HttpRequestWire): Wire.HttpResponseWire {
        val body = responses.getOrElse(_urls.size) { responses.last() }
        _urls += request.url
        return Wire.HttpResponseWire(status = 200, body = body.encodeToByteArray())
    }

    override fun log(level: Wire.LogLevel, message: String) = Unit
}

/**
 * A `/manga/{id}/feed` page covering the cases the mapper has to survive: two readable chapters (one
 * titled with two scanlation groups, one untitled with a fractional number and a non-UTC timestamp),
 * one hosted off-site, and one delisted with zero pages.
 */
private val CANNED_FEED =
    """
    {"result":"ok","response":"collection","data":[
      {"id":"ch-1","type":"chapter",
       "attributes":{"chapter":"1","title":"Romance Dawn","translatedLanguage":"en","pages":45,
                     "publishAt":"2018-01-01T12:30:00+00:00","readableAt":"2018-01-01T12:30:00+00:00"},
       "relationships":[
         {"type":"scanlation_group","attributes":{"name":"TCB Scans"}},
         {"type":"scanlation_group","attributes":{"name":"Vyrell"}}
       ]},
      {"id":"ch-1-5","type":"chapter",
       "attributes":{"chapter":"1.5","title":"","translatedLanguage":"en","pages":12,
                     "publishAt":"2021-03-05T00:00:00-03:00"},
       "relationships":[]},
      {"id":"ch-external","type":"chapter",
       "attributes":{"chapter":"2","translatedLanguage":"en","pages":0,
                     "externalUrl":"https://mangaplus.shueisha.co.jp/viewer/1","publishAt":"2019-01-01T00:00:00+00:00"},
       "relationships":[]},
      {"id":"ch-delisted","type":"chapter",
       "attributes":{"chapter":"3","translatedLanguage":"en","pages":0,"publishAt":"2019-02-01T00:00:00+00:00"},
       "relationships":[]}
    ],"limit":500,"offset":0,"total":4}
    """.trimIndent()

/** A synthetic feed page of [count] minimal chapters, for exercising the multi-request walk. */
private fun feedPage(offset: Int, count: Int, total: Int): String {
    val data = (0 until count).joinToString(",") { index ->
        """{"id":"ch-${offset + index}","type":"chapter",""" +
            """"attributes":{"chapter":"${offset + index + 1}","translatedLanguage":"en","pages":10},""" +
            """"relationships":[]}"""
    }
    return """{"result":"ok","response":"collection","data":[$data],"limit":500,"offset":$offset,"total":$total}"""
}