package app.tanuki.core.source.runtime

/**
 * The single per-platform binding for the embedded WASM runtime. Each platform `actual` returns
 * whichever engine it embeds — Chicory on Android, wasmi on iOS. Bound once as a singleton in
 * [app.tanuki.core.source.di.SourceBindings], so every loaded source shares one engine instance.
 */
expect fun provideWasmEngine(): WasmEngine