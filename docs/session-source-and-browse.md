# Session handoff — Source system + Browse UI

Context for a future session. Covers the WASM source pipeline (Phase 0–1) and the Browse feature.
Read alongside `App/CLAUDE.md` (the canonical project doc); this records what was **built and decided**
in this session, not the whole project.

> Status at handoff: everything through the MangaDex source is **built and green on both engines**
> (Chicory/Android host test, wasmi/iOS sim test). The Browse UI slice is **written but not yet
> compiled** — see "Open / unverified" at the bottom.

---

## Mental model (how a source works, end to end)

```
BrowseVM → SourceLoader (interface, runtime-agnostic)      ← SEAM 1
         → WasmSourceLoader (bridges the two seams)
         → WasmEngine / WasmModule (WASM-aware)            ← SEAM 2
             Android: ChicoryWasmEngine (pure JVM)
             iOS:     WasmiWasmEngine  (Rust wasmi via C shim, cinterop)
         → the .wasm guest  (Rust, separate identity — not app.tanuki)
             guest calls back UP into the host: http_request, log
             → KtorSourceHost → shared Ktor client (future: Cloudflare interceptor)
```

- **Host = the Kotlin app. Guest = the `.wasm`.** Import/export names are from the guest's POV:
  *exports* = functions the host calls down into (`manga_list`…); *imports* = host functions the guest
  calls up into (`http_request`, `log`).
- A source is a **`.wasm` binary**, identified by a **string id** (`"mangadex"`), not a JVM package.
  It lives in its own repo eventually (`tanuki/sources`); for now under `App/native/sources/`.
- The engines are **interpreters** (no JIT — iOS forbids it, Android can't load runtime JVM bytecode).
- **Cloudflare payoff:** guests have *no* network capability; every request must go through the host's
  one Ktor client, so the interceptor (not built yet) applies uniformly and can't be bypassed.

---

## Why Rust for guests (not Kotlin)

Decided after investigation: **Kotlin/Wasm cannot be a guest.** It compiles to **WasmGC** (needs the GC
+ exception-handling proposals), which (a) neither wasmi nor Chicory can parse, and (b) puts objects in a
GC heap separate from linear memory — so it exports no `malloc` and can't satisfy the linear-memory ABI.
This is a memory-model mismatch, not a "wait for support" issue.

Guests are **Rust** compiled to `wasm32-unknown-unknown`. All `unsafe` is quarantined in the SDK
(~60 lines); an actual source is a ~100–200 line data-mapping file with no unsafe/lifetimes. If Rust ever
feels wrong, AssemblyScript is the fallback — cheap to switch while the ABI is small.

---

## The ABI (v2)

Host side: `core/source/src/commonMain/.../abi/` — `SourceAbi.kt` (constants), `Wire.kt` (JSON payloads),
`SourceCodec.kt` (JSON config), `Mappers.kt` (wire→domain). Guest side: `native/source-api/src/`
(`abi.rs`, `wire.rs`, `lib.rs`). **`Wire.kt` and `wire.rs` must stay field-for-field identical** —
nothing enforces it but care + the cross-engine tests. Change both in one commit.

- **Version handshake:** `SourceAbi.VERSION == 2`. Guest exports `abi_version()`; host refuses a
  mismatched guest at load (loud failure, no shims). Bump `ABI_VERSION` (Rust) and `SourceAbi.VERSION`
  (Kotlin) together on any breaking change.
- **Exports (host→guest):** `abi_version`, `source_meta`, `manga_list`, `manga_details`,
  `chapter_list`, `chapter_pages`. Only `source_meta` returns a **bare** payload (no envelope); the rest
  return a `Wire.Envelope` (ok/error, so guests report failures in-band rather than trapping).
- **Imports (guest→host):** `http_request`, `log`, under import module `"tanuki"`.
- **Memory ABI:** `tanuki_alloc`/`tanuki_dealloc` + a packed-`i64` `(ptr,len)` return. A WASM fn returns
  one scalar, hence the packing. Fully hidden below `WasmModule.call`.

### Two ABI bugs the cross-engine test caught (both silent on Chicory, fatal on wasmi)
1. **`tanuki_dealloc` dead-code-eliminated.** A `#[no_mangle]` symbol in a dependency rlib isn't
   guaranteed to export from the final `.wasm`, and the guest never calls dealloc itself. **Fix:** the
   memory exports are emitted by the `export_source!` macro *in the source crate*, making them roots.
2. **`source_meta` arity mismatch.** It was `() -> i64` but the host calls every bytes-export as
   `(ptr,len) -> i64`. Chicory tolerates extra args; wasmi's `get_typed_func` is strict. **Fix:** every
   bytes-export now takes `(ptr,len)` uniformly (`source_meta` ignores them).

**Lesson:** always run `:core:source:testAndroidHostTest` **and** `:core:source:iosSimulatorArm64Test`.
Chicory being lenient means Android-only green is not proof.

### Wire types (domain shapes)
Pre-persistence, source-native, no local `Uuid`, no user state:
`SourceMeta` (id, name, lang, `version: Int?`, `listings`), `SourceListing` (**closed** enum of browse
tabs the contract defines — `POPULAR`, `LATEST`; a source declares which it serves, the app owns the
localized label and the tab order),
`MangaListing` (`sourceMangaKey`, `url: Url?` permalink, title, cover, author, description, status),
`MangaPage` (entries + `hasNextPage`), `ChapterListing`, `Page`.

`SourceLoader` interface (the "never break this" seam):
```
loadSource(id) : Source                          // installs on first load, mints local Uuid
listSources() : List<SourceMeta>
fetchMangaList(sourceId, listing: SourceListing?, query?, page) : MangaPage
fetchMangaDetails(sourceId, mangaKey) : MangaListing
fetchChapterList(sourceId, mangaKey) : List<ChapterListing>
fetchChapterPages(sourceId, chapterId) : List<Page>
```

---

## Why `native/` exists (the iOS cost)

Android got a WASM interpreter for free (**Chicory**, a JAR). iOS had none, so `native/wasmi-cabi/` is a
thin Rust crate wrapping **wasmi** in a C ABI (`tanuki_wasmi.h`), built by cargo into `libwasmi_cabi.a`,
linked via Kotlin/Native **cinterop**. This is why the iOS build needs a Rust toolchain. `WasmEngine` is
the seam that makes swapping engines (e.g. WAMR, WasmKit) a one-`actual` change — considered Wasmer
(it's just wasmi under the hood on iOS) and rejected as added indirection.

Directory layout under `App/native/`:
- `wasmi-cabi/` — host-side iOS engine shim (part of the app).
- `source-api/` — **guest SDK** (rlib): allocator, packing, host-import wrappers, `Source` trait +
  `export_source!` macro, serde wire types.
- `sources/hello/` — smallest possible source (`source_meta` only); the permanent cross-engine fixture.
- `sources/mangadex/` — first real source.

Keeping the SDK + sources in-repo for now (not a separate `tanuki/source-api` repo): while the ABI still
moves, one repo means an ABI change is one atomic commit. **Dependency direction is a V:** app → ABI ←
guest. A guest must never depend on the app; they agree only on the JSON wire format (per-language
bindings, no shared code). Extract to its own repo when a second party depends on it.

---

## What was built, by phase

### Phase 0 — prove a `.wasm` runs (DONE, green both engines)
- Rust guest SDK (`native/source-api`).
- `hello` source; builds to ~76 KB `.wasm`.
- Gradle: `:shared:buildDevSources` compiles `native/sources/*` → composeResources; **skips cleanly if
  cargo is absent** so Android-only contributors don't need Rust.
- `DevSourceInstaller` copies bundled `.wasm` into `SourceBundleStore` on launch (dev-only; the real
  remote-repo installer replaces it).
- Cross-engine test `HelloSourceEngineTest` runs the real `.wasm` through both engines. Fixtures are
  generated as compiled-in base64 (`generate<Crate>Fixture` Gradle tasks) because Kotlin/Native tests
  have no classpath resources.
- Enabled `withHostTest {}` on `:core:source` so Chicory is actually exercised (it had none before).

### Phase 1 — MangaDex source (DONE, green both engines)
- `native/sources/mangadex`: `source_meta` + `manga_list` against `api.mangadex.org` (JSON:API). Builds
  the `/manga` URL (popular→`followedCount`, latest→`latestUploadedChapter`, query→percent-encoded
  `title`), fetches via the host `http_request` import, parses into `MangaPage`. Cover URL =
  `uploads.mangadex.org/covers/{id}/{fileName}.512.jpg`. No networking crate in the guest.
- `MangaDexSourceEngineTest`: runs the real `mangadex.wasm` on both engines with a **fake host returning
  canned JSON** — proves the guest→host→guest bridge + parsing without network flakiness. (Does NOT hit
  live MangaDex — deliberately; that's an untrusted changing dependency.)
- `MangaWire`/`MangaListing` gained an optional **`url` permalink** (the guest owns its URL scheme).

### Also: source versioning (DONE)
`SourceMetaWire.version` now flows to `SourceMeta`, `Source`, and a `sources.version` column (Room v2 +
`Migration1To2`). Monotonic `Int`, not semver. Update *detection* needs the remote repo index (later);
persisting the installed version is the prerequisite and is done. Note the DB uses hand-written
migrations, never destructive fallback (library/progress/history aren't re-fetchable).

### Browse UI (WRITTEN, NOT YET COMPILED)
UX decided with the user: **source-list-first** (Browse tab lists installed sources → tap → per-source
browse), and **cover tap → `features/manga`** (which already owns detail + add/remove library).

- **Coil 3** singleton `ImageLoader` over Ktor 3, set in `App.kt` via `setSingletonImageLoaderFactory`.
  Fills `MangaCover`'s image slot with `AsyncImage`. TODO left: rebuild it on the shared,
  interceptor-carrying client when Cloudflare lands. (MangaDex covers aren't CF-protected, so a separate
  client is fine now.) Libs: `coil = "3.0.4"`, `coil-compose`, `coil-network-ktor3`.
- **`features/browse` module:**
  - `BrowseCatalogViewModel` / `BrowseCatalogState` — lists sources via `listSources()`; client-side
    name filter; tap → `NavTo(SourceBrowse(sourceId))`.
  - `SourceBrowseViewModel` / `SourceBrowseState` — assisted-injected with `sourceId` (mirrors
    `MangaViewModel`). First page driven reactively by `selectedListing × debounced query` +
    `collectLatest`; **load-more** is an explicit `onLoadMore` guarded against double-fetch; `Append`
    enum (Idle/Loading/End/Error) drives the grid footer. `onRetry` restarts the whole init pipeline.
  - Screens/components: `BrowseCatalogScreen` (`TanukiTopBar` + `SourceRow`/`SourceAvatar`),
    `SourceBrowseScreen` (`TanukiDetailTopBar` + `TanukiSearchBar` + `TanukiSegmentedControl` for
    listings + `BrowseGrid`), `BrowseGrid` (adaptive cover grid, `snapshotFlow`-driven infinite scroll,
    `AsyncImage` in `MangaCover`). Strings in en + pt-BR.
- **Nav:** added `Destination.SourceBrowse(sourceId: String)` (leaf, String not Uuid); replaced the
  Browser `TabPlaceholder` with `BrowseCatalogRoute`; wired the `SourceBrowse` route.
- **Tap→detail seam:** `CacheSourceManga` use case inflates a `MangaListing` into a **cache-only**
  `Manga` (minted Uuid, `dateAdded = null`), deduped on the **existing unique `(source, url)` index**
  (added `findMangaIdBySourceAndUrl` to `MangaDao`/`MangaRepository`). Idempotent: an already-cached
  series returns its existing row **without** rewriting (would clobber a library membership). Covered by
  the runnable `CacheSourceMangaTest`.

---

## Key conventions / gotchas for the next session

- **Two build targets, always:** `:core:source:testAndroidHostTest` + `:core:source:iosSimulatorArm64Test`.
  Chicory is lenient, wasmi is strict — Android-only green hides real iOS breakage.
- **`Wire.kt` ↔ `wire.rs` parity** is unenforced. Change together; the cross-engine tests are the guard.
- **Never panic in a guest** — a panic aborts the module and the host sees an opaque trap. Return
  `AbiError` (surfaces as `SourceAbiException`).
- **Config-cache safety in Gradle:** task types declared in a `.gradle.kts` and execution-time closures
  (`onlyIf`, `rename`, `doLast`) must not capture script-level `val`s (`logger`, custom vals) — capture
  plain locals (`File`, `String`) declared inside the task's config block. Use built-in `Exec`/`Copy`.
- **`GetLibraryTest` fake** (`FakeMangaRepository`) must be updated whenever `MangaRepository` grows a
  method — it bit us twice.
- **Room:** hand-written migrations only; bump version + add a `MigrationNtoM` in `Migrations.kt`.

---

## Open / unverified (do these next)

1. **Compile the Browse slice.** `./gradlew :androidApp:assembleDebug`. Unverified in the session that
   built it. Most likely nits: Coil 3.0.4 coordinates vs Compose 1.11 / Kotlin 2.4; minor Compose API
   mismatches. Then run the domain + source tests.
2. **Manual check:** launch → Browse → MangaDex → Popular should stream live covers; scroll paginates;
   tapping a cover opens the manga detail.
3. **No Browse ViewModel unit test yet** (needs Main-dispatcher + virtual-time setup; skipped rather than
   ship broken). `CacheSourceMangaTest` covers the tap→cache logic.

## Roadmap beyond this session (from the agreed plan)
- Finish MangaDex: `manga_details`, `chapter_list`, `chapter_pages` (extend the guest; the detail screen
  can then show synopsis + chapters and read).
- `AddToLibrary`-from-detail already exists in `features/manga`; the cache seam feeds it.
- **Cloudflare interceptor** (Android `WebView`, iOS headless `WKWebView`) on the shared client — the big
  differentiator; independent of everything above; not needed for MangaDex.
- **Remote source repo** + installer + update check (this is where `Source.version` finally pays off).
- **MangaFire** as source #2 — HTML scraping in-guest + image descrambling + the first real Cloudflare
  target. Deliberately last.
