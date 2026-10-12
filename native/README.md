# native/

Rust code, for two unrelated reasons.

| Directory | What | Why Rust |
|---|---|---|
| `wasmi-cabi/` | C-ABI shim over the [wasmi](https://github.com/wasmi-labs/wasmi) interpreter | iOS has no WASM engine and can't JIT; Kotlin/Native can only call C |
| `tanuki-sources/` | **Submodule** ([tanuki-reader/tanuki-sources](https://github.com/tanuki-reader/tanuki-sources)): the guest SDK (`source-api/`), first-party sources (`sources/`), and the repo publishing tooling | Guests must be linear-memory WASM; see that repo's README |

`wasmi-cabi` is part of the **host**; everything in `tanuki-sources` is a **guest**. They never
reference each other — they meet at the ABI, over JSON.

## Setup

```bash
git submodule update --init     # once per clone (or clone with --recursive)
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
rustup target add aarch64-apple-ios aarch64-apple-ios-sim   # for wasmi-cabi
# tanuki-sources/rust-toolchain.toml brings the pinned compiler + wasm32 target for the sources.
```

## Building

Until the app installs sources from a repo (Phase 2 of `docs/design-remote-sources.md`), Gradle still
builds them: `:shared:buildDevSources` compiles each crate in `tanuki-sources/sources/` and copies the
`.wasm` into `shared/src/androidMain/resources/sources/`, where `DevSourceInstaller` picks it up on
launch. `:core:source` builds the same crates for its engine tests and embeds
`tanuki-sources/source-api/fixtures/` for the wire contract test.

The cargo tasks **skip themselves** if cargo isn't installed, so Android-only contributors don't need
Rust.

**Changing a source:** commit in `tanuki-sources` (and push it), then commit the new submodule pointer
here. The app builds against whatever commit the pointer pins.
