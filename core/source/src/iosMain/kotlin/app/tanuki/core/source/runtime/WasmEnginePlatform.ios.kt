package app.tanuki.core.source.runtime

/**
 * iOS WASM engine binding — [WasmiWasmEngine], the wasmi interpreter via the `native/wasmi-cabi` C
 * shim (cinterop). Android uses Chicory instead.
 */
actual fun provideWasmEngine(): WasmEngine = WasmiWasmEngine()