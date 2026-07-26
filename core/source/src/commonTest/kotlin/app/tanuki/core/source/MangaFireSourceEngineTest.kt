package app.tanuki.core.source

import app.tanuki.core.source.abi.SourceAbi
import app.tanuki.core.source.abi.SourceAbiException
import app.tanuki.core.source.abi.SourceCodec
import app.tanuki.core.source.abi.Wire
import app.tanuki.core.source.fixture.MANGAFIRE_WASM
import app.tanuki.core.source.runtime.SourceHost
import app.tanuki.core.source.runtime.provideWasmEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Runs the real `mangafire.wasm` on both engines (Chicory / wasmi). MangaFire is still a **scaffold** —
 * its VRF request signing isn't configured yet (see `native/sources/mangafire/src/vrf.rs`) — so the
 * useful invariants at this stage are: the bundle loads and describes itself, and every API-backed export
 * fails **loudly and in-band** (a `"config"` `AbiError`) rather than silently sending an invalid token.
 * When the real VrfSigner constants land, this file grows canned-response parsing tests like the MangaDex
 * one. No host request is made here (meta doesn't fetch; the others fail at signing first).
 *
 * Skips itself when [MANGAFIRE_WASM] is empty (no Rust toolchain at build time).
 */
class MangaFireSourceEngineTest {
    @Test
    fun `source_meta describes the source and its listings`() {
        if (MANGAFIRE_WASM.isEmpty()) return

        val engine = provideWasmEngine()
        val module = engine.instantiate("mangafire", MANGAFIRE_WASM, UnusedHost)

        val meta = SourceCodec.decode<Wire.SourceMetaWire>(module.call(SourceAbi.Exports.SOURCE_META, ByteArray(0)))

        assertEquals("mangafire", meta.id)
        assertEquals("MangaFire", meta.name)
        assertEquals("en", meta.lang)
        assertEquals("https://mangafire.to", meta.baseUrl)
        assertTrue(meta.listings.containsAll(listOf("popular", "latest")), "serves popular + latest")

        module.close()
    }

    @Test
    fun `manga_list fails in-band with a config error until vrf signing is wired up`() {
        if (MANGAFIRE_WASM.isEmpty()) return

        val engine = provideWasmEngine()
        val module = engine.instantiate("mangafire", MANGAFIRE_WASM, UnusedHost)

        val request = SourceCodec.encode(Wire.MangaListRequest(listing = "popular", page = 1))
        val error =
            assertFailsWith<SourceAbiException> {
                SourceCodec.decodeEnvelope<Wire.MangaPageWire>(
                    module.call(SourceAbi.Exports.MANGA_LIST, request)
                )
            }

        // The guest reports it can't sign the request, rather than trapping or fetching with a bad token.
        assertEquals("config", error.code)

        module.close()
    }
}

/** A [SourceHost] whose network/log are never reached by these tests — meta doesn't fetch, the rest fail first. */
private object UnusedHost : SourceHost {
    override suspend fun httpRequest(request: Wire.HttpRequestWire): Wire.HttpResponseWire =
        error("mangafire scaffold test made no request")

    override fun log(
        level: Wire.LogLevel,
        message: String
    ) = Unit
}