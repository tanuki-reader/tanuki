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

The app doesn't bundle sources: it installs them from user-added, signed repos (see
`docs/design-remote-sources.md`). Gradle only builds the crates for tests — `:core:source` compiles
`tanuki-sources/sources/*` for its engine tests and embeds `tanuki-sources/source-api/fixtures/` for
the wire contract test. Those tasks **skip themselves** if cargo isn't installed, so Android-only
contributors don't need Rust.

**Trying a source change in the app:** build and serve a local repo from `tanuki-sources` (its README,
"Local repo for the dev loop"), or push it and let CI publish.

**Changing a source:** commit in `tanuki-sources` (and push it), then commit the new submodule pointer
here.
