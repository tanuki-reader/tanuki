@file:OptIn(ExperimentalForeignApi::class)

package app.tanuki.core.source.runtime

import app.tanuki.core.source.abi.HttpFrame
import app.tanuki.core.source.abi.SourceCodec
import app.tanuki.core.source.abi.Wire
import cnames.structs.TanukiEngine
import cnames.structs.TanukiModule
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.convert
import kotlinx.cinterop.pointed
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import kotlinx.coroutines.runBlocking
import platform.posix.malloc
import platform.posix.memcpy
import wasmi.tanuki_abi_version
import wasmi.tanuki_buf_free
import wasmi.tanuki_call
import wasmi.tanuki_engine_new
import wasmi.tanuki_module_free
import wasmi.tanuki_module_new

/**
 * iOS [WasmEngine] backed by [wasmi](https://wasmi-labs.github.io) through the `native/wasmi-cabi` C
 * shim (cinterop `wasmi`). The counterpart to Android's [ChicoryWasmEngine]; both satisfy the same
 * [WasmEngine] contract, so [app.tanuki.core.source.WasmSourceLoader] is unchanged across platforms.
 *
 * The memory ABI + import bridge live in Rust (see the shim); this class is the thin Kotlin/Native
 * binding: lifecycle, byte marshalling, and routing the guest's imports to a [SourceHost] via
 * [wasmiHostCall]. One wasmi engine is created per instance (a DI singleton, so process-lifetime) and
 * shared by every module it instantiates.
 */
class WasmiWasmEngine : WasmEngine {

    private val engine: CPointer<TanukiEngine> =
        checkNotNull(tanuki_engine_new()) { "wasmi engine creation failed" }

    override fun instantiate(sourceId: String, wasmBytes: ByteArray, host: SourceHost): WasmModule {
        require(wasmBytes.isNotEmpty()) { "Empty wasm bytes for source \"$sourceId\"" }
        // The host must stay alive as long as the module can call back into it; the module owns this ref
        // and disposes it on close.
        val hostRef = StableRef.create(host)
        val module = wasmBytes.usePinned { pinned ->
            tanuki_module_new(
                engine,
                pinned.addressOf(0).reinterpret(),
                wasmBytes.size,
                staticCFunction(::wasmiHostCall),
                hostRef.asCPointer(),
            )
        }
        if (module == null) {
            hostRef.dispose()
            error("wasmi failed to compile/instantiate source \"$sourceId\"")
        }
        return WasmiWasmModule(module, hostRef)
    }
}

/** A single instantiated wasmi module behind the shim's opaque handle. */
private class WasmiWasmModule(
    private val module: CPointer<TanukiModule>,
    private val hostRef: StableRef<SourceHost>,
) : WasmModule {

    override fun call(export: String, input: ByteArray): ByteArray {
        // addressOf(0) traps on an empty array, so pass a null/0 buffer for no-input exports.
        val buf = if (input.isEmpty()) {
            tanuki_call(module, export, null, 0)
        } else {
            input.usePinned { pinned ->
                tanuki_call(module, export, pinned.addressOf(0).reinterpret(), input.size)
            }
        }
        return try {
            buf.useContents {
                check(len >= 0 && ptr != null) { "wasmi call to export '$export' failed" }
                ptr!!.reinterpret<ByteVar>().readBytes(len)
            }
        } finally {
            tanuki_buf_free(buf)
        }
    }

    override fun callReturningI32(export: String): Int {
        // The shim's scalar call is specialized to the ABI version handshake (the only bare-i32 export).
        val value = tanuki_abi_version(module)
        check(value >= 0) { "wasmi scalar call to export '$export' failed" }
        return value
    }

    override fun close() {
        tanuki_module_free(module)
        hostRef.dispose()
    }
}

/**
 * The host-import callback wasmi invokes when a guest calls a `tanuki.*` import (the iOS analogue of
 * Chicory's import handlers). Must be top-level/capture-free to become a `staticCFunction`; the
 * [SourceHost] travels through [env] as a [StableRef]. `http_request` is driven synchronously via
 * `runBlocking` — the suspend→blocking bridge — and its reply is returned in a `malloc`'d buffer the
 * Rust shim copies out then frees.
 *
 * That bridge blocks the calling thread for a whole HTTP round-trip, which is why
 * [app.tanuki.core.source.WasmSourceLoader] runs the `fetch*` calls on `io` rather than `Default`.
 */
internal fun wasmiHostCall(
    env: COpaquePointer?,
    kind: Int,
    arg: Int,
    inPtr: CPointer<UByteVar>?,
    inLen: Int,
    outPtr: CPointer<CPointerVar<UByteVar>>?,
    outLen: CPointer<IntVar>?,
): Int {
    val host = env!!.asStableRef<SourceHost>().get()
    return when (kind) {
        1 -> { // log
            val message = inPtr?.reinterpret<ByteVar>()?.readBytes(inLen)?.decodeToString() ?: ""
            host.log(Wire.LogLevel.entries.getOrElse(arg) { Wire.LogLevel.INFO }, message)
            0
        }

        0 -> { // http_request
            val request = SourceCodec.decode<Wire.HttpRequestWire>(
                inPtr!!.reinterpret<ByteVar>().readBytes(inLen),
            )
            val responseBytes = HttpFrame.encode(runBlocking { host.httpRequest(request) })
            val buffer = malloc(responseBytes.size.convert())!!.reinterpret<UByteVar>()
            responseBytes.usePinned { pinned ->
                memcpy(buffer, pinned.addressOf(0), responseBytes.size.convert())
            }
            outPtr!!.pointed.value = buffer
            outLen!!.pointed.value = responseBytes.size
            0
        }

        else -> -1
    }
}