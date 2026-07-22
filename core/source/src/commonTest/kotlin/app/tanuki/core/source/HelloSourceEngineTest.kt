package app.tanuki.core.source

import app.tanuki.core.source.abi.SourceAbi
import app.tanuki.core.source.abi.SourceCodec
import app.tanuki.core.source.abi.Wire
import app.tanuki.core.source.fixture.HELLO_WASM
import app.tanuki.core.source.runtime.SourceHost
import app.tanuki.core.source.runtime.provideWasmEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Phase 0 proof: run the **real** `hello.wasm` through the **real** engine and confirm the whole
 * memory ABI works end to end — allocation, the `(ptr,len)` packing, and JSON decode across linear
 * memory. Because [provideWasmEngine] is an `expect`/`actual`, this single test body executes on both
 * engines: Chicory on the Android host test, wasmi on the iOS simulator test.
 *
 * Everything above this — [WasmSourceLoaderTest] — runs against a fake engine and so proves only the
 * loader's *logic*. This is the one test that proves a source binary actually loads and runs, which is
 * the entire risk Phase 0 exists to retire. Keep `hello` around as this fixture even after real
 * sources exist.
 *
 * Skips itself when [HELLO_WASM] is empty — the fixture generator produces empty bytes when the Rust
 * toolchain is absent, so an Android-only contributor without cargo still gets a green suite.
 */
class HelloSourceEngineTest {
    @Test
    fun `real hello wasm loads - reports ABI v2 - and returns its metadata`() {
        if (HELLO_WASM.isEmpty()) return // No toolchain at build time; nothing to run.

        val engine = provideWasmEngine()
        val module = engine.instantiate("hello", HELLO_WASM, NoOpHost)

        // The handshake runs before any payload marshalling is trusted — if this passes, the module
        // instantiated and a bare i32 export round-tripped.
        assertEquals(
            SourceAbi.VERSION,
            module.callReturningI32(SourceAbi.Exports.ABI_VERSION),
            "hello.wasm should report the ABI version the host speaks"
        )

        // SOURCE_META exercises the full memory ABI: the guest allocs a buffer, packs (ptr,len), and
        // the host reads it back and frees both regions (proving tanuki_dealloc is exported too).
        val meta =
            SourceCodec.decode<Wire.SourceMetaWire>(
                module.call(SourceAbi.Exports.SOURCE_META, ByteArray(0))
            )

        assertEquals("hello", meta.id)
        assertEquals("Hello", meta.name)
        assertEquals("en", meta.lang)
        assertEquals(1, meta.version)
        assertEquals(listOf("popular", "latest"), meta.listings.map { it.key })
        assertEquals(listOf("Popular", "Latest Updates"), meta.listings.map { it.name })

        module.close()
    }

    @Test
    fun `an unimplemented export reports an in-band error - not a trap`() {
        if (HELLO_WASM.isEmpty()) return

        val engine = provideWasmEngine()
        val module = engine.instantiate("hello", HELLO_WASM, NoOpHost)

        // hello implements only source_meta; manga_list falls through to the SDK's default, which must
        // return an Envelope.error the host surfaces as SourceAbiException — never a WASM trap.
        val request = SourceCodec.encode(Wire.MangaListRequest(page = 1))
        val error =
            runCatching {
                SourceCodec.decodeEnvelope<Wire.MangaPageWire>(
                    module.call(SourceAbi.Exports.MANGA_LIST, request)
                )
            }.exceptionOrNull()

        assertTrue(
            error is app.tanuki.core.source.abi.SourceAbiException,
            "expected an in-band SourceAbiException, got $error"
        )

        module.close()
    }
}

/** A [SourceHost] the engine binds but the metadata path never invokes. */
private object NoOpHost : SourceHost {
    override suspend fun httpRequest(request: Wire.HttpRequestWire) =
        Wire.HttpResponseWire(status = 200, bodyBase64 = "")

    override fun log(
        level: Wire.LogLevel,
        message: String
    ) = Unit
}