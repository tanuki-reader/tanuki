package app.tanuki.core.source.runtime

import app.tanuki.core.source.abi.HttpFrame
import app.tanuki.core.source.abi.SourceAbi
import app.tanuki.core.source.abi.SourceCodec
import app.tanuki.core.source.abi.Wire
import com.dylibso.chicory.runtime.HostFunction
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.runtime.Store
import com.dylibso.chicory.wasm.Parser
import com.dylibso.chicory.wasm.types.FunctionType
import com.dylibso.chicory.wasm.types.ValType
import kotlinx.coroutines.runBlocking

/**
 * Android [WasmEngine] backed by [Chicory](https://chicory.dev) — a pure-JVM WASM interpreter, so no
 * NDK/JNI and no native artifacts. Interpreter mode only: Chicory's AOT path emits JVM bytecode that
 * Android can't load at runtime.
 *
 * This class owns the engine half of the ABI's two mechanical pieces:
 *  - **Memory ABI** — moving bytes through the guest's linear memory via its `tanuki_alloc`/`tanuki_dealloc`
 *    exports (the `(ptr,len)` ⇄ packed-`i64` dance). All of it stays below [WasmModule.call].
 *  - **Import bridge** — binding the guest's synchronous `tanuki` imports to [SourceHost]. `http_request`
 *    is suspending host-side, so the handler [runBlocking]s the interpreter's worker thread for a whole
 *    HTTP round-trip. That is why [app.tanuki.core.source.WasmSourceLoader] runs the `fetch*` calls on
 *    `io` rather than `Default` — a pool meant to be blocked, instead of the CPU-sized one shared with
 *    every UI state pipeline. The WASM ABI is synchronous by design, so the blocking bridge stays.
 */
class ChicoryWasmEngine : WasmEngine {

    override fun instantiate(sourceId: String, wasmBytes: ByteArray, host: SourceHost): WasmModule {
        val module = Parser.parse(wasmBytes)
        val store = Store()
        store.addFunction(httpRequestImport(host), logImport(host))
        return ChicoryWasmModule(store.instantiate(sourceId, module))
    }

    /** `tanuki.http_request(reqPtr, reqLen) -> (packed ptr,len)` → [SourceHost.httpRequest]. */
    private fun httpRequestImport(host: SourceHost) = HostFunction(
        SourceAbi.Imports.MODULE,
        SourceAbi.Imports.HTTP_REQUEST,
        FunctionType.of(listOf(ValType.I32, ValType.I32), listOf(ValType.I64)),
    ) { instance, args ->
        val request = SourceCodec.decode<Wire.HttpRequestWire>(
            instance.memory().readBytes(args[0].toInt(), args[1].toInt()),
        )
        val responseBytes = HttpFrame.encode(runBlocking { host.httpRequest(request) })
        // Response buffer is allocated via the guest's allocator; the guest frees it after reading.
        longArrayOf(packPtrLen(instance.writeToGuest(responseBytes), responseBytes.size))
    }

    /** `tanuki.log(level, msgPtr, msgLen)` → [SourceHost.log]; no return. */
    private fun logImport(host: SourceHost) = HostFunction(
        SourceAbi.Imports.MODULE,
        SourceAbi.Imports.LOG,
        FunctionType.of(listOf(ValType.I32, ValType.I32, ValType.I32), emptyList()),
    ) { instance, args ->
        val level = Wire.LogLevel.entries.getOrElse(args[0].toInt()) { Wire.LogLevel.INFO }
        host.log(level, instance.memory().readString(args[1].toInt(), args[2].toInt()))
        null
    }
}

/** A single instantiated Chicory module. Performs the memory ABI for every export call. */
private class ChicoryWasmModule(private val instance: Instance) : WasmModule {

    override fun call(export: String, input: ByteArray): ByteArray {
        val inputPtr = instance.writeToGuest(input)
        val packed = instance.export(export).apply(inputPtr.toLong(), input.size.toLong())[0]
        val outPtr = unpackPtr(packed)
        val outLen = unpackLen(packed)
        val result = instance.memory().readBytes(outPtr, outLen)
        // Free both regions: the input we allocated, and the result the guest allocated for us.
        instance.guestDealloc(inputPtr, input.size)
        instance.guestDealloc(outPtr, outLen)
        return result
    }

    override fun callReturningI32(export: String): Int =
        instance.export(export).apply()[0].toInt()

    // Chicory instances need no teardown (plain JVM objects, GC-managed).
    override fun close() = Unit
}

// --- Memory ABI helpers (engine-internal; never surfaced above WasmModule.call) -----------------

/** Allocates [bytes].size in the guest via `tanuki_alloc`, writes them, and returns the pointer. */
private fun Instance.writeToGuest(bytes: ByteArray): Int {
    val ptr = export(SourceAbi.Memory.ALLOC).apply(bytes.size.toLong())[0].toInt()
    memory().write(ptr, bytes)
    return ptr
}

/** Frees a guest region previously obtained from `tanuki_alloc`. */
private fun Instance.guestDealloc(ptr: Int, size: Int) {
    export(SourceAbi.Memory.DEALLOC).apply(ptr.toLong(), size.toLong())
}

/** Packs a `(ptr, len)` pair into the `i64` the ABI returns: high 32 bits ptr, low 32 bits len. */
private fun packPtrLen(ptr: Int, len: Int): Long = (ptr.toLong() shl 32) or (len.toLong() and 0xFFFFFFFFL)

private fun unpackPtr(packed: Long): Int = (packed ushr 32).toInt()

private fun unpackLen(packed: Long): Int = (packed and 0xFFFFFFFFL).toInt()