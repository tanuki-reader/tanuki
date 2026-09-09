# Tanuki — Claude Code Context

Tanuki is a free and open source manga/comic reader for **Android and iOS**, built as a
Kotlin Multiplatform (KMP) reimplementation of [mihonapp/mihon](https://github.com/mihonapp/mihon).

This repo is a **fresh start**. It begins from the JetBrains KMP wizard scaffold and grows
toward the Mihon feature set incrementally. A previous, more fully-scaffolded attempt lives at
`~/Documents/personal_projects/tanuki` and can be mined for reference, but this is the canonical repo.

---

## Project Goals

- Bring the full Mihon experience (library, sources, trackers, reader) to iOS
- Share as much logic as possible via KMP — domain/data/network/source + ViewModels/UI
- Solve the Cloudflare bypass problem that plagues existing iOS manga readers (e.g. Suwatte)
- Design for a WASM-based extension migration (App Store / Play Store compatible path)

---

## Build & Run

```bash
# Android debug build
./gradlew :androidApp:assembleDebug

# iOS: open ./iosApp in Xcode and run from there (no gradle run task).
# iosApp is a thin host: it launches the shared Compose UI (a ComposeUIViewController from sharedUI).
```

## Test

```bash
# Common + Android host tests
./gradlew :sharedUI:testAndroidHostTest :sharedLogic:testAndroidHostTest

# iOS tests (simulator)
./gradlew :sharedLogic:iosSimulatorArm64Test
```

When adding tests, the `kotlin-test-generator` skill follows this project's conventions
(kotest + coroutine patterns). Source sets: `commonTest` for shared, `androidHostTest` /
`iosTest` for platform-specific.

---

## Module Structure (build toward this)

**Decision: hybrid — layered foundation, feature-sliced top. Packages first, Gradle modules later.**

Not pure layer-based, not pure feature-based:
- **Foundation is layered** (`core`, `domain`, `data`, `network`, `source`) — horizontal capabilities
  shared by everything. Mirrors Mihon, so porting its non-UI code is near-mechanical.
- **The top is feature-sliced** (`library`, `browse`, `reader`, `tracking`, `updates`, `settings`) —
  vertical, mostly independent slices. Build/change/delete one without touching the others.

Start as **packages inside the shared module(s)**. Promote a package to its own Gradle module only when
something concrete forces it (see "When to split" below) — JetBrains' new default deliberately favors one
shared module with a clear responsibility, so don't over-modularize.

> **Current state (2026-09):** the actual Gradle modules are:
>
> | Module | What's in it | Compose? |
> |---|---|---|
> | `:androidApp` / `:iosApp` | thin platform hosts | — |
> | `:shared` | app shell: the one `Scaffold`/`NavHost`, Metro `AppGraph`, platform bindings | yes |
> | `:core:domain` | models, repository interfaces, use cases, `DispatcherProvider`/`Logger` | no |
> | `:core:data` | Room entities/DAOs + repository implementations | no |
> | `:core:navigation` | `Destination`, `NavEvent`, `NavCommand`, `AppNavigator` | no |
> | **`:core:presentation`** | **`TanukiViewModel`** + **`StateViewModel<S>`** — the base classes every screen's ViewModel extends | no |
> | `:core:source` | WASM source runtime + Cloudflare/session networking | no |
> | `:features:<x>` | one per slice (`library`, `manga`, `reader`, `activity`, `home`, `source`, `settings`) — each holds that slice's Compose screens **and** its ViewModels | yes |
> | `:assets:designSystem` | the Compose design system | yes |
>
> There is **no `:sharedLogic` / `:sharedUI` split** — those names in this doc and below are the **target**
> vocabulary. They now map onto the `:core:*` modules (Compose-free foundation, the `sharedLogic` role) and
> the `:features:*` + `:shared` + `:assets:designSystem` modules (Compose, the `sharedUI` role). Because a
> feature module holds its screens *and* its ViewModels together, the "`sharedLogic` never imports Compose"
> rule is inside `:features:<x>` still a *design intent* enforced by package discipline (ViewModels live in
> `…/<feature>/viewmodel/` and must not import Compose), not a module boundary. It **is** a real,
> compiler-enforced boundary in `:core:*`, none of which may depend on Compose.

```
sharedLogic/   (KMP — ALL platforms, NEVER imports Compose)
  app.tanuki/
    core/        cross-cutting: Result types, dispatchers, logging, preferences
    domain/      models (Manga, Chapter, Source…) + repository INTERFACES + use cases
    data/        Room (KMP) entities/DAOs + repositories (implement domain interfaces)
    network/     Ktor client + Cloudflare interceptor (WebView via expect/actual)
    source/      WASM source-api + WasmSourceLoader (engine via expect/actual)
    feature/     per-feature ViewModels — Compose-free, Swift-consumable (see MVVM note)
      library/  browse/  reader/  tracking/  updates/  settings/
  androidMain/   Android WASM engine binding; Android WebView interceptor
  iosMain/       iOS WASM engine binding; WKWebView interceptor

sharedUI/      (Compose Multiplatform — ALL UI platforms incl. iOS; depends on sharedLogic)
  app.tanuki/
    designsystem/  ONE Compose design system (theme, colors, TanukiTextStyle, components)
    feature/       per-feature Compose screens; bind to the ViewModels in sharedLogic
      library/  browse/  reader/  tracking/  updates/  settings/
```

### Presentation pattern: MVVM (not MVI)

Each feature has a **ViewModel** that lives in `sharedLogic` (Compose-free) and exposes immutable UI
state as a `StateFlow<XxxUiState>`, with plain methods for user actions (`onSearch()`, `onRefresh()`).

- **Why in `sharedLogic` and not `sharedUI`:** keeping ViewModels Compose-free preserves the boundary
  rule (`sharedLogic` never imports Compose) and lets presentation logic be unit-tested without a Compose
  runtime. The Compose UI in `sharedUI` collects the `StateFlow` and calls the plain action methods —
  the same screens render on **both** Android and iOS, so presentation logic is written once.
- Use the KMP `ViewModel` from `androidx.lifecycle.viewmodel` (already a dependency) so both platforms
  get lifecycle scoping for free.
- **Both platforms:** Compose screens collect state with `collectAsStateWithLifecycle()` and call
  ViewModel methods. No reducers/intents/MVI machinery, no SwiftUI, no SKIE bridging needed for UI.
- **Every ViewModel extends `TanukiViewModel`** (`:core:presentation`) rather than `ViewModel` directly.
  The base owns what every screen repeats — don't re-declare any of it:
  - `navEvents` + `navigateTo(destination)` / `navigateBack()` for screen-scoped navigation (a leaf
    pushed onto the current tab). Cross-tab jumps and deep links still go through `AppNavigator`.
  - `dispatcher` and `logger`, taken as constructor parameters the subclass forwards to `super`.
  - `tag` — defaults to the class's `simpleName`; used by the logging helpers.
  - `Flow<T>.asState(initial)` for a `StateFlow` derived straight from a domain flow, and
    `io { }` / `tryIo("what failed") { }` for suspend work at the ViewModel boundary. **`tryIo` is
    mostly not about the hop** — every layer below already dispatches for itself (Room via
    `setQueryCoroutineContext(io)`, DataStore, `FileSystemChapterDownloadStore`, `WasmSourceLoader`) —
    it is about logging what failed *and* re-throwing cancellation. Where the hop isn't wanted, use
    `runCatchingCancellable { }`; a bare `runCatching` around a suspend call swallows
    `CancellationException` and reports an abandoned load as a failure.
  - **Where the IO hop lives, once:** the layer that knows whether the work blocks owns it. A repository
    wraps in `withContext(io)` only for work *it* does — mapping a row list to domain, batching,
    encoding — never for a plain DAO delegation. (`:core:data` has redundant wrappers predating this
    rule; they're harmless, since `withContext` to the context you're already in doesn't dispatch.)
- **A screen whose state is *derived* extends `StateViewModel<S>`** (`:core:presentation`), the second
  tier on top of `TanukiViewModel`. Give it `initialState` through `super` and implement
  `override val state: Flow<S>`; the base owns `uiState`, sharing it through `asState` so the pipeline
  runs only while the screen is watching. **Don't add a `flowOn`** — `asState` already maps on
  `dispatcher.default`. Everything the user sets on the screen (a sheet being open, a selected period, a
  refresh in flight) is an **input** `MutableStateFlow` folded into the pipeline, never something written
  over its output: `uiState` has no setter, so `uiState.value = …` will not compile.

  Two screens extend `TanukiViewModel` directly and declare their own state with Kotlin's explicit
  backing field (`val uiState: StateFlow<S>` / `field = MutableStateFlow(Loading)`), for two different
  reasons: **source browse** has no derived state at all (a cursor-driven paging machine), while **the
  reader's state is derived** — what doesn't fit it is `asState` itself, whose `flowOn(default)` would
  race the reader's re-anchor bookkeeping, whose `stateIn` publishes too late for scroll commands
  computed against the list just emitted, and whose `WhileSubscribed` would reseed a stateful `scan` for
  a route-scoped screen that never leaves the foreground. So the marker means *either* "not derived"
  *or* "`asState`'s contract doesn't fit" — reach for it only on one of those two grounds, and don't add
  a `SharingStarted` knob to `StateViewModel` to dodge the third (see its KDoc for why).
  Don't put a type parameter on `TanukiViewModel` itself — it serves both kinds, plus the settings
  ViewModels that expose a single domain value under its own name rather than a screen state.
  Screen-specific one-shot effects stay local too (`ReaderViewModel.commands`).
- **Metro gotcha — a ViewModel's `@ContributesIntoMap` must name its bound type explicitly:**
  `@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())`. Metro otherwise infers the
  bound type from the supertype, which is now `StateViewModel`/`TanukiViewModel`, and the ViewModel lands in a
  multibinding nothing consumes — `metroViewModel<X>()` then fails to resolve **at runtime**, while the
  build only emits a `SuspiciousUnusedMultibinding` warning. Assisted ViewModels are unaffected (their
  `Factory` supertype is `ManualViewModelAssistedFactory`).

### Navigation: shared contract, native rendering

Single-activity on Android, single root view controller on iOS (both hosting the shared Compose UI).
The **set of destinations is defined once in `sharedLogic`** as a serializable sealed type; a single
Navigation Compose graph in `sharedUI` renders it on both platforms.

```kotlin
// sharedLogic — the ONE definition of what screens exist
@Serializable
sealed interface Destination {
    @Serializable data object Library : Destination
    @Serializable data class MangaDetail(val mangaId: Long) : Destination
    @Serializable data class Reader(val chapterId: Long) : Destination
    @Serializable data object Settings : Destination
}
// ViewModels express navigation as intent, not mechanics:
sealed interface NavEvent { data class Go(val to: Destination) : NavEvent; data object Back : NavEvent }
```

- **Both platforms:** **Jetpack Navigation Compose** (multiplatform artifact
  `org.jetbrains.androidx.navigation:navigation-compose`); a single `NavHost` in `sharedUI` switches
  over `Destination`. Written once, runs on Android and iOS.
- Navigation *intent* lives in shared ViewModels (`NavEvent`); the `NavHost` in `sharedUI` performs the
  push/pop. One UI codebase, so routes/params cannot drift across platforms by construction.
- **Decided against:** Decompose (would own the back stack in Kotlin and duplicate what Navigation
  Compose already gives us across platforms).

### Boundary rules (keep these true)

1. Dependencies point **down only**: `feature → domain/core`. Never `core → feature`.
2. **No feature → feature** dependencies. Shared behavior goes through a `domain` use case both call.
3. `domain` owns **interfaces**, `data` owns **implementations**; features depend on interfaces, wired
   by **Metro** (see `di/AppGraph.kt`, `AppBindings.kt`). This is what keeps the WASM loader and `data`
   layer swappable.
4. `sharedLogic` **never imports Compose** — discipline (or a Konsist/lint rule), since packages can't
   enforce it the way module boundaries can.

### When to split a package into its own Gradle module

Only when one bites: (a) incremental builds get slow → modules buy parallelism/caching; (b) you want a
compiler-enforced boundary (good first candidate: `:core:domain`); (c) a feature becomes independently
togglable. Natural first split: `:core`, `:domain`, `:data`, `:source`, then `:feature:<x>:logic` /
`:feature:<x>:ui` pairs — same names as the packages above, so it's a rename, not a redesign.

### Technology stack (target)

| Concern | Technology |
|---|---|
| Database | Room KMP  |
| Networking | Ktor (replaces OkHttp in shared code) |
| App UI (Android + iOS) | Compose Multiplatform (in `sharedUI`) — one UI, both platforms |
| iOS WebView (Cloudflare) | `WKWebView` headless via `expect`/`actual` — not part of the Compose UI tree |
| Extensions (both platforms) | WASM source runtime — single shared mechanism, no JSCore, no APK loader |
| Presentation | MVVM — `ViewModel` + `StateFlow<UiState>` in `sharedLogic` (Compose-free), no MVI |
| DI | Metro (KMP-compatible) |
| Async | Kotlin Coroutines + Flow |

---

## Design System — single source of truth for all UI

**All UI, designs, and visual artifacts MUST be based on the Tanuki Design System, which lives as a
**remote claude.ai/design project** (the single source of truth — there is no local copy in this repo):
`https://claude.ai/design/p/425e7085-96ba-4446-8f59-63793c9cbf48` (project id
`425e7085-96ba-4446-8f59-63793c9cbf48`). This is the authoritative reference for the brand, colors,
typography, spacing, iconography, copy/voice, and the dual-platform component language. Do not invent
ad-hoc colors, type scales, spacing, or components — pull them from the remote project.

- **Access it via the `claude_design` MCP connector** (the `DesignSync` tool). If the connector needs
  authorization, run `/design-login` (grants `user:design:read`/`write`). Read the project with
  `DesignSync` methods: `list_files` to see paths, `get_file` to read a specific file's content.
  Before building or mocking any screen, component, marketing/visual artifact, or production UI, pull
  the relevant design files from the project first.
- **Start here (paths inside the project):** `readme.md` (brand + visual foundations), `styles.css` →
  `tokens/` (colors, typography, spacing, fonts — the actual values to use), `components/` (React
  primitives, each with a `.prompt.md`), `ui_kits/android/` + `ui_kits/ios/` (full screen recreations
  to copy and adapt). `SKILL.md` is the in-project agent guide.
- **One Compose design system, both platforms:** the app renders the **same Compose UI** on Android and
  iOS, so there is a single Material You–based Tanuki design system in `sharedUI/designsystem`. The design
  project's `ui_kits/ios/` (Liquid Glass) is **reference only** — true OS Liquid Glass needs native
  SwiftUI, which we've deliberately dropped for maximum code sharing. Where the design calls for a glassy
  surface, approximate it in Compose (translucency/blur), but do not fork the component set per platform;
  the `platform="android" | "ios"` prop collapses to a single Compose implementation.
- Dark-first, near-monochrome, with a single warm tanuki orange (`#E8731C`) accent used sparingly. Use
  **Material Symbols Rounded** across both platforms (SF Symbols aren't available to Compose); the only
  proprietary glyph is the tanuki brand mark in the project's `assets/`.
- The design doc at `_source/tanuki_design_doc.pdf` (inside the project) is the primary visual reference
  the system is built from. When the design system and this file disagree on a visual detail, the design
  system wins. To keep a local component library in sync with the project, use the `/design-sync` skill.

---

## Localization (en + pt-BR)

Every user-facing string MUST be localizable — the app ships in **English** and **Brazilian
Portuguese (pt-BR)** for now, more later. No hardcoded display copy.

- **One mechanism, both platforms — Compose Multiplatform Resources.** Because the UI is a single
  Compose codebase, strings live once in `sharedUI/src/commonMain/composeResources/`:
  `values/strings.xml` (en) and `values-pt-rBR/strings.xml` (pt-BR). No `Localizable.xcstrings`, no
  SwiftUI `LocalizedStringKey` — those are gone with native iOS UI.
- In composables, reference strings by generated id: `Text(stringResource(Res.string.filter_by_category))`
  — never an inline display literal. Dynamic values go through format args
  (`stringResource(Res.string.show_n_titles, n)`), not string concatenation; counts use
  `pluralStringResource(...)`.
- **Strings needed in `sharedLogic` (Compose-free ViewModels):** do not resolve text there. Either expose
  the `StringResource` reference in the UI state and resolve it in the composable, or use the non-composable
  suspend `getString(Res.string.x)` accessor. Keep raw display `String`s out of ViewModels.
- Pure content/data (manga title, genre, author) stays a plain `String` — it comes from sources, isn't
  localizable, and never goes through `stringResource`.
- A new screen is not done until both languages have every key. Don't add a string in one language only.
- Status: **not wired up yet** — `composeResources/` string catalogs don't exist (the current `sharedUI`
  is still the scaffold). The old `iosApp/iosApp/Localizable.xcstrings` approach is **superseded** by
  Compose Resources; set up `values/` + `values-pt-rBR/` when real Compose UI lands.

## Key Interface — Never Break This

The entire extension system must hide behind one interface. **Never leak WASM engine details (memory,
host functions, module handles) through it.** The interface stays runtime-agnostic so the WASM engine
can be swapped per platform via a single DI binding, with all UI/domain code unaffected.

```kotlin
// commonMain
interface SourceLoader {
    suspend fun loadSource(id: String): Source
    suspend fun listSources(): List<SourceMeta>
    suspend fun fetchMangaList(sourceId: String): List<Manga>
    suspend fun fetchChapterPages(sourceId: String, chapterId: String): List<Page>
}
```

Implementation: a single `WasmSourceLoader` whose engine binding is provided per platform
(`expect`/`actual`) — Android and iOS share the same loader and the same `.wasm` sources.

---

## Cloudflare Bypass (key differentiator)

Existing iOS manga apps (Suwatte, Paperback) run sources in JavaScriptCore with no browser context,
so Cloudflare blocks requests. Tanuki solves this the way Mihon does on Android — a real browser
engine interceptor:

```
HTTP Request → CloudflareInterceptor
   ├─ normal response → return it
   └─ CF challenge (403/503) → WebView solves it, extracts cf_clearance cookie → retry with cookie
```

- **Android:** `android.webkit.WebView` (Chromium)
- **iOS:** `WKWebView` driven headlessly (offscreen) via `expect`/`actual` — it solves the challenge;
  it is not part of the Compose UI (headless platform binding, one of the few bits of iOS-specific code)
- Interceptor logic lives in `commonMain`; the WebView is injected via `expect`/`actual`.
- **Independent of the extension runtime** — the interceptor and the WASM source loader are separate
  concerns. Don't couple the two.

---

## Extension System — WASM only

One extension runtime, both platforms. Sources are compiled to `.wasm` bundles, fetched from a remote
repo at runtime, and executed in an embedded WASM runtime. The same `.wasm` artifact runs identically
on Android and iOS — only the engine binding differs per platform (`expect`/`actual`).

This is a deliberate single-track decision: **no JSCore, no APK loader.** Aidoku
(https://github.com/Aidoku/Aidoku) is the closest reference for the WASM source model. Picking the
concrete embedded runtime (e.g. a Kotlin/Native-friendly engine on iOS, and the JVM/Android engine)
and defining the WASM ABI are the first real design tasks for this subsystem.

### Source ecosystem & naming (what the umbrella does and doesn't cover)

Keep three layers distinct — only the middle one carries the `app.tanuki` umbrella:

| Layer | What | Naming |
|---|---|---|
| The app | the reader | `app.tanuki` |
| **Source SDK / `source-api`** | the contract sources compile against (`Source`, `Manga`, `Page`, the WASM ABI) — a published artifact | `app.tanuki.source.api` |
| A **source implementation** | the actual MangaDex/etc. source, compiled to `.wasm` | **its own identity — NOT forced under `app.tanuki`** |

- A `.wasm` binary has **no JVM package** — it's identified by an **ABI** + a **string source ID** (e.g.
  `"mangadex"`) in metadata, not a package name. So source repos are not `app.tanuki.*`.
- A JVM-style package only exists if a source is written in **Kotlin/Wasm**, and then it's the *author's*
  choice; the source merely **depends on** `app.tanuki.source.api`. (Sources may also be Rust / AssemblyScript
  — then there's no package at all, like Aidoku.)
- **First-party** sources the org maintains may live under `app.tanuki.source.<name>` (its own content);
  **third-party** sources use their own namespace (`io.github.<author>.*`) and just link the SDK.
- What unifies the ecosystem is the **GitHub org**, not the package: e.g. `tanuki/tanuki` (app),
  `tanuki/source-api` (SDK), `tanuki/sources` (first-party WASM sources), `tanuki/source-cli` (tooling).

---

## Upstream & Licensing

- **Upstream:** https://github.com/mihonapp/mihon (Apache-2.0)
- Keep a LICENSE file and document changes from upstream.
- **Don't** use "Mihon" branding or imply affiliation. The app is "Tanuki".
- When porting Mihon code, repackage `tachiyomi.*` / `mihon.*` into `app.tanuki.*`. Do not
  introduce new code under `tachiyomi.*` or `mihon.*` packages.

---

## What NOT to do

- Don't leak platform/runtime types (`WasmMemory`, module handles, `NSURLRequest`) through shared interfaces.
- Don't reintroduce a JSCore runner or an APK-based loader — the extension system is WASM-only.
- Don't pull genuinely Android-only libraries into shared modules — use KMP-compatible ones (Room KMP /
  Ktor / Metro). Room is the database: use its **KMP** build (`androidx.room` 2.7+) with the **bundled
  SQLite driver** (`androidx.sqlite:sqlite-bundled`) so iOS/native works; add the KSP room compiler per
  target. Don't reach for SQLDelight — Room is the decided choice.
- Don't import Android-specific Compose APIs in `commonMain`.
- Don't write native SwiftUI screens for app UI — the iOS UI **is** Compose Multiplatform from `sharedUI`.
  `iosApp` is a thin host that launches a `ComposeUIViewController`; the only iOS-specific code is
  `expect`/`actual` platform bindings (headless `WKWebView`, WASM engine), not UI.
- Don't define routes per-platform — destinations live once in `sharedLogic` (`Destination` sealed type).
- Don't extend `ViewModel` directly, and don't re-declare a `navEvents` channel, a `stateIn` timeout, or
  a `withContext(dispatcher.io)` wrapper in a ViewModel — extend `TanukiViewModel` and use what it owns
  (see "Presentation pattern"), including the `binding<ViewModel>()` requirement on `@ContributesIntoMap`.
- Don't end a ViewModel pipeline with `.onEach { uiState.value = it }.launchIn(viewModelScope)` — that
  runs for the ViewModel's whole lifetime whether or not any UI is subscribed. Extend `StateViewModel<S>`
  and let its `asState` gate the pipeline on `WhileSubscribed`.
- Don't invent ad-hoc colors, type scales, spacing, or UI components — base all designs on the Tanuki
  Design System (remote claude.ai/design project, via the `claude_design` MCP). One Compose design system
  serves both platforms; the iOS Liquid Glass kit is reference only, so don't fork components per platform.
- Don't set type with raw font sizes — use the `TanukiTextStyle` tokens (Compose `TextStyle`s in the
  design system) so size/weight/tracking/line-height come from the scale. (Snap to the nearest token;
  don't use off-scale sizes.)
- Don't hardcode user-facing copy — every display string must be localizable via Compose Resources with
  `en` + `pt-BR` entries (see "Localization"). No inline display literals in Compose; use
  `stringResource(Res.string.…)`.
- Don't put dependency versions inline in build files — add them to `gradle/libs.versions.toml`.
- Don't put shared build config in module build files — extend the `gradle/build-logic` convention
  plugins (`tanuki.*`) instead; SDK/JVM levels live in `Config.kt`.
- Don't invert module dependencies (`sharedLogic` must not depend on `sharedUI` or the apps).
- Don't couple the Cloudflare interceptor to the extension loader — they are independent.
- Don't break the Android build when adding iOS code; keep both targets green.

---

## References

- **Tanuki Design System (authoritative UI reference):** remote claude.ai/design project `https://claude.ai/design/p/425e7085-96ba-4446-8f59-63793c9cbf48` — read via the `claude_design` MCP connector (`DesignSync` tool); auth with `/design-login`
- Mihon upstream: https://github.com/mihonapp/mihon
- Aidoku (primary WASM extension reference): https://github.com/Aidoku/Aidoku
- Suwatte (illustrates the iOS Cloudflare problem JSCore readers hit): https://github.com/suwatte
- Compose Multiplatform WebView (Android in-app webview): https://github.com/KevinnZou/compose-webview-multiplatform
- Compose Multiplatform for iOS (shared Compose UI, `ComposeUIViewController`): https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-multiplatform-ios.html
- Compose Multiplatform Resources (localized strings, images): https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-multiplatform-resources.html
- Navigation Compose (multiplatform): https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-navigation-routing.html
- SKIE (Kotlin↔Swift bridging — only if thin Swift host code needs it; not used for UI): https://skie.touchlab.co
- Apple Liquid Glass (design reference only — not implemented natively): https://developer.apple.com/documentation/technologyoverviews/liquid-glass
- KMP docs: https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html