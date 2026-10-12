# Remote sources: as built

How Tanuki gets its sources today, as of Phases 0–3 (2026-10-12; PRs #1 and #2). The plan and its
reasoning are in [`design-remote-sources.md`](design-remote-sources.md); the format a repo serves is
specified in `native/tanuki-sources/docs/repo-format.md`.

## In one paragraph

The app ships **no sources and no repo URL**. The user adds a source repo by address: a static
host serving a signed index of `.wasm` bundles. Adding it pins the repo's ed25519 key after the user
confirms its fingerprint. From then on, indexes are accepted only if they verify against that key,
bundles only if their size and sha256 match the verified index, and updates only from the repo each
source was installed from. The first-party repo, `tanuki-reader/tanuki-sources`, is built and signed
by CI and published to GitHub Pages, and the app ships its key.

## The pieces

| Where | What |
|---|---|
| [`tanuki-reader/tanuki-sources`](https://github.com/tanuki-reader/tanuki-sources), mounted at `native/tanuki-sources` | The source SDK (`source-api`), first-party sources, `bundles.toml` (what gets published), `xtask` (build → validate → index → sign), and `publish.yml` |
| `native/tanuki-sources/source-api/fixtures/v4/` | Golden wire JSON. Both `wire.rs` (`cargo test`) and `Wire.kt` (`WireFixtureTest`, Android and iOS) test against it |
| `core/source/.../repo/RemoteSourceCatalog.kt` | The app's `SourceCatalog`: add, preview, refresh, verify, install, plus the pinned first-party key |
| `core/source/.../WasmSourceLoader.kt` | Runs installed bundles. Accepts ABI `MIN_SUPPORTED..VERSION`, and retires a source's cached module when its bundle is replaced |
| `core/data/.../sourcerepo/` | Room: `source_repos` (v17: pinned key + last verified index) and `source_installations` (v18: origin repo per source) |
| `core/domain/.../source/` | `SourceCatalog`, `ObserveSourceCatalog` (installed + offered + updates), `InstallSource`, `CheckSourceUpdates`, and the repo use cases |
| `features/settings/logic/.../sources/` | `SourcesViewModel` (lists, language filter, install/update/Update all, removal) and `SourceReposViewModel` (add, confirm, remove, refresh) |
| `features/settings/.../SourcesScreen.kt`, `iosApp/.../SourcesScreen.swift` | The Sources screen on both platforms, built to the design system's `SourcesScreen`, `AddSourceRepo` and `SourceRow` |
| `shared/.../update/` | The background job (WorkManager / BGTaskScheduler) runs `CheckSourceUpdates` before the library update; notifiers post "N source updates" |

## The trust chain

```
pinned key ──verifies──▶ index.json ──sha256 + size──▶ .wasm ──origin repo only──▶ updates
```

| Step | Guard | On failure |
|---|---|---|
| Add a repo | `https` only; `repo.json` must carry a key; the index must verify; the user confirms name, source count, host and fingerprint | Nothing is stored. The error names the reason (unsigned, bad signature, unreachable, too new) |
| First-party repo | Both of its addresses must present the key shipped in `RemoteSourceCatalog.PINNED_REPO_KEYS` | Refused as "key changed" before the user sees a fingerprint |
| Refresh (launch, background job, pull to refresh) | Verify against the **pinned** key, never the current `repo.json` | Keeps the last verified index; the repo row shows a red banner for *key changed* or *tampered* |
| Install / update | Size and sha256 must match the verified entry; only from the source's origin repo | Nothing is written; the row names the step ("the file didn't match the repo") and offers Retry |
| Load | ABI in `MIN_SUPPORTED..VERSION`; the index's `abi` lets the catalog say "Needs a newer Tanuki" / "Outdated" before any download | Not installable; listed with a warning and no action |

Verification uses cryptography-kotlin 0.6.0: the JDK provider on Android (Ed25519 is in the platform at
minSdk 36) and CryptoKit on iOS. sha256 uses okio.

## What the user sees

- **First run:** an empty state, "No sources yet", whose one action is **Add repo**. The app never
  implies it provides content.
- **Adding a repo:** paste the address, Continue, then confirm "Add Tanuki Sources?", showing
  "5 sources · host", the "Signed · ed25519 key fingerprint" in mono, and the trust warning.
- **Sources screen**, top to bottom:
  - language chips;
  - **Updates** ("English · v9 → v10", Update, "Update all");
  - **Installed** (overflow or swipe to remove);
  - **Available** (Install);
  - **Repos** (remove; a banner on a trust failure).

  A row's repo is named only when more than one repo is added. Progress reads "Downloading · 42%" then
  "Verifying".
- **Settings:** "Show adult content" sits above Sources, which shows an update-count badge.
- **Notification:** the background job notifies "N source updates" once per new version.

## Operating it

- **Changing a source:** commit in `tanuki-sources` with a higher `source_meta().version` and push.
  CI publishes, and the app offers it as an update after its next refresh. Then bump the submodule
  pointer here.
- **Signing key:** it lives in the `TANUKI_SIGNING_KEY` Actions secret, backed up in the maintainer's
  macOS Keychain ("tanuki-sources signing key"). The app ships the public key, so **rotation needs an
  app release first**.
- **A local repo for development:** `xtask build --out out` (unsigned without the key, which the app
  refuses; sign with a scratch key), then `python3 -m http.server -d out`. See the sources README.

## Known limits and next steps

- **Auto-update** isn't built; the user taps Update or Update all.
- **Key rotation** through a new key signed by the old one isn't designed; rotating today is the
  app-release route above.
- Repo checks run at launch and in the background job, with no extra throttle.
- Browsing a source is slow on the Android emulator (interpreted WASM parsing large JSON, around 40 s
  for MangaDex). Not investigated.
- The iOS screens build but haven't been exercised on a device or simulator in this work.
