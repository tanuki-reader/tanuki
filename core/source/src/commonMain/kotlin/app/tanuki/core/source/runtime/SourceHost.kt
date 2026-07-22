package app.tanuki.core.source.runtime

import app.tanuki.core.source.abi.Wire
import app.tanuki.core.source.abi.Wire.HttpRequestWire
import app.tanuki.core.source.abi.Wire.HttpResponseWire

/**
 * The host capabilities a guest source can call back into — the other half of the ABI from
 * [WasmEngine]'s exports (see [app.tanuki.core.source.abi.SourceAbi.Imports]). An implementation is
 * bound to a module's imports at [WasmEngine.instantiate] time.
 *
 * **Threading contract:** these methods are `suspend`, but the WASM import a guest calls is *synchronous*
 * (core-WASM calls don't await). Bridging the two is the **engine binding's** responsibility: when the
 * guest invokes an import, the engine blocks its own worker thread on the corresponding suspend call
 * here. Implementations therefore stay clean and suspend-based; only the per-platform engine deals with
 * the blocking bridge. (The loader already runs all engine work off the caller thread, on
 * [app.tanuki.core.domain.common.service.DispatcherProvider.default].)
 *
 * Routing source HTTP through this seam — rather than letting the guest open its own sockets — is what
 * makes the Cloudflare bypass uniform: every request shares the host's one Ktor client and its
 * cf_clearance cookie (CLAUDE.md → "Cloudflare Bypass"). The interceptor stays a host concern, fully
 * independent of the WASM runtime.
 */
interface SourceHost {
    /** Performs an HTTP request on the source's behalf and returns the host's response. */
    suspend fun httpRequest(request: HttpRequestWire): HttpResponseWire

    /** Emits a diagnostic [message] from the source at [level]. */
    fun log(level: Wire.LogLevel, message: String)
}