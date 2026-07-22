package app.tanuki.core.source.runtime

/**
 * The embedded WebAssembly runtime, abstracted so the *concrete* engine can be chosen per platform
 * (Chicory on Android, wasmi on iOS) behind a single `expect`/`actual` binding — see [provideWasmEngine].
 * This is the lower of the two source seams:
 *
 * - [app.tanuki.core.domain.source.SourceLoader] is the *app-facing* seam — runtime-agnostic, the only
 *   thing UI/domain code sees. It MUST NOT mention anything in this package.
 * - [WasmEngine] / [WasmModule] are the *runtime-facing* seam — WASM-aware, but still engine-agnostic.
 *   Only [app.tanuki.core.source.WasmSourceLoader] (which bridges the two) is allowed to touch them.
 */
interface WasmEngine {
    /**
     * Instantiates a WASM module from its raw bytes, binding [host] to the module's imports
     * (see [app.tanuki.core.source.abi.SourceAbi.Imports]), ready to have its exports called.
     *
     * @param sourceId source-native id the bytes belong to (e.g. `"mangadex"`); used only for
     *   diagnostics/caching — the engine does not interpret it.
     * @param wasmBytes the complete `.wasm` bundle.
     * @param host the host capabilities (HTTP, log) the guest may call back into. The engine bridges
     *   the guest's synchronous imports onto [host]'s suspend calls — see [SourceHost].
     */
    fun instantiate(sourceId: String, wasmBytes: ByteArray, host: SourceHost): WasmModule
}

/**
 * A single instantiated WASM module. One per loaded source; not safe to assume re-entrancy across
 * concurrent calls (the loader serializes/threads as needed).
 *
 * The call shape is a named export taking one byte buffer and returning one byte buffer. Below this
 * call the engine performs the memory ABI (`tanuki_alloc`/`tanuki_dealloc`, the `(ptr,len)` ⇄ packed
 * `i64` dance); none of that — pointers, lengths, linear memory — is visible here.
 */
interface WasmModule : AutoCloseable {
    /**
     * Invokes an exported function by [export] name, passing [input] and returning the raw result
     * bytes. Caller (the loader) owns serializing [input] and deserializing the result per the ABI.
     */
    fun call(export: String, input: ByteArray): ByteArray

    /**
     * Invokes an export that takes no arguments and returns a bare `i32` — only the ABI version
     * handshake ([app.tanuki.core.source.abi.SourceAbi.Exports.ABI_VERSION]) uses this shape, as it must
     * be callable before any payload marshalling is trusted.
     */
    fun callReturningI32(export: String): Int
}

/**
 * Placeholder [WasmEngine] for a platform that has no concrete engine wired yet. It instantiates
 * nothing — [instantiate] fails loudly — so the DI graph still resolves while making it unmistakable
 * that no source can run on that platform. Kept for parity/diagnostics; both current platforms bind a
 * real engine.
 *
 * @param platform short platform label for the error message (e.g. `"Android"`, `"iOS"`).
 */
class UnavailableWasmEngine(private val platform: String) : WasmEngine {
    override fun instantiate(sourceId: String, wasmBytes: ByteArray, host: SourceHost): WasmModule =
        throw NotImplementedError(
            "No WASM engine is wired for $platform yet. Provide one from provideWasmEngine(). " +
                "Attempted to instantiate source \"$sourceId\".",
        )
}