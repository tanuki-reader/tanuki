package app.tanuki.core.source.runtime

/**
 * Android WASM engine binding — [ChicoryWasmEngine], the pure-JVM Chicory interpreter (no NDK/JNI).
 * iOS uses wasmi via cinterop instead.
 */
actual fun provideWasmEngine(): WasmEngine = ChicoryWasmEngine()