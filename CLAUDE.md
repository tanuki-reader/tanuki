# Tanuki — Claude Code Context

Tanuki is a free and open source manga/comic reader for **Android and iOS**, built as a
Kotlin Multiplatform (KMP) reimplementation of [mihonapp/mihon](https://github.com/mihonapp/mihon).

This repo is a **fresh start**. It begins from the JetBrains KMP wizard scaffold and grows
toward the Mihon feature set incrementally. A previous, more fully-scaffolded attempt lives at
`~/Documents/personal_projects/tanuki` and can be mined for reference, but this is the canonical repo.

---

## Project Goals

- Bring the full Mihon experience (library, sources, trackers, reader) to iOS
- Share as much logic as possible via KMP — domain/data/network/source + ViewModels and navigation
  contracts. **UI is native per platform:** Jetpack Compose on Android, SwiftUI with Liquid Glass on iOS
  (see "Platform UI split" below)
- Solve the Cloudflare bypass problem that plagues existing iOS manga readers (e.g. Suwatte)
- Design for a WASM-based extension migration (App Store / Play Store compatible path)

---

## Build & Run

```bash
# Android debug build
./gradlew :androidApp:assembleDebug

# iOS: open ./iosApp in Xcode and run from there (no gradle run task).
# Today iosApp still hosts the shared Compose UI (ComposeUIViewController via MainViewController).
# Target: iosApp is a native SwiftUI app consuming the shared Kotlin framework through SKIE.
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

## Platform UI split (decided — migration pending)

**Decision:** the iOS app gets a **native SwiftUI UI** built on **Liquid Glass** (iOS 26); Android keeps
its **Jetpack Compose** UI. What stays shared in Kotlin is everything below the UI: domain, data,
network, sources, ViewModels (`StateFlow<UiState>` + action methods) and the `Destination` contract.
This reverses the earlier "one Compose UI on both platforms" decision.

> **Current state (2026-10):** iOS still renders the shared Compose UI through
> `iosApp/iosApp/ContentView.swift` → `MainViewControllerKt.MainViewController()`. The migration is in
> **Phase 0 (foundation)**: SKIE is applied in `:shared`, which exports the `:core:*` modules to Swift, and
> the Swift side of the bridge has been verified. No SwiftUI screen ships yet. **Plan, estimates and
> progress log: `docs/swiftui-migration.md`. Read it before any migration work, and update its progress
> log at the end of the session.** Until the split lands:
> - **New iOS-facing UI work goes to SwiftUI** in `iosApp`, not into shared Compose screens.
> - Keep the Compose UI working on iOS (don't break the current host) until the SwiftUI screen that
>   replaces it ships. Migrate one feature slice at a time, in the order the plan sets
>   (home → settings → library → source browse → manga → activity → downloads → reader). Unmigrated
>   destinations keep rendering their Compose screen inside the SwiftUI root.
> - A Kotlin bump must wait until **both** SKIE and Metro support the new version. Build the iOS
>   framework, not just Android, before calling it done.
> - ViewModels must become consumable from Swift: move them out of the Compose `:features:<x>` modules
>   into Compose-free modules (the `:features:<x>:logic` / `:features:<x>:ui` pairs from "When to split")
>   that are exported in the iOS framework.

- **Swift ↔ Kotlin bridge: SKIE.** `StateFlow`/`Flow` arrive in Swift as `AsyncSequence`, sealed types as
  exhaustive Swift enums (`onEnum(of:)`), `suspend` functions as `async`. Don't hand-write Flow wrappers
  or callback adapters; don't add KMP-NativeCoroutines alongside it.
- **Each iOS screen** is a SwiftUI `View` plus a small `@Observable` Swift holder that owns the Kotlin
  ViewModel, iterates its `uiState` in a `.task { for await state in vm.uiState { … } }`, forwards user
  actions to the ViewModel's methods, and calls `clear()` when the screen is torn down. No presentation
  logic in Swift — if Swift needs to compute it, it belongs in the ViewModel.
- **What is native per platform:** screens, components, navigation rendering, icons, motion, strings
  presentation, platform chrome. Once a slice is migrated, no composable renders on iOS for it.
- iOS-specific non-UI code (headless `WKWebView`, WASM engine binding) stays in `iosMain` via
  `expect`/`actual`, exactly as before.

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
> | `:androidApp` | thin Android host | — |
> | `iosApp` (Xcode) | today a thin host for the Compose UI; target: the native SwiftUI app (screens, Swift design system, SKIE consumers) | — (SwiftUI) |
> | `:shared` | app shell: the one `Scaffold`/`NavHost`, Metro `AppGraph`, platform bindings | yes |
> | `:core:domain` | models, repository interfaces, use cases, `DispatcherProvider`/`Logger` | no |
> | `:core:data` | Room entities/DAOs + repository implementations | no |
> | `:core:navigation` | `Destination`, `NavEvent`, `NavCommand`, `AppNavigator` | no |
> | **`:core:presentation`** | **`TanukiViewModel`** + **`StateViewModel<S>`** — the base classes every screen's ViewModel extends | no |
> | `:core:source` | WASM source runtime + Cloudflare/session networking | no |
> | `:features:<x>` | one per slice (`library`, `manga`, `reader`, `activity`, `home`, `source`, `settings`) — each holds that slice's Compose screens **and** its ViewModels | yes |
> | `:assets:designSystem` | the Compose design system — **Android dialect** of the Tanuki Design System | yes |
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

sharedUI/      (Compose — ANDROID UI only once the split lands; depends on sharedLogic)
  app.tanuki/
    designsystem/  Android dialect of the design system (theme, colors, TanukiTextStyle, components)
    feature/       per-feature Compose screens; bind to the ViewModels in sharedLogic
      library/  browse/  reader/  tracking/  updates/  settings/

iosApp/        (Xcode — native SwiftUI; imports the shared framework, SKIE-enhanced)
  DesignSystem/    iOS dialect: color assets + Color.tk.*, type, Liquid Glass components
  Features/        per-feature SwiftUI screens + @Observable holders over the Kotlin ViewModels
      Library/  Browse/  Reader/  Tracking/  Updates/  Settings/
  Navigation/      NavigationStack/TabView rendering of the shared `Destination`
```

### Presentation pattern: MVVM (not MVI)

Each feature has a **ViewModel** that lives in `sharedLogic` (Compose-free) and exposes immutable UI
state as a `StateFlow<XxxUiState>`, with plain methods for user actions (`onSearch()`, `onRefresh()`).

- **Why in `sharedLogic` and not `sharedUI`:** keeping ViewModels Compose-free preserves the boundary
  rule (`sharedLogic` never imports Compose), lets presentation logic be unit-tested without a Compose
  runtime, and is what lets **SwiftUI consume the same ViewModels** — presentation logic is written once,
  even though each platform draws its own screens.
- Use the KMP `ViewModel` from `androidx.lifecycle.viewmodel` (already a dependency) so both platforms
  get lifecycle scoping for free.
- **Android:** Compose screens collect state with `collectAsStateWithLifecycle()` and call ViewModel
  methods. **iOS (target):** SwiftUI observes `uiState` through SKIE's `AsyncSequence` in an
  `@Observable` holder and calls the same methods (see "Platform UI split"). No reducers/intents/MVI
  machinery on either side. (Today iOS still runs the Compose screens.)
- **Keep ViewModel APIs Swift-friendly:** UI state as data classes / sealed interfaces (SKIE turns them
  into enums), actions as plain methods with simple parameters, no Compose types (`Color`, `ImageVector`,
  `StringResource` resolved to text) in state.
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

Single-activity on Android; a SwiftUI `App` on iOS (today still a root view controller hosting the
Compose UI). The **set of destinations is defined once in `sharedLogic`** as a serializable sealed type;
each platform renders it natively.

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

- **Android:** **Jetpack Navigation Compose**; a single `NavHost` switches over `Destination`.
- **iOS (target):** a `TabView` (Liquid Glass tab bar) whose tabs each own a `NavigationStack`; a
  `NavigationPath` of `Destination` values, rendered with `.navigationDestination` and an exhaustive
  `switch onEnum(of: destination)`. Until the split, iOS uses the same Compose `NavHost`.
- Navigation *intent* lives in shared ViewModels (`NavEvent`); each platform's navigator performs the
  push/pop. Routes and params are defined once in `Destination`, so they cannot drift; a new destination
  is not done until both platforms render it.
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
5. Only Compose-free modules are exported to the iOS framework. Swift never sees a Compose type.

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
| Android UI | Jetpack Compose + Material 3 (`sharedUI`, `:assets:designSystem`) |
| iOS UI | SwiftUI on iOS 26 with Liquid Glass (`iosApp`) — native, not Compose (migration pending) |
| Swift ↔ Kotlin bridge | SKIE (Flow → AsyncSequence, sealed → enum, suspend → async) |
| iOS WebView (Cloudflare) | `WKWebView` headless via `expect`/`actual` — not part of the Compose UI tree |
| Extensions (both platforms) | WASM source runtime — single shared mechanism, no JSCore, no APK loader |
| Presentation | MVVM — `ViewModel` + `StateFlow<UiState>` in `sharedLogic` (Compose-free, consumed by both UIs), no MVI |
| DI | Metro (KMP-compatible) |
| Async | Kotlin Coroutines + Flow |

---

## Design System — single source of truth for all UI

**All UI, designs and visual artifacts MUST follow the Tanuki Design System**, a claude.ai Design System
artifact: **https://claude.ai/artifact/D6FkueZhdrbViopx1rP1AT** (same artifact:
`https://claude.ai/code/artifact/61e97a24-0b84-4d3a-8740-97432b556ee4`). It is the authority for brand,
color, typography, spacing, shape, Liquid Glass usage, iconography, motion, voice and the component
language of both platforms. Don't invent ad-hoc colors, type sizes, spacing or components.

> The old claude.ai/design project (`425e7085-96ba-4446-8f59-63793c9cbf48`, read via `DesignSync` /
> `/design-sync`) and its `#E8731C` orange / tanuki-face logo are **superseded**. Don't read from it or
> sync to it.

- **How to read it:** use the Artifact tool's `read` on the link with a file `path`. Start with
  `project/README.md` (the brand book and all usage rules), then `project/tokens.json` (every token, both
  themes, with usage notes) and `project/components/<Name>/README.md` for a component's guidelines.
  Logos are uploaded assets listed in `project/design-system.json` under `assetGroups.Logos`. The
  artifact is private to the owner's account; if it can't be read, say so — don't guess values.
- **One brand core, two native dialects.** The core (tokens, type scale, voice, the mark) is shared.
  Android renders it as Material 3 in `:assets:designSystem` (`TanukiTheme` → M3 `ColorScheme` +
  `LocalTanukiColors`); iOS renders it natively in `iosApp/DesignSystem` (asset-catalog colors, `Color.tk.*`).
  Components keep the same names and behavior but each platform owns its own implementation. Generate
  `TanukiColors.kt` and the iOS color assets **from `tokens.json`**; never hand-copy hex values.
- **Brand at a glance:** dark-first (Night "sumi" + Day "washi"), warm ink/paper neutrals, one
  persimmon `accent` (`kaki` #f2843a; #b0480b on light grounds) for state and the single primary action.
  Display face **Bricolage Grotesque** (wordmark, hero titles, Android large titles); text face is the
  platform's (Roboto / SF Pro). The mark is the **leaf-page** (a persimmon leaf split like an open book).
- **iOS = Liquid Glass.** Glass is the navigation layer only: tab bar (+ search tab, continue-reading
  bottom accessory), toolbar buttons, search, sheets, alerts, menus, reader chrome. Content (covers,
  chapter rows, lists, cards, text) stays solid. Use the system: `.glassEffect()`,
  `.buttonStyle(.glass)` / `.glassProminent` (only the primary action, tinted `accent`),
  `GlassEffectContainer`, `ToolbarItemGroup`, scroll-edge effects. The `glass-*` tokens exist only for
  web previews — Swift never paints them. Android has **no glass or blur**: opaque M3 tonal surfaces.
- **Icons are the system sets — never hand-drawn.** Android: **Material Symbols Rounded** (FILL 0 at
  rest, FILL 1 when selected). iOS: **SF Symbols** (`.fill` variants for selection, hierarchical
  rendering for status, symbol effects for state changes). The Tanuki → Material → SF name table is in
  the README. The custom-drawn `TanukiIcons.kt` vectors are being replaced; don't add to them. The only
  proprietary glyph is the leaf mark.
- **Status of the code:** `:assets:designSystem` still holds the previous palette, type scale and logo.
  Bring it to the new tokens incrementally; when the code and the artifact disagree, the artifact wins.

---

## Localization (en + pt-BR)

Every user-facing string MUST be localizable — the app ships in **English** and **Brazilian
Portuguese (pt-BR)** for now, more later. No hardcoded display copy.

- **Android (and the Compose UI that iOS still runs today): Compose Multiplatform Resources.** Strings
  live in `sharedUI/src/commonMain/composeResources/`: `values/strings.xml` (en) and
  `values-pt-rBR/strings.xml` (pt-BR).
- **Native iOS UI: open decision.** How SwiftUI screens get localized copy (an Xcode String Catalog in
  `iosApp`, or one shared string source both platforms read) is **not decided yet**. Raise it before the
  first SwiftUI screen ships; don't pick one silently. Whatever is chosen, en + pt-BR stay in parity
  with Android.
- In composables, reference strings by generated id: `Text(stringResource(Res.string.filter_by_category))`
  — never an inline display literal. Dynamic values go through format args
  (`stringResource(Res.string.show_n_titles, n)`), not string concatenation; counts use
  `pluralStringResource(...)`.
- **Strings needed in `sharedLogic` (Compose-free ViewModels):** do not resolve text there, and don't
  put `StringResource` in UI state either (Swift can't resolve it). Expose *meaning* — an enum or sealed
  type (`DownloadError.NoNetwork`), counts, ids — and let each platform's UI map it to localized copy.
  Keep raw display `String`s out of ViewModels.
- Pure content/data (manga title, genre, author) stays a plain `String` — it comes from sources, isn't
  localizable, and never goes through `stringResource`.
- A new screen is not done until both languages have every key. Don't add a string in one language only.
- Status: **not wired up yet** — `composeResources/` string catalogs don't exist. Set up `values/` +
  `values-pt-rBR/` for the Compose UI; the iOS mechanism waits on the open decision above.

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
  it is not part of any UI tree (a headless platform binding in `iosMain`, independent of SwiftUI)
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
- Don't build new iOS UI in Compose — new iOS screens are SwiftUI. Don't remove the Compose host for a
  slice until its SwiftUI replacement ships. Don't put presentation logic in Swift; it belongs in the
  shared ViewModel.
- Don't expose Compose types (`Color`, `ImageVector`, `StringResource`, `@Composable` lambdas) from
  ViewModels or anything exported to the iOS framework.
- Don't hand-write Flow/suspend bridges for Swift — SKIE handles them.
- Don't define routes per-platform — destinations live once in `sharedLogic` (`Destination` sealed type);
  only their rendering is per-platform.
- Don't extend `ViewModel` directly, and don't re-declare a `navEvents` channel, a `stateIn` timeout, or
  a `withContext(dispatcher.io)` wrapper in a ViewModel — extend `TanukiViewModel` and use what it owns
  (see "Presentation pattern"), including the `binding<ViewModel>()` requirement on `@ContributesIntoMap`.
- Don't end a ViewModel pipeline with `.onEach { uiState.value = it }.launchIn(viewModelScope)` — that
  runs for the ViewModel's whole lifetime whether or not any UI is subscribed. Extend `StateViewModel<S>`
  and let its `asState` gate the pipeline on `WhileSubscribed`.
- Don't invent ad-hoc colors, type scales, spacing, or UI components — base all designs on the Tanuki
  Design System artifact (https://claude.ai/artifact/D6FkueZhdrbViopx1rP1AT). Don't use the old
  claude.ai/design project. Same components on both platforms, each implemented natively.
- Don't put Liquid Glass on content (covers, lists, cards, text), stack glass on glass, or tint secondary
  glass; don't add glass/blur on Android. Don't draw custom icons — Material Symbols Rounded on Android,
  SF Symbols on iOS.
- Don't set type with raw font sizes — on Android use the `TanukiTextStyle` tokens (Compose `TextStyle`s
  in the design system); on iOS use the Dynamic Type text styles the design system maps to the scale.
  Snap to the nearest token; don't use off-scale sizes.
- Don't hardcode user-facing copy — every display string must be localizable with `en` + `pt-BR`
  entries (see "Localization"). No inline display literals in Compose (use `stringResource(Res.string.…)`)
  or in SwiftUI.
- Don't put dependency versions inline in build files — add them to `gradle/libs.versions.toml`.
- Don't put shared build config in module build files — extend the `gradle/build-logic` convention
  plugins (`tanuki.*`) instead; SDK/JVM levels live in `Config.kt`.
- Don't invert module dependencies (`sharedLogic` must not depend on `sharedUI` or the apps).
- Don't couple the Cloudflare interceptor to the extension loader — they are independent.
- Don't break the Android build when adding iOS code; keep both targets green.

---

## References

- **Tanuki Design System (authoritative UI reference):** https://claude.ai/artifact/D6FkueZhdrbViopx1rP1AT — read with the Artifact tool, starting at `project/README.md`
- Mihon upstream: https://github.com/mihonapp/mihon
- Aidoku (primary WASM extension reference): https://github.com/Aidoku/Aidoku
- Suwatte (illustrates the iOS Cloudflare problem JSCore readers hit): https://github.com/suwatte
- Compose Multiplatform WebView (Android in-app webview): https://github.com/KevinnZou/compose-webview-multiplatform
- SKIE (Kotlin↔Swift bridge for the SwiftUI app): https://skie.touchlab.co
- Apple Liquid Glass (implemented natively in the iOS app): https://developer.apple.com/documentation/technologyoverviews/liquid-glass
- SF Symbols: https://developer.apple.com/sf-symbols/
- Material Symbols: https://fonts.google.com/icons
- Compose Multiplatform for iOS (current iOS host, until the split): https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-multiplatform-ios.html
- Compose Multiplatform Resources (localized strings, images): https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-multiplatform-resources.html
- Navigation Compose (multiplatform): https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-navigation-routing.html
- KMP docs: https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html
