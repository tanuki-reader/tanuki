package app.tanuki.core.source

import app.tanuki.core.source.abi.SourceAbi
import app.tanuki.core.source.abi.SourceCodec
import app.tanuki.core.source.abi.Wire
import app.tanuki.core.source.fixture.MANHASTRO_WASM
import app.tanuki.core.source.runtime.SourceHost
import app.tanuki.core.source.runtime.provideWasmEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs the real `manhastro.wasm` on both engines (Chicory / wasmi) against canned JSON, so the whole
 * path — guest builds a URL → host import → guest parses the reply — is exercised without hitting the
 * live site.
 *
 * Manhastro is Tanuki's first pt-BR source, so the assertions here are mostly about the two things
 * that makes different: the language tag it reports, and picking the Brazilian title/synopsis over the
 * original-language one while keeping the original as `originalTitle`.
 *
 * Skips itself when [MANHASTRO_WASM] is empty (no Rust toolchain at build time).
 */
class ManhastroSourceEngineTest {
    @Test
    fun `source_meta describes a pt-BR source with both listings`() {
        if (MANHASTRO_WASM.isEmpty()) return

        val engine = provideWasmEngine()
        val module = engine.instantiate("manhastro", MANHASTRO_WASM, ManhastroHost(MANHASTRO_EMPTY))

        val meta = SourceCodec.decode<Wire.SourceMetaWire>(module.call(SourceAbi.Exports.SOURCE_META, ByteArray(0)))

        assertEquals("manhastro", meta.id)
        assertEquals("Manhastro", meta.name)
        assertEquals("pt-BR", meta.lang)
        assertEquals("https://manhastro.net", meta.baseUrl)
        assertTrue(meta.listings.containsAll(listOf("popular", "latest")), "serves popular + latest")

        module.close()
    }

    @Test
    fun `manga_list prefers the Brazilian title and keeps the original`() {
        if (MANHASTRO_WASM.isEmpty()) return

        val host = ManhastroHost(MANHASTRO_CATALOG)
        val engine = provideWasmEngine()
        val module = engine.instantiate("manhastro", MANHASTRO_WASM, host)

        val request = SourceCodec.encode(Wire.MangaListRequest(listing = "popular", page = 1))
        val page = SourceCodec.decodeEnvelope<Wire.MangaPageWire>(module.call(SourceAbi.Exports.MANGA_LIST, request))

        // Popular has its own endpoint, which returns whole records rather than id stubs.
        assertTrue(host.lastUrl.endsWith("/rank/diario"), "url was ${host.lastUrl}")

        assertEquals(1, page.entries.size)
        val soloLeveling = page.entries.first()
        assertEquals("6881", soloLeveling.key)
        assertEquals("Nível Único", soloLeveling.title)
        assertEquals("SOLO LEVELING", soloLeveling.originalTitle)
        assertEquals("https://manhastro.net/manga/6881", soloLeveling.url)
        // The cover arrives scheme-relative and has to be made absolute for the image loader.
        assertEquals("https://capa.manhastro.net/capa-6881.jpg", soloLeveling.coverUrl)
        assertEquals("ongoing", soloLeveling.status?.lowercase())
        // `descricao_brasil` wins over `descricao`, matching the title choice.
        assertEquals("Dez anos atrás, um Portal se abriu.", soloLeveling.description)
        // The listing endpoints carry no `meta`, so there is no second page to offer.
        assertTrue(!page.hasNextPage, "listing endpoints are unpaginated")

        module.close()
    }

    @Test
    fun `manga_list search pages through the catalog endpoint`() {
        if (MANHASTRO_WASM.isEmpty()) return

        val host = ManhastroHost(MANHASTRO_SEARCH)
        val engine = provideWasmEngine()
        val module = engine.instantiate("manhastro", MANHASTRO_WASM, host)

        val request = SourceCodec.encode(Wire.MangaListRequest(query = "nível único", page = 2))
        val page = SourceCodec.decodeEnvelope<Wire.MangaPageWire>(module.call(SourceAbi.Exports.MANGA_LIST, request))

        assertTrue(host.lastUrl.contains("/dados?"), "url was ${host.lastUrl}")
        assertTrue(host.lastUrl.contains("page=2"), "url was ${host.lastUrl}")
        // The accented query has to survive as percent-encoded UTF-8.
        assertTrue(host.lastUrl.contains("nome=n%C3%ADvel%20%C3%BAnico"), "url was ${host.lastUrl}")
        // Unlike the listings, /dados reports whether another page exists.
        assertTrue(page.hasNextPage, "meta.has_more should drive pagination")

        module.close()
    }

    @Test
    fun `chapter_list reads the number out of the chapter name`() {
        if (MANHASTRO_WASM.isEmpty()) return

        val host = ManhastroHost(MANHASTRO_CHAPTERS)
        val engine = provideWasmEngine()
        val module = engine.instantiate("manhastro", MANHASTRO_WASM, host)

        val request = SourceCodec.encode(Wire.ChapterListRequest(mangaKey = "6881"))
        val chapters =
            SourceCodec.decodeEnvelope<List<Wire.ChapterWire>>(module.call(SourceAbi.Exports.CHAPTER_LIST, request))

        assertTrue(host.lastUrl.endsWith("/dados/6881"), "url was ${host.lastUrl}")
        assertEquals(2, chapters.size)

        // Manhastro has no numeric chapter field — the number is parsed out of "Capitulo 12.5".
        assertEquals(12.5, chapters.first().number)
        assertEquals("Capitulo 12.5", chapters.first().name)
        // "2025-12-28 13:21:24" read as UTC.
        assertEquals(1_766_928_084_000L, chapters.first().dateUpload)

        // A chapter named without a number stays unnumbered rather than becoming a wrong 0.
        assertNull(chapters[1].number)

        module.close()
    }

    @Test
    fun `chapter_pages joins the base url hash and file names`() {
        if (MANHASTRO_WASM.isEmpty()) return

        val host = ManhastroHost(MANHASTRO_PAGES)
        val engine = provideWasmEngine()
        val module = engine.instantiate("manhastro", MANHASTRO_WASM, host)

        val request = SourceCodec.encode(Wire.ChapterPagesRequest(chapterKey = "563736"))
        val pages =
            SourceCodec.decodeEnvelope<List<Wire.PageWire>>(module.call(SourceAbi.Exports.CHAPTER_PAGES, request))

        assertTrue(host.lastUrl.endsWith("/paginas/563736"), "url was ${host.lastUrl}")
        assertEquals(2, pages.size)
        assertEquals("https://temp2.manhastro.net/manga_abc/capitulo-2/1.webp", pages.first().imageUrl)
        assertEquals(1, pages[1].index)
        // Manhastro's CDN serves page images without a Referer, so nothing rides along.
        assertTrue(pages.first().headers.isEmpty(), "no per-page headers expected")

        module.close()
    }

    @Test
    fun `chapter_pages returns nothing for a text-only release`() {
        if (MANHASTRO_WASM.isEmpty()) return

        val engine = provideWasmEngine()
        val module = engine.instantiate("manhastro", MANHASTRO_WASM, ManhastroHost(MANHASTRO_TEXT_RELEASE))

        val request = SourceCodec.encode(Wire.ChapterPagesRequest(chapterKey = "1"))
        val pages =
            SourceCodec.decodeEnvelope<List<Wire.PageWire>>(module.call(SourceAbi.Exports.CHAPTER_PAGES, request))

        // `data.chapter` is absent on a text release; that's an empty chapter, not a parse failure.
        assertTrue(pages.isEmpty(), "text releases have no page images")

        module.close()
    }
}

/** A [SourceHost] that records the requested URL and answers with a fixed JSON body. */
private class ManhastroHost(
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

/** Empty catalog, for the tests that only need the source to load. */
private val MANHASTRO_EMPTY = """{"success":true,"data":[]}"""

/** One bilingual series, trimmed to the fields the source reads. A listing response carries no `meta`. */
private val MANHASTRO_CATALOG =
    """
    {"success":true,"data":[
      {"manga_id":6881,
       "titulo":"SOLO LEVELING","titulo_brasil":"Nível Único",
       "descricao":"Ten years ago, a Gate opened.",
       "descricao_brasil":"Dez anos atrás, um Portal se abriu.",
       "imagem":"capa.manhastro.net/capa-6881.jpg",
       "status":"on-going"}
    ]}
    """.trimIndent()

/** A search page that reports another page behind it. */
private val MANHASTRO_SEARCH =
    """
    {"success":true,"data":[
      {"manga_id":6881,"titulo":"SOLO LEVELING","imagem":"https://capa.manhastro.net/capa-6881.jpg"}
    ],"meta":{"current_page":2,"has_more":true}}
    """.trimIndent()

/** Two chapters: one numbered in its name, one not. */
private val MANHASTRO_CHAPTERS =
    """
    {"success":true,"data":[
      {"capitulo_id":563736,"capitulo_nome":"Capitulo 12.5","capitulo_data":"2025-12-28 13:21:24"},
      {"capitulo_id":563737,"capitulo_nome":"Extra","capitulo_data":"2025-12-28 13:24:24"}
    ]}
    """.trimIndent()

/** A two-page chapter. The page path is `{baseUrl}/{hash}/{file}`, and `hash` itself holds a slash. */
private val MANHASTRO_PAGES =
    """
    {"success":true,"data":{"text":false,"chapter":{
      "baseUrl":"https://temp2.manhastro.net",
      "hash":"manga_abc/capitulo-2",
      "data":["1.webp","2.webp"]}}}
    """.trimIndent()

/** A release with no images — `data.chapter` is simply absent. */
private val MANHASTRO_TEXT_RELEASE = """{"success":true,"data":{"text":true}}"""