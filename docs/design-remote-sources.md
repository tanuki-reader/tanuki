# Design — Remote source distribution

Status: **Phase 1 done** (2026-10-12); Phase 2 (the app installs from a repo) is next. Replaces the
"sources ship inside the app" arrangement with user-added, signed, static source repos hosted on GitHub. Decisions are also logged in `decisions.md`
(2026-09-27).

**Progress**
- 2026-10-12 — §1 re-checked against the repo (iOS is native SwiftUI, DB at v16, bundles are Java
  resources / app-bundle files, `nsfw` mapped). Phase 0 landed: `SourceAbi.MIN_SUPPORTED` +
  `isSupported` (loader accepts the range), golden fixtures in `native/source-api/fixtures/v4/` checked
  by `cargo test` (`tests/fixtures.rs`) and `WireFixtureTest` (Android + iOS, via the generated
  `WIRE_FIXTURES`). The conformance source is `hello` + `HelloSourceEngineTest`, which already covers
  envelopes, host calls and the in-band error path on both engines.
- 2026-10-12 — Phase 1 landed: `tanuki-reader/tanuki-sources` (history split from `native/`), mounted
  in the app as the submodule `native/tanuki-sources`. `xtask build` builds `bundles.toml`, gates each
  bundle (exports, `abi_version`, `source_meta.id`/`version`), refuses changed bytes under a published
  version, and writes and signs the index; `publish.yml` pushes it to the `repo` branch. Deviations from
  the original design are marked in §3: no hosted icons (`iconUrl` from `source_meta`), `hello` and
  `manhastro` not published, toolchain pinned and lockfiles committed (byte-reproducible builds, which
  the version gate depends on), and a submodule instead of the SDK release tarball.

---

## 1. Current state

| Step | Where | What happens |
|---|---|---|
| Source code | `native/sources/<crate>/` (+ SDK in `native/source-api/`) | Rust `cdylib`s, one crate may yield several bundles via cargo features (`mangadex` → `mangadex`, `mangadex-pt-br`) |
| Build | `shared/build.gradle.kts` (`devSourceBundles`, `cargoBuild<X>`, `buildDevSources`, `syncDevSourcesForXcode`) | cargo builds each bundle, copies `<bundleId>.wasm` into `shared/src/androidMain/resources/sources/` (gitignored, Java resources). `syncDevSourcesForXcode` copies them into the iOS app bundle's `sources/` before `embedAndSignAppleFrameworkForXcode` |
| Install | `shared/.../dev/DevSourceInstaller.kt` + `expect readBundledSource` (`BundledSources.android.kt`: classloader; `BundledSources.ios.kt`: `NSBundle`) | on every launch, **always rewrites** each id in the hardcoded `BUNDLED_SOURCES` (5 ids; `manhastro` commented out because it needs auth) → `SourceBundleStore.writeBundle`, skipping ids in the `removed_source_ids` pref. It is also the **only** `SourceCatalog` binding (`availableIds()` / `install(id)`) |
| Store | `core/source/.../store/FileSystemSourceBundleStore.kt` | one `<id>.wasm` per source under `filesDir/sources` (Android) / `Application Support/sources` (iOS); atomic writes |
| Load | `core/source/.../WasmSourceLoader.kt` | instantiates, requires `abi_version()` in `SourceAbi.MIN_SUPPORTED..VERSION` (both 4 today), reads `source_meta`, persists a `sources` row on first load |
| Persist | `core/data/.../SourceEntity.kt` (`sources`), `MangaEntity.source` FK with `CASCADE` | the row outlives an uninstall on purpose, so reinstalling reattaches the library via `getSourceByNativeId` |
| Catalog | `core/domain/.../source/usecase/ObserveSourceCatalog.kt` → `CatalogSource` | merges installed metadata with persisted rows; an uninstalled source shows its last-known name/icon, and `nsfw` reads `false` until installed |
| UI | Android: `features/settings/.../sources/SourcesScreen.kt`; iOS: `iosApp/Features/Settings/SourcesScreen.swift`; both over `SourcesViewModel` (`:features:settings:logic`) | installed / available sections, install & uninstall |

**What only works because app and sources are built together:**

1. ~~**ABI exact-match.**~~ Now a range (Phase 0). A source outside it is still silently skipped
   (`observeInstalledSources` swallows the load failure); the index's `abi` lets the catalog explain
   why before downloading (Phase 2).
2. **No integrity check.** Bytes come from the app bundle, so nobody verifies them.
3. **Metadata requires the binary.** Name/lang/version/icon come only from the `source_meta` export —
   there is no way to list a source that isn't on disk.
4. **The contract has two unchecked copies.** `Wire.kt` / `SourceAbi.kt` (host) and
   `native/source-api/src/{abi,wire}.rs` (guest) are kept in sync by hand.
5. ~~**`nsfw` is dropped.**~~ Done: `Mappers.kt` maps it into `SourceMeta.nsfw` and `CatalogSource.nsfw`.
6. **Versions:** every crate's `Cargo.toml` is `0.1.0`; the update-driving version is
   `source_meta().version` (e.g. MangaDex = 9). CI rules key on that, not on cargo.

---

## 2. Phase 0 — prerequisites (land before the first remote publish)

### 2.1 Enforcing the ABI contract

The contract is not one artifact — it is implemented **twice**, in Kotlin (host) and Rust (guest), and
the two must agree on: the ABI version, the export/import names and signatures, and every wire JSON
shape. A shared *repository* does not by itself make two languages agree; what enforces the contract is
**one canonical definition that both sides test against**.

**Decision: the SDK is the source of truth, and it ships a conformance kit.**

- `source-api` owns the contract: `ABI_VERSION`, the export/import table, and a directory of
  **golden wire fixtures** — one JSON file per wire type and edge case
  (`fixtures/v4/source_meta.full.json`, `…/manga_page.empty.json`, `…/envelope.error.json`, …).
  **Until Phase 1 moves the SDK, they live at `native/source-api/fixtures/v4/`** and the host test reads
  them straight from the repo path (embedded at build time, like the `.wasm` fixtures), not from a
  release tarball.
- **Guest side** — the Rust crate's own tests round-trip every fixture through `wire.rs`. Sources
  depend on the crate by tag, so a source can only compile against a released contract.
- **Host side** — the app pins the SDK version in `gradle/libs.versions.toml`
  (`tanukiSourceApi = "4.1.0"`). A Gradle task fetches the fixtures for that tag (release tarball) into
  `core/source/build/`, and a `commonTest` round-trips each through `Wire.kt`. Drift fails the app build,
  not a user's phone.
- **Behavioural conformance** — the existing fixture crates (`core/source/build.gradle.kts` embeds every
  `native/sources/*` crate as base64 `<CRATE>_WASM`; `hello` is already the minimal contract source)
  become a single `conformance` source in the SDK repo, built from the pinned
  tag and exercised by both engine tests (Chicory, wasmi). It checks alloc/dealloc, envelopes, host
  `http_request`/`log`, and error paths.
- **Publish-time gate** — sources-repo CI instantiates every built bundle in a host harness and
  validates exports/imports and `abi_version` before it can enter the index.

**Compatibility range, not equality.** Add `SourceAbi.MIN_SUPPORTED` beside `SourceAbi.VERSION`;
the loader accepts `MIN_SUPPORTED..VERSION`. Rules:
- Additive, backward-compatible change (new optional wire field, new optional export) → **minor** SDK
  bump, ABI version unchanged. Wire decoding already tolerates unknown/absent optional fields.
- Breaking change → ABI `VERSION` bump; the host keeps a shim for the previous version until
  `MIN_SUPPORTED` is raised in a later app release.
- The index carries each bundle's `abi`, so the catalog can say "needs app update" (abi > VERSION) or
  "source outdated" (abi < MIN_SUPPORTED) **before** downloading.

**Where `source-api` lives.** Start it inside the sources repo (`tanuki-sources/source-api`, tagged
`source-api-v4.1.0`): today every source is first-party, the SDK and sources change together, and one
repo means one PR for a contract change plus the sources that adopt it. Move it to its own repo
(`tanuki-source-api`) when **third-party authors** exist and the SDK needs a release cadence independent
of first-party sources — consumers only change a git URL, so it is a rename, not a redesign.
Code generation from a language-neutral schema (JSON Schema → Kotlin + Rust) is the next step if the
fixture approach stops scaling; not worth it at ~18 wire types.

### 2.2 Integrity and authenticity

- Every index entry carries the bundle's `sha256` and `size`; the app verifies both before writing to
  the store (okio `ByteString.sha256()`).
- The index is signed with **ed25519**. The private key lives in a GitHub Actions secret;
  `repo.json` publishes the public key. The app **pins** the key when the user adds the repo (shows the
  fingerprint for confirmation) and rejects an index whose signature fails or whose key changed.
- Open item: the KMP ed25519 verifier — `cryptography-kotlin` (check the iOS provider supports EdDSA)
  vs. a small vendored pure-Kotlin verifier. Verification only, never signing, on device.
- WASM stays sandboxed: the only host capabilities remain `http_request` and `log`.

### 2.3 Metadata without the binary

CI builds each bundle, instantiates it, calls `source_meta`, and writes the index from that output —
the index can't drift from the binary. On install the app still re-checks that the downloaded module's
`source_meta` id and version match the index entry.

### 2.4 Small model fixes

- ~~Map `nsfw` into the domain `SourceMeta` / catalog entry.~~ Done.
- `source_meta.id` must equal the bundle id (already the convention; the CI gate enforces it).

---

## 3. The sources repo (`tanuki-sources` on GitHub)

```
tanuki-sources/
  source-api/            # SDK crate + fixtures/v<ABI>/ (hello is the conformance source)
  sources/<crate>/       # mangadex, mangafire, hipercool, manhastro, hello
  bundles.toml           # the PUBLISHED bundles: id, crate, cargo feature (not hello/manhastro)
  xtask/                 # build → validate → extract meta → hash → write + sign index; keygen
  rust-toolchain.toml    # pinned rustc: same source + same toolchain = same bytes
  .github/workflows/publish.yml
```

The app mounts it as the submodule `native/tanuki-sources` (relative URL `../tanuki-sources`), pinned
to a commit. That pin plays the role of the "pinned SDK version" in §2.1; Gradle reads the crates and
the fixtures straight from it, and `settings.gradle.kts` fails fast if it isn't checked out.

**CI (`publish.yml`, on push to `main`):** build all bundles to `wasm32-unknown-unknown`, run the gate
(§2.1), fail if a bundle's bytes changed under an already-published `source_meta().version` (or the
version went backwards; compared against the live `repo` branch), then force-push the output as the
orphan **`repo`** branch. Pull requests run the same build unsigned and don't publish. The signing seed
is the `TANUKI_SIGNING_KEY` secret (`xtask keygen`).

**Published layout (the `repo` branch):**

```
repo.json                     # { formatVersion, name, publicKey }
index.json                    # signed payload
index.json.sig                # base64 detached ed25519 signature over the exact bytes of index.json
wasm/mangadex-v9.wasm         # immutable, versioned file names → safe CDN caching
```

**`index.json`:**

```json
{
  "formatVersion": 1,
  "generatedAt": "2026-09-27T12:00:00Z",
  "sources": [
    {
      "id": "mangadex",
      "name": "MangaDex",
      "lang": "en",
      "version": 6,
      "abi": 4,
      "nsfw": false,
      "baseUrl": "https://mangadex.org",
      "listings": ["popular", "latest"],
      "iconUrl": "https://mangadex.org/pwa/icons/icon-180.png",
      "wasmPath": "wasm/mangadex-v6.wasm",
      "sha256": "…",
      "size": 412345
    }
  ]
}
```

`iconUrl` and `baseUrl` are omitted when the source reports none. `formatVersion` versions the index schema itself. The app ignores an index with a higher
`formatVersion` than it understands and tells the user to update the app.

**Serving:** GitHub Pages on the `repo` branch is the primary host, with jsDelivr
(`https://tanuki-reader.github.io/tanuki-sources/`, mirror `cdn.jsdelivr.net/gh/tanuki-reader/tanuki-sources@repo/`) as a mirror. The app's only requirement is "a base
URL that serves `repo.json`", so moving to R2/S3/any static host later changes nothing in the app.

**Posture:** the app ships **no** repo URL. Users add repos themselves (Mihon model), so the app never
bundles or points at scraper sources. This limits takedown and App Store (guideline 2.5.2) exposure,
and it supports several repos at once.

---

## 4. App design

### 4.1 Data (`:core:data`, Room migration v16 → v17)

- **`source_repos`**: `id`, `base_url` (unique), `name`, `public_key`, `last_fetched_at`, `index_json`
  (the last verified index, so the catalog works offline).
- **`source_installations`**: `source_id` (PK, native string id), `repo_id` (nullable FK →
  `source_repos`, `SET NULL`), `version`, `abi`, `sha256`, `installed_at`. This is a separate table from
  `sources` because a `sources` row is meant to survive an uninstall (CASCADE to manga). An installation
  row is the record that a bundle is on disk and where it came from.
- The `removed_source_ids` pref goes away: with no auto-seeding, "not installed" is simply the absence
  of an installation.

### 4.2 Domain (`:core:domain/source`)

- `SourceRepoRepository` (add / remove / observe / store a verified index).
- Widen `SourceCatalog` from `availableIds()` to
  `observeAvailable(): Flow<List<CatalogEntry>>`. A `CatalogEntry` holds the id, name, lang, version,
  nsfw, iconUrl, repo, and `compatibility: Compatible | NeedsAppUpdate | Outdated`. `install(id, repoId)`
  stays.
- New use cases: `AddSourceRepo` (fetch `repo.json` → verify → pin), `RemoveSourceRepo`,
  `RefreshSourceRepos`, `CheckSourceUpdates`, `UpdateSource`.
- `InstallSource`, `UninstallSource` and `ObserveSourceCatalog` keep their current semantics: uninstall
  is reversible, and the same `Uuid` reattaches the library.

### 4.3 Implementation (`:core:source`)

`RemoteSourceCatalog` replaces `DevSourceInstaller` as the `SourceCatalog` binding. The install flow:

```
refresh:  GET repo.json → GET index.json + .sig → verify against pinned key → cache index
install:  pick entry (compatible only) → GET wasmPath → check size + sha256
          → probe-instantiate: abi in range, source_meta.id/version == entry
          → bundleStore.writeBundle (atomic) → upsert source_installations
```

- It uses the existing Ktor client and is gated by `ConnectionObserver`, the same as `fetch*`. Repos
  are plain static hosts, so the Cloudflare interceptor is **not** involved (the two stay decoupled).
- **Ids across repos:** ids are global. If two repos offer the same id, the catalog shows both, only one
  can be installed, and the installation records which repo it came from. Updates only come from that
  repo.

### 4.4 Updates

`CheckSourceUpdates` compares each installation's version with its repo's cached index. It runs on
launch (throttled, e.g. once every 12 h) and inside the existing library-update job (WorkManager /
BGTaskScheduler). The Sources screen shows an update badge and an "update all" action. Auto-update is
a later setting.

### 4.5 Migration and the dev loop

- Bundles already on disk from `DevSourceInstaller` become installations with `repo_id = null`
  ("local"). They keep loading. When the user adds a repo offering the same id, the installation is
  adopted (`repo_id` set) and updates flow from there.
- Release builds drop `DevSourceInstaller`, the composeResources bundling, and the cargo tasks in
  `shared/build.gradle.kts`.
- **Dev loop:** `xtask serve` builds the repo locally and serves it over HTTP. A debug-only setting lets
  the app add a repo at `http://10.0.2.2:<port>` (Android emulator) or `http://localhost:<port>` (iOS
  simulator) and skip key pinning. Edit a source, rebuild, tap refresh: no app reinstall.

### 4.6 UI (`:features:settings/sources`)

- **Repos screen:** add a repo by URL (validate, show name + key fingerprint, confirm), list repos with
  their last-refresh time, remove a repo.
- **Sources screen:** Installed / Updates / Available sections, a language filter, an NSFW toggle (off
  by default), the version, and "needs app update" / "outdated" states. An empty state when no repo is
  added explains how to add one.
- Both platforms: Compose in `:features:settings`, SwiftUI in `iosApp/Features/Settings/` (a Repos
  screen beside `SourcesScreen.swift`), over the same ViewModels in `:features:settings:logic`.
- Built from the Tanuki Design System artifact. Every new string gets en + pt-BR entries in the
  `strings.xml` (iOS picks them up through `generateIosStrings`).

---

## 5. Phased rollout

| Phase | Scope | Tests |
|---|---|---|
| **0** Prereqs ✅ | `MIN_SUPPORTED` range, `nsfw` mapping, in-repo fixtures, contract tests on both sides; conformance source = `hello` until Phase 1 | ABI range tests in `:core:source`; wire fixture round-trips (Kotlin + Rust) |
| **1** Sources repo ✅ | move `native/source-api` + `native/sources` to `tanuki-sources`, `bundles.toml`, `xtask`, CI publish to `repo` branch | xtask golden index test; CI gate rejects bad exports / missing version bump |
| **2** App install from repo | `source_repos` / `source_installations` + migration, `RemoteSourceCatalog`, ed25519 + sha256 verify, Repos screen | signature/key-change/sha256/size rejection tests; migration test; use-case tests (kotest) |
| **3** Updates | `CheckSourceUpdates` / `UpdateSource`, background check, badges | update detection + repo-scoped update tests |
| **4** Cleanup | remove `DevSourceInstaller`, composeResources bundles, cargo tasks, `removed_source_ids` | Android + iOS builds green, fresh-install flow manually |

---

## 6. Risks and open questions

- **ed25519 on KMP/iOS**: choose between a library and a vendored verifier (§2.2).
- **Key rotation**: a new key signed by the old one, published in `repo.json`. Design this before the
  key ever needs to change.
- **App Store 2.5.2**: downloading executable code is still a review risk even with user-added repos.
  WASM + no default repo is the strongest position available, but it is not a guarantee.
- **GitHub takedown** of a repo with scraper sources: mitigated by multi-repo support and the jsDelivr
  mirror; any static host works.
- **Index size**: one JSON file is fine for tens of sources; shard it by language only if it grows into
  the hundreds.
