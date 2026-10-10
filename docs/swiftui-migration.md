# iOS → SwiftUI migration: findings & plan

> Living doc. Update the **Progress log** at the end of every session. The decision itself is recorded
> in `CLAUDE.md` → "Platform UI split"; this file covers *how* we get there.

## Context

When this plan was written, iOS drew the shared Compose UI: `iosApp/iosApp/ContentView.swift` →
`MainViewControllerKt.MainViewController()` → `App(appGraph)` → `TanukiNavHost`. (Since Phase 0 it has a
SwiftUI root; see the progress log.) We're moving iOS to
native SwiftUI (iOS 26, Liquid Glass). Android stays on Compose. **Rule: all presentation logic lives in
the shared Kotlin ViewModels.** SwiftUI and Compose only render state and forward taps, so neither
platform duplicates logic.

## TL;DR answers

1. **Can we migrate one feature at a time? Yes.** We don't need everything migrated before the new
   version builds. iOS gets a SwiftUI root (`TabView`, with a `NavigationStack` per tab) right away. Any
   destination not migrated yet renders its existing Compose `*Route` inside a
   `UIViewControllerRepresentable`, through a per-destination Kotlin bridge. Each migrated feature
   swaps its bridge for a SwiftUI view. The app ships and works at every step.
2. **There's a one-time foundation phase first (~2.5–3.5 weeks).** It covers SKIE, framework exports,
   the Swift↔ViewModel glue, the hybrid root, image loading, the Cloudflare session overlay, iOS
   design tokens and strings. After that, Home takes about 1 week.
3. **Total estimate: ~58–91 dev-days (≈3–4.5 months for one full-time dev).** The Reader and Manga
   detail are the biggest pieces. Breakdown below.
4. **Navigation logic stays the same.** `Destination`, `NavEvent` and `TanukiViewModel.navigateTo()` /
   `navigateBack()` don't change. Android keeps `TanukiNavHost`. iOS turns the same `navEvents` into
   `NavigationPath` pushes and pops.

## Findings (state of the code, 2026-10-10)

| Finding | Where | Impact |
|---|---|---|
| ViewModels are already Compose-free in practice (one exception: `@Immutable` in `ReaderState.kt`) | `features/*/…/viewmodel/` | Good. The logic is already shared, so we mostly re-host the UI |
| ViewModels live **inside** Compose feature modules | `features/<x>/build.gradle.kts` | To export them to Swift without dragging Compose into the headers, move `viewmodel/` into `:features:<x>:logic` |
| Framework `Shared` exports nothing explicitly. Features are `api` deps of `:shared` only | `shared/build.gradle.kts` | Add `export(...)` for `:core:domain`, `:core:navigation`, `:core:presentation` and each `:features:<x>:logic` |
| ~~No SKIE yet~~ **Done (spike):** SKIE 0.10.15 on Kotlin 2.4.20 | `gradle/libs.versions.toml`, `shared/build.gradle.kts` | Kotlin bumps are now gated on SKIE **and** Metro support (see spike results) |
| iOS deployment target is already **26.0** | `iosApp.xcodeproj` | `@Observable` and Liquid Glass are available. No back-compat work |
| App-lifetime jobs run as `LaunchedEffect`s in the Compose root: dev source install, foreground library update, verify downloads + resume queue, prune history, keep the update schedule, crash-reporting consent | `shared/…/App.kt:53-115` | Must move to a Compose-free `AppStartup.start()` that both platforms call. **This blocks the SwiftUI root** |
| Theme preference is observed in the Compose root | `App.kt` | The SwiftUI root observes `observeThemePreference()` → `.preferredColorScheme` |
| The Cloudflare / site-session overlay is a root-level Compose layer (471 lines) | `shared/…/platform/web/SiteSessionHost.kt`. The coordinator, `core/source/…/SiteSessionCoordinator.kt`, is Compose-free | Needs a SwiftUI overlay that observes the same coordinator. The iOS `WKWebView` code already exists (`SiteSessionWebView.ios.kt`) |
| Covers and pages load through Coil on the shared CF-aware `sourceHttpClient`, with per-image headers | `shared/…/platform/imaging/TanukiImageLoader.kt` | SwiftUI `AsyncImage` can't see the Ktor `cf_clearance` cookies or headers. Needs a Kotlin image bridge |
| Some screen-local UI state lives in composables (e.g. Home's For You/Sources toggle, `rememberSaveable`) | `HomeScreen.kt:86` | Move it into the ViewModel as an input `MutableStateFlow`, so neither UI holds it |
| `AppNavigator` / `NavCommand` have **no consumers** (only a KDoc mention in `MangaViewModel`) | `core/navigation/` | Leave as is. Wire it into both roots when deep links arrive |
| ~~`UuidNavType` lives in `:core:navigation`~~ **Done:** moved to `:shared` as `internal` | `shared/…/navigation/UuidNavType.kt` | `:core:navigation` is Compose-free, and its header has no `NavType` |
| `:shared`'s **own** public API is always in the framework header (`export` doesn't control it), so `App()`, the image loader builders, `AppGraph`, etc. pull Skiko/Coil/Ktor/Compose-runtime types into Swift | `shared/src/commonMain` | Harmless but noisy, and it slows Swift indexing. Mark Compose-facing `:shared` declarations `internal` as they're touched, and drop them in Phase 9 |
| Strings are in Compose Resources (~360 keys across modules), and SwiftUI can't read them natively | `features/*/composeResources/values*/strings.xml` | Needs a strings pipeline (see Decisions) |
| State uses `kotlin.uuid.Uuid` and `kotlin.time.Instant` | e.g. `HomeState.kt` | Uuid reaches Swift as an opaque hashable class, which is fine for ids and navigation. For Instant, expose epoch millis or add one Swift extension to `Date` |
| Kotlin objects reach Swift as `NSObject` subclasses, with `isEqual`/`hash` → `equals`/`hashCode` | — | Data-class `Destination`s are `Hashable`, so they work directly in `NavigationPath` |

## Target architecture (iOS side)

```
iOSApp (SwiftUI @main)
 ├─ AppStartup.start()                     ← Kotlin, shared with Android
 ├─ RootView: TabView (Liquid Glass tab bar)
 │    └─ per tab: NavigationStack(path: [Destination])
 │         ├─ root: SwiftUI screen if migrated, else ComposeDestinationView(dest)
 │         └─ .navigationDestination(for: Destination) → DestinationView(dest)
 │                switch onEnum(of: dest) { migrated → SwiftUI ; else → Compose bridge }
 └─ .overlay: SiteSessionOverlay (observes SiteSessionCoordinator)
```

### Swift ↔ ViewModel glue (written once, reused by every screen) — **built**

- **Kotlin, `shared/src/iosMain/kotlin/app/tanuki/ScreenScope.kt`:** owns a `ViewModelStore` and
  resolves ViewModels through `ViewModelProvider.create(store, appGraph.metroViewModelFactory)`, the same
  factory as `metroViewModel()`. `close()` clears the store. Swift can't name a `KClass`, so each screen
  gets a one-line accessor: `fun ScreenScope.homeViewModel(): HomeViewModel`. **Assisted ViewModels**
  (Manga, Reader, SourceBrowse) get an accessor taking the route args when they migrate:
  `viewModelFactory { addInitializer(kClass) { factory.createManuallyAssistedFactory(F::class)().create(…) } }`.
- **Swift, `iosApp/iosApp/Presentation/ScreenModel.swift`:** `@Observable ScreenModel<VM, S>`. It
  creates the ViewModel **lazily**, because SwiftUI re-evaluates `@State` initializers and discards the
  extras. It exposes `vm` (for actions) and `state` (the last `uiState` emission, else `.value`).
  `observe()` iterates `uiState` in the screen's `.task`, and `deinit` closes the scope.
- **Swift, `iosApp/iosApp/Navigation/NavEvents.swift`:** `Route` wraps `any Destination` as `Hashable`,
  using Kotlin `equals`/`hashCode`. `.navEvents(vm.navEvents, path: $path)` appends or pops on a
  `[Route]` path.
- **Per screen, Swift is only the `View`:**
  `@State private var model = ScreenModel({ $0.homeViewModel() }, uiState: \.uiState)`, plus
  `.task { await model.observe() }` and `.navEvents(model.vm.navEvents, path: $path)`. Lifecycle matches
  Android: leaving cancels the `.task` → `WhileSubscribed(5s)` stops the pipeline, and popping clears the
  ViewModel.

### Hybrid bridge for unmigrated destinations

- Pull the `Destination → *Route` mapping out of `TanukiNavHost.kt` into
  `@Composable fun DestinationContent(dest, onNavigate, onBack)`. Android's NavHost then calls that
  function too, so the code isn't duplicated.
- `iosMain`: `fun ComposeDestinationController(dest, onNavigate, onBack): UIViewController` wraps
  `DestinationContent` in the same providers `App.kt` sets up (theme, `LocalMetroViewModelFactory`,
  `LocalPageImageLoader`).
- Swift hides its own nav bar for Compose leaves (they draw their own top bars). Compose's back button
  calls `onBack` → pop. Native swipe-back works.
- The bridge is temporary. Delete it, and the Compose dependency from the iOS targets, once the last
  feature is migrated.

### Navigation: stays as is

- Shared and unchanged: the `Destination` sealed type, `NavEvent`, and `navigateTo`/`navigateBack` in
  `TanukiViewModel`.
- Android: `TanukiNavHost` is unchanged, apart from using `DestinationContent`.
- iOS: tab selection lives in Swift (`TabView(selection:)`), with one `NavigationPath` per tab.
  `TopLevelDestination.entries` drives the tabs. Tab switching is pure chrome with no logic, so it
  belongs in the UI.
- Swift switches over destinations with `onEnum(of:)`, which is exhaustive. A new `Destination`
  breaks the Swift build until iOS renders it, which is what we want.
- Not now: iOS state restoration of the path (Kotlin objects aren't `Codable`). Add it with deep links,
  via `AppNavigator`.

## Making the ViewModels carry all the logic (scalability rules)

1. **State is render-ready.** Labels are preformatted (`chapterNumberLabel` already is), there are
   derived booleans (`forYouIsEmpty`), and lists are sorted and grouped. UI code may only `if`/`switch`
   on state. It never computes.
2. **No screen-local state in the UI.** Toggles, selected segments, open sheets and selection mode are
   input `MutableStateFlow`s folded into `state` (the existing `StateViewModel` rule). This is the
   Home toggle's first change.
3. **Copy is keys, not text.** State carries enums or ids for messages plus raw values. Each platform
   maps the key to its localized string. No resolved `String`s and no `StringResource` in state.
4. **One-shot effects use the `navEvents` pattern** (a `Channel` → `Flow`, like
   `ReaderViewModel.commands`), collected by the same Swift modifier style.
5. **Swift-friendly API:** plain methods with simple params, sealed interfaces for state (SKIE →
   Swift enums), and non-null primitives where possible (avoid `KotlinInt?`).
6. **Previews and tests come free:** state is plain data, so SwiftUI previews build
   `HomeState.Content(...)` directly with no ViewModel. Logic tests stay in `commonTest`.
7. When moving a feature, **audit its composables for logic** (formatting, filtering, `remember`
   calculations) and move it into the ViewModel *first*. That also slims the Android UI.

## Decisions to confirm (defaults chosen)

- **Bridge: SKIE** (already the decision in CLAUDE.md). Verified in the Phase 0 spike.
- **Strings (built):** `strings.xml` stays the one source. `scripts/generate-ios-strings.py` (Gradle
  `:shared:generateIosStrings`, which runs before every Xcode framework build) writes one checked-in
  table per module to `iosApp/iosApp/Strings/` (`:shared` → `Localizable`, `:features:<x>` → `<X>`),
  plus a generated `StringTable` enum. It converts escapes, `%1$s` → `%1$@`, `%1$d` → `%1$lld`, and
  plurals into plural variations, and **fails the build if a language lacks a key**. Swift reads copy
  with `tr("key", args…, table: .home)`; plain default-table keys also work as `LocalizedStringKey`.
  Never edit the `.xcstrings`.
- **Images (built):** Kotlin owns the fetch and the disk cache. `ImageBridge.loadCoverData(url, headers)`
  goes through Coil's singleton loader: the CF-aware Ktor client, `cover_cache` shared with Compose,
  success-only caching, and a pass-through decoder that returns the encoded bytes. **Nuke 13** (SPM) owns
  the memory cache, decoding, coalescing and prefetching, with a `KotlinDataLoader` (Nuke's
  `DataLoading`) replacing its network and no Nuke disk cache (`ImagePipeline.tanuki`). Screens only use
  `TanukiImage(url:headers:placeholder:)`. Never fetch images with `URLSession`: it would bypass the
  Cloudflare solve.
- **Design tokens (built, Phase 1):** the Tanuki Design System artifact
  (https://claude.ai/artifact/D6FkueZhdrbViopx1rP1AT) is the authority; the old claude.ai/design project
  that Phase 0's tokens came from is superseded. `iosApp/design/tokens.json` is a checked-in copy of the
  artifact's `project/tokens.json`, and `scripts/generate-ios-tokens.py` turns it into the colorsets,
  `Color.tk.*` and `TK.Space/Radius/Size`. Re-download the json and re-run the script when the design
  changes; never hand-copy values. Type maps onto the system Dynamic Type styles (`TanukiType.swift`),
  and motion is hand-written from the README (`TanukiMotion.swift`). Android's `:assets:designSystem`
  still has the old palette and is migrated separately.
- **Module split per feature, done when that feature migrates** (`:features:home:logic` first), not
  all at once.

## Phased plan & estimates (one dev, dev-days)

| Phase | Work | Estimate |
|---|---|---|
| **0. Foundation** (blocking) | SKIE + Kotlin 2.4.10 spike (0.5–1) · `AppStartup` out of `App.kt` (0.5) · framework `export` + move `UuidNavType` (0.5–1) · `ScreenScope` + `ScreenModel` + navEvents modifier (1–2) · SwiftUI root with tabs + stacks + `DestinationContent` + Compose bridge (2–3) · `ImageBridge` + `TanukiImage` (2–3) · SwiftUI site-session overlay (2–3) · iOS design tokens: colors, type, base components from the design project (2–3) · strings pipeline (0.5–1) | **11–17** |
| **1. Home** | split `:features:home:logic` · move the For You/Sources toggle into the VM · SwiftUI `HomeView` (segments, continue-reading carousel, new chapters, language groups) · en/pt-BR strings | **3–5** |
| 2. Settings (+ appearance, privacy, categories, downloads, sources) | mostly lists and forms | 6–9 |
| 3. Library | grid + large filter/sort sheet | 5–7 |
| 4. Source browse | paging grid | 3–4 |
| 5. Manga detail | hero, chapter list, selection, 4 sheets, download menu | 8–12 |
| 6. Activity | stats charts (Swift Charts), history with swipe | 5–8 |
| 7. Downloads | queue screen | 3–5 |
| 8. Reader | paged + webtoon, prefetch, decode sizing, scrubber, scroll commands. Some layout logic must move into the VM first | 12–20 |
| 9. Cleanup | delete the Compose bridge, drop Compose from the iOS framework, update CLAUDE.md | 2–4 |
| **Total** | | **≈58–91** |

The order after Home is a recommendation: simple to complex, with the Reader last because it's the
riskiest. Every phase ends with both apps building and the migrated screen at parity.

## Risks

- Every Kotlin bump must wait for SKIE (it hooks compiler internals) and Metro to support it. Check both
  changelogs, and build the iOS framework, not just Android.
- Hybrid period: each Compose leaf is its own composition. Watch memory on deep stacks and
  double back buttons.
- Reader performance in SwiftUI with large images. We may need a `UIScrollView`/`UICollectionView`
  representable instead of `ScrollView`.
- Kotlin/Native gotchas already in memory: NSLog varargs, and WASM host callbacks must not throw.

## Verification per phase

- `./gradlew :androidApp:assembleDebug` and `:shared:embedAndSignAppleFrameworkForXcode` both green.
- Tests: `testAndroidHostTest` + `iosSimulatorArm64Test` for the
  touched modules (ViewModel tests stay in `commonTest`).
- Run on the iOS simulator. Check the migrated screen against the Compose version and the design
  project's `ui_kits/ios/`. Check navigation to and back from Compose leaves, VM clearing on pop (log
  in `onCleared`), and en + pt-BR.

## Phase 0 spike results (2026-10-10)

- **Toolchain:** Kotlin 2.4.10 → **2.4.20** (the version SKIE 0.10.15 lists), Gradle 9.6.1 → **9.8.1**,
  Metro 1.3.2 → **1.4.5**. Metro 1.3.2 crashes the IR phase on the 2.4.20 *final* release
  (`NoSuchMethodError … getValueArgument` in `compat.k2420_beta1`), even though its compatibility table
  lists 2.4.20 from 1.2.0 onward. Kotlin 2.4.21 exists but SKIE doesn't list it, so we stay on 2.4.20.
- **SKIE** is applied in `:shared` (the framework module). The framework exports `:core:domain`,
  `:core:navigation`, `:core:presentation` and, **temporarily**, `:features:home` (marked `ponytail:` in
  `shared/build.gradle.kts`; replace it with `:features:home:logic` in Phase 1).
- **`StateViewModel<S>` → `StateViewModel<S : Any>`.** With an unbounded `S`, SKIE typed `uiState` as a
  flow of `(any HomeState)?`, which adds a phantom `nil` case to every `switch` and makes `.value`
  optional. Every subclass already used a non-null state, so nothing else changed.
- **Verified from Swift** (a throwaway probe typechecked against the built framework with
  `swiftc -typecheck -F shared/build/bin/iosSimulatorArm64/debugFramework`):
  - `for await state in vm.uiState`, typed `HomeState`, and `vm.uiState.value` without suspending.
  - `switch onEnum(of: state)` (`.loading` / `.content(let c)`), exhaustive.
  - `for await event in vm.navEvents` + `onEnum(of: event)` (`.navTo` / `.navBack`).
  - `onEnum(of: destination)` (`.mangaDetail`, `.reader`, `.sourceBrowse`, `.topLevelDestination` →
    nested `onEnum` for the tabs).
  - `NavigationPath.append(MangaDetail)`: Kotlin data classes are `Hashable` in Swift.
  - Plain methods: `vm.onToggleLanguage(lang:)`, `vm.onSourceClick(sourceId:)`.
- **Green:** `:androidApp:assembleDebug`, `testAndroidHostTest`, `iosSimulatorArm64Test` (all modules),
  and a full `xcodebuild` of `iosApp` for the arm64 simulator.
- **Side fix:** `upload-dsyms.sh` and `sentry-release.sh` wrote `$VAR…`. Bash in Xcode's locale reads
  the `…` bytes as part of the name, which fails under `set -u` (`SENTRY_PROJECT\342: unbound
  variable`) and broke every Release iOS build with Sentry credentials. Fixed with `${VAR}…`.
- **Xcode CLI note:** build with `ARCHS=arm64` for the simulator. The generic destination also
  requests x86_64, which Gradle doesn't build.

## Progress log

- 2026-10-10 — Findings + plan written.
- 2026-10-10 — Phase 0 SKIE spike done (see results above).
- 2026-10-10 — Phase 0 `AppStartup` done. The six launch jobs moved out of `App()` into
  `shared/…/AppStartup.kt` (`@SingleIn`, once-per-process `start()`), exposed as `AppGraph.appStartup`.
  The seven graph accessors only `App.kt` used are gone. Callers are the UI entry points, not process
  start, so WorkManager/BGTask launches don't run a foreground update: `MainActivity.onCreate` on
  Android, and `.task { MainViewControllerKt.startApp() }` on the iOS scene, which the SwiftUI root keeps
  unchanged. Side effect: Android no longer re-runs the jobs on every Activity recreation. Verified with
  the Android build and tests, the iOS simulator tests, and a fresh install on the iOS simulator (all 5
  bundled sources installed). Android wasn't run on a device; none was available.
- 2026-10-10 — Phase 0 `UuidNavType` move done. It now lives in `shared/…/navigation/UuidNavType.kt` as
  `internal`, and its two tests moved to `:shared` (`commonTest` + `iosTest`). `:core:navigation` drops
  navigation-compose and declares `kotlinx-coroutines-core` as `api` (`AppNavigator` exposes `Flow`;
  it used to come in transitively). The framework header has no `NavType` any more.
- 2026-10-10 — Phase 0 glue done: `ScreenScope` (Kotlin, `iosMain`) + `ScreenScopeTest` (same instance
  per class, `close()` → `onCleared`, an unregistered ViewModel fails), `ScreenModel` + `Route` +
  `.navEvents` (Swift). A scratchpad probe typechecked a real usage against Home's ViewModel
  (`ScreenModel({ $0.homeViewModel() }, uiState: \.uiState)`, `onEnum(of: model.state)`, `.navEvents`,
  `.navigationDestination(for: Route.self)`), and the Debug app build passes. Runtime behaviour is still
  unexercised: nothing renders it until the SwiftUI root.
- 2026-10-10 — Phase 0 SwiftUI root done. iOS now launches `RootView` (SwiftUI `TabView`, Liquid Glass
  tab bar, one `NavigationStack(path: [Route])` per tab). Every destination renders through
  `ComposeBridge.DestinationViewController`, a Compose `*Route` in a `UIViewControllerRepresentable`.
  - `DestinationContent` (`shared/…/navigation/`) is now the one Destination → `*Route` mapping, and
    `TanukiNavHost` calls it too. `App()` was split into `TanukiComposeHost`, the providers every Compose
    tree needs, plus Android's own root. The page `ImageLoader` is process-wide (one memo), so several
    trees don't each open `page_cache`.
  - Cloudflare/session layer: Compose `SiteSessionHost` in its own non-opaque controller, mounted
    permanently **behind** the `TabView` and brought to the front through the new `onRevealedChange`.
    That keeps the silent-solve rule (occluded but painting) without a SwiftUI overlay. **The planned
    "SwiftUI site-session overlay" Phase 0 item may not be needed;** decide after a real challenge has
    been tested on device.
  - Theme: `IosAppKt.observeThemePreference()` → `.preferredColorScheme`, and the Compose screens follow
    it (checked in dark mode on the simulator). Tab labels come from `iosApp/iosApp/Localizable.xcstrings`
    (en + pt-BR, copied from `:shared`'s `tab_*`), and `pt-BR` was added to the project's known regions.
    Icons are SF Symbols. `AccentColor` is set to the tanuki orange `#E8731C`.
  - `MainViewController()` was deleted, and `MainViewController.kt` renamed to `IosApp.kt` (the Swift call
    is `IosAppKt.startApp()`).
  - Leaves hide the SwiftUI nav bar and tab bar. A `UINavigationController` extension re-enables the
    edge-swipe back gesture that a hidden bar turns off.
  - **Verified:** Android build + host tests, `:shared` iOS tests, a Debug xcodebuild, and the simulator
    launch (SwiftUI tab bar + Compose Home, light and dark, no double status-bar inset).
    **Not verified (no UI automation here):** tab switching, pushing a leaf (tab bar hidden), Compose back
    and edge-swipe back, the ViewModel cleared on pop, and a live Cloudflare reveal.
- 2026-10-10 — Phase 0 iOS design tokens done (`iosApp/iosApp/DesignSystem/`), read from the remote
  design project (`tokens/*.css`, `readme.md`, `ui_kits/ios/README.md`) and cross-checked against
  Android's `TanukiPalette`, which matches it value for value.
  - **Colours:** 22 colorsets in `Assets.xcassets/Tanuki/` (namespaced), each with a light and a dark
    appearance, exposed as `Color.tk.*`. `AccentColor` = the orange token, light `#D9620F` and dark
    `#E8731C`. Deliberately left out: the CSS glass fills/highlights, which only imitate Liquid Glass on
    the web; iOS uses the system `.glassEffect()`.
  - **Type:** `.tkStyle(.display|.title|.headline|.body|.caption|.label|.mono)`, SF Pro at the token
    sizes, weights, line heights and tracking, scaled with Dynamic Type relative to the nearest system
    style.
  - **Spacing/radii/motion:** `TK.Space` (Android's names), `TK.Radius` (the Liquid Glass family:
    container 10, glass card 14, cover 6, sheet 16), `TK.Size.tapMin`, `TK.Motion.ease()` (`--ease-ios` at
    120/200/320 ms).
  - Icons are SF Symbols, per the design readme's glyph vocabulary.
  - **Not built yet:** components. Each one gets built when the first SwiftUI screen needs it (Home), from
    the project's `components/` + `ui_kits/ios/`.
  - **Verified:** a Debug xcodebuild, plus a temporary specimen screen screenshotted on the simulator in
    dark and light (every token resolved and flipped), then reverted.
- 2026-10-10 — Phase 0 images done (see the Decisions bullet "Images"). `ImageBridge.kt` +
  `ImageBridgeTest` (MockEngine + a temporary disk cache: bytes + headers, second load from disk, 404 →
  `null` and not cached). Nuke 13.2.0 + NukeUI were added via SPM, wired like Sentry. `KotlinDataLoader`,
  `ImagePipeline.tanuki` and `TanukiImage` are in `iosApp/iosApp/DesignSystem/`. **Verified live** on the
  simulator with a temporary specimen: a real MangaDex cover rendered, a 404 showed the placeholder, and
  `cover_cache` held exactly the one fetched cover. Calling the Kotlin suspend function from Nuke's
  background task works with no main-thread restriction.
- 2026-10-10 — Phase 0 strings pipeline done (see the Decisions bullet "Strings"). All 9 modules
  generated, with en + pt-BR complete (662 strings, 60 plurals). The hand-made `Localizable.xcstrings`
  is replaced by the generated one. **Verified live** with a temporary specimen in pt-BR and en: format
  args, plural one/other, the `\'` escape, cross-table keys and a default-table `LocalizedStringKey`.
- **Phase 0 complete**, apart from the SwiftUI site-session overlay, which is on hold: the Compose layer
  behind the root may be enough; decide after a live Cloudflare test on a device. The hand checks of
  the SwiftUI root are still pending (tab switching, leaf push/back, edge swipe, a Cloudflare reveal).
  Next: **Phase 1 — Home** (split `:features:home:logic`, move the For You/Sources toggle into the VM,
  `MangaCover` + `HomeView` in SwiftUI, add Home's branch to the destination switch).
- 2026-10-10 — **Phase 1 — Home done** (pending commit and the hand checks below).
  - **Tokens first:** the iOS tokens were regenerated from the design-system artifact (see the Decisions
    bullet "Design tokens"). The Phase 0 colorsets and `TanukiSpacing.swift` are gone, replaced by 31
    generated colorsets, `TanukiColors.swift` and `TanukiTokens.swift`. Type now maps onto system
    Dynamic Type styles, and `TanukiMotion.swift` holds enter/exit/reduced.
  - **`:features:home:logic`** (nested under `features/home/logic`) holds `HomeViewModel`, `HomeState`
    and the test, in the same package. The framework exports it instead of the whole Compose module,
    which the SKIE spike had done, and the header has `HomeViewModel`, `HomeSegment` and `Elapsed`, with
    no Home composables.
  - **Logic moved out of the UI:**
    - the For You/Sources toggle is `Content.segment` + `onSelectSegment`, replacing `rememberSaveable`.
      It no longer survives Android process death; add `SavedStateHandle` if that matters.
    - the relative caption is `NewChapterItem.discoveredAgo: Elapsed`, from
      `:core:presentation`'s `elapsedSince`, which Activity's `HistoryRow` should adopt in Phase 6.
    - Android's `HomeScreen`/`NewChapterRow` now read both from state. The segmented control renders
      with the content, not during loading.
  - **SwiftUI:**
    - `Features/Home/HomeScreen.swift`: a large title and one `List` with `Picker(.segmented)`, a
      continue-reading carousel of `MangaHeroCard`s, New Chapters rows, and language `DisclosureGroup`s
      bound to the ViewModel.
    - `DesignSystem/Components/`: `MangaCover` (5:7, a `cover-*` fallback via a port of Kotlin's
      `String.hashCode`, so slots match Android's), `MangaHeroCard` and `SourceIcon`.
    - `Navigation/DestinationView.swift` is the destination switch: Home is native, everything else
      goes through the Compose bridge (which now hides the nav bar itself).
  - **Verified:**
    - `:core:presentation` tests (`ElapsedTest` 2/2), `:features:home:logic` tests (6/6 on host and
      iOS), `:shared:iosSimulatorArm64Test`, the framework link, `:androidApp:assembleDebug`, and a
      Debug xcodebuild.
    - Live on the simulator: For You empty state, plus Sources with the first group expanded (driven
      by a temporary auto-select, since reverted), in dark and light. The real source icons loaded.
  - **Not verified by hand:**
    - For You with content (the simulator library is empty);
    - tapping a source → the Compose browse leaf → back (button and edge swipe);
    - Android on a device.
  - **Out of scope, noted:** the artifact's tab set (Library/Updates/History/Browse + search) differs
    from `TopLevelDestination`; Android's `:assets:designSystem` is still on the old palette.

  Next: **Phase 2 — Settings**.
