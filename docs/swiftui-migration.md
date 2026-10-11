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
| **Value classes reach Swift as an untyped `id`** (found in Phase 2): `Text` (`Category.name`, `Manga.title`, `SourceMeta.name`) can't be read from Swift | `:core:domain` models | Swift-facing state carries plain `String`s: map to a feature item type in the ViewModel (`CategoryItem`, `StorageSeriesItem`), as Home's items already did |
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
| 3. Library | grid + large filter/sort sheet; category switcher + search (see "UI/UX redesign") | 5–7 |
| 4. Source browse | paging grid → Source home redesign (rails + "See all" grid; see "UI/UX redesign") | 3–4 (+ shared contract work) |
| 5. Manga detail | hero, chapter list, selection, 4 sheets, download menu; one-scroll redesign (see "UI/UX redesign") | 8–12 |
| 6. Activity | stats charts (Swift Charts), history with swipe; merged history rows (see "UI/UX redesign") | 5–8 |
| 7. Downloads | queue screen | 3–5 |
| 8. Reader | paged + webtoon, prefetch, decode sizing, scrubber, scroll commands. Some layout logic must move into the VM first | 12–20 |
| 9. Cleanup | delete the Compose bridge, drop Compose from the iOS framework, update CLAUDE.md | 2–4 |
| **Total** | | **≈58–91** |

The order after Home is a recommendation: simple to complex, with the Reader last because it's the
riskiest. Every phase ends with both apps building and the migrated screen at parity.

## UI/UX redesign to fold into the migration (planned 2026-10-11)

Decided from a review of iOS screenshots: when a slice migrates, rebuild it **to the redesign**, not as a
1:1 port of the Compose screen. Shared (Kotlin) parts land first so Android benefits too.

- **Review doc (Claude Docs):** https://claude.ai/code/artifact/fe96ff11-736e-42d6-86d3-629814780240
- **Mockups (Design canvas, iOS Day + Android Night):** https://claude.ai/artifact/B2724pSPBJh9VBcJdgPb4r
  (`Main` = iOS Source home, `MangaDetail`, `History`, `SourceAndroid`, `Library`, `Statistics`). Covers in the mockups are the
  design system's fallback colours; list contents are placeholders.

### Source browse (Phase 4) → "Source home"

- Large title (source name) + `.searchable` field instead of the magnifier button and the
  Popular/Latest segmented tabs.
- Horizontal rails, each with "See all": **Popular**, **Latest updates** (cover + "Ch. 222 · 15 h ago"),
  plus any extra listing the source declares (e.g. **New series**), then an **All series** grid preview.
- **"In library"** marker (glyph + words) on covers already in the library.
- "See all" / search push today's paged grid as its own destination.
- Shared work, before the SwiftUI screen:
  - [x] `SourceListing.NEW` (`"new"`) + `listing_new` in en and pt-BR; MangaDex declares it.
        (`TRENDING` not added: no source can sort that way yet.)
  - [x] `MangaListing`: optional `latestChapter`, `latestChapterName`, `updatedAt`, `genres`.
  - [x] Optional `source_home` export (one call for several listings), with a per-listing fallback.
  - [x] New `SourceHomeViewModel` (`StateViewModel`): one rail per declared listing + the default
        catalog, each loading/failing/retrying on its own, plus an in-library flag per item.
        `SourceBrowseViewModel` stays as the "See all"/search grid behind `SourceCatalogGrid`.
  - [x] Android: snapping hero `LazyRow` + cover `LazyRow` rails over the new state.

### Manga detail (Phase 5)

- [x] Bug: synopsis shows HTML entities (`Murim&#039;s`). Decoded host-side in `Mappers.kt`.
- [x] Bug: white strip under bottom content on Compose-rendered iOS screens; the bridge now ignores both
      vertical safe areas.
- [x] One scrolling page, de-crowded (both platforms): hero with ONE metadata line ("Ongoing · 143 ch.
      · ★ 8.2") → Reading status + Categories → synopsis (3 lines) → one merged tag row ("+N") →
      recommendations (only when not in the library) → chapters. Full synopsis, all tags, AniList
      rating, characters, reviews, library-series recommendations and the Source / Reading mode rows
      live in an **About** sheet ("Show more" or the tag row). Android dropped its Chapters/Info tabs.
- [x] Primary action: floating `.glassProminent` "Read Ch. N" capsule sized to its label (not a
      full-width bar), in `.safeAreaInset(edge: .bottom)`; "Remove from library" lives in the Reading
      status menu; "Matched on AniList" moved out of the hero into the ⋯ menu.
- [x] **Phase 5b — metadata enrichment** (AniList + MangaDex covers), per
      `docs/design-manga-metadata.md`.

### Activity (Phase 6)

- [x] History: merge entries of the same series on the same day into one row
      ("Eleceed · 4 chapters · Ch. 418–421"), grouped under day headers. Done in the ViewModel.
- [x] History/Statistics (and Week/Month/Year) as the system segmented control; no solid-accent selected
      segment (design system: selection = `accent-tint`, accent only for the one primary action).

### Library (Phase 3)

- [x] ~~Category switcher under the title~~ — dropped (2026-10-11): categories stay in the sort &
      filter sheet (multi-select + any/all), and the filter/sort are now remembered across launches.
- [x] `.searchable` instead of the magnifier button; native toolbar buttons;
      `.tabBarMinimizeBehavior(.onScrollDown)`.
- [x] Cover size setting (Appearance → Library covers: Small / Medium / Large), both platforms.
- [ ] Select mode (mockup's "Select" button) + bulk actions — deferred.

### Statistics (Phase 6)

- [x] Day-streak tile: when the current streak is 0, show the best streak (or hide the tile).
- [x] Label every stat tile with its period ("979 · all time" vs "64 · this month"); today the two
      "Chapters read" tiles look identical.
- [x] Reading curve: bars per day instead of a smoothed line (the spline invents in-between values),
      a few date labels on the x axis, value on tap.
- [x] "Most read" rank arrows need words or a number ("↑2 this week"); colour alone carries no meaning.

### Home (done in Phase 1, polish)

- [ ] Sentence case for section headers: "Continue reading", "New chapters".
- [ ] Continue-reading card: progress bar + "Resume ch. N"; keep it solid (no glass on cards).
- [ ] Consider moving continue reading to `.tabViewBottomAccessory` so it's reachable from every tab.
- [ ] Optional "Popular in your sources" rail on For You, reusing the source rail.

### Tab bar (decision needed)

- [ ] Outline SF Symbols at rest, `.fill` only when selected (`TabView` does this if given outline
      symbols); today every icon looks filled.
- [ ] Decide the tab set: today Library / Home / Activity / Settings + a **Search** tab
      (`Tab(role: .search)`, added with global search on 2026-10-11); the design system specifies
      Library / Updates / History / Browse + search.

### Settings (done in Phase 2, polish)

- [ ] Neutral icon tiles (dark glyph on a neutral tile) instead of accent-orange tiles; accent is for
      state and the one primary action, not decoration.
- [ ] Optional: crash reporting as an inline `Toggle` in the index instead of a pushed page.

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
- 2026-10-10 — **Phase 2 — Settings done** (pending commit and the hand checks below).
  - **`:features:settings:logic`** holds all six ViewModels (index, Appearance, Privacy, Categories,
    Downloads settings, Sources), in their existing packages, and is exported. `ScreenScope` has an
    accessor for each.
  - **State moved out of the UI:**
    - the index's row taps are `SettingsEntry` + `onEntryClick` (`navigateTo`), and "open queue" is
      `onOpenQueue`;
    - every dialog is ViewModel state: Categories' create and delete dialogs, Sources' remove dialog and
      its "also delete downloads" option, and Downloads' delete-all confirm;
    - Privacy became a `StateViewModel<PrivacyState>`, so Swift observes one flow;
    - Downloads derives `categoryOptions` (selected or not), `needsCategoryPick` and `listedSeries`
      (the top-5 cap), so no UI does set membership or caps lists.

    Android's screens read the same state. Only the category-name text buffer stays local.
  - **Value-class finding:** `Text` reaches Swift as `id` (see Findings), hence `CategoryItem` and
    `StorageSeriesItem`.
  - **SwiftUI** (`Features/Settings/`):
    - the index has accent symbol tiles, value subtitles and chevrons;
    - Appearance is a segmented picker; Privacy is a `Toggle` plus a reset button with footers;
    - Categories uses swipe-to-delete, a `+` toolbar button and alerts;
    - Sources has sections with install/remove and busy/failed rows. iOS alerts can't hold a switch, so
      Android's "also delete downloads" switch is a second destructive button;
    - Downloads settings uses inline pickers with hints, category checkmarks, Wi-Fi only, storage via
      `ByteCountFormatter` (decimal like Android) and a separate `danger` delete-all group;
    - alert bindings are state-driven only: every button calls the ViewModel itself, because SwiftUI's
      write-back could clear a pending item before the confirm action reads it;
    - `DestinationView` is now an exhaustive switch: Home + Settings native, the rest on the bridge.
  - **Verified:**
    - `:features:settings:logic` tests (`CategoriesViewModelTest` 3, `DownloadSettingsStateTest` 2, on
      host and iOS), the framework link, `:androidApp:assembleDebug`, and a Debug xcodebuild.
    - Live on the simulator: the index in dark and light, and every leaf pushed through
      `onEntryClick` → `navEvents` (temporary auto-push, reverted). Appearance, Privacy, Sources (real
      installed sources) and Downloads settings rendered correctly.
  - **Not covered:**
    - ViewModel tests for Sources, Downloads and the index: their use cases are `sealed` and would need
      about eight repository fakes. Categories covers the dialog pattern end to end.
    - By hand: create/delete a category, install/remove a source (both buttons), delete all downloads,
      a theme switch flipping the app, and Android on a device.

  Next: **Phase 3 — Library**.
- 2026-10-11 — UI/UX review of the iOS screens. Planned redesigns for Source browse (Source home with
  rails), Manga detail (one scroll) and History (merged rows), plus two bugs (HTML entities in
  synopsis, white bottom strip on Compose-bridged screens). Recorded in "UI/UX redesign to fold into the
  migration", with the review doc and mockup links. No code changed. Same day: added Library,
  Statistics, Home, tab bar and Settings polish items to that section.
- 2026-10-11 — **Phase 3 — Library done** (pending commit and the hand checks below).
  - **Domain/data:** `LibraryFilter.categories` → `categoryIds: Set<Uuid>` (`ObserveLibrary` only ever
    read ids; membership comes live from `observeLibraryCategories()`). New `LibraryPreferences`
    (filter + sort) persisted in DataStore via `SettingsRepository.observe/updateLibraryPreferences`
    (atomic transform inside `edit`) and the `ObserveLibraryPreferences`/`UpdateLibraryPreferences`
    use cases. New `LibraryGridSize` setting (Small/Medium/Large, default Medium) with its own use cases.
  - **`:features:library:logic`** holds `LibraryViewModel`, exported; `ScreenScope.libraryViewModel()`.
    State is one `LibraryState.Content` (items as plain `LibraryItem`, `CategoryChip` with name/count,
    `sheetOpen`, `gridSize`, derived `filterActive` and `empty: LibraryEmpty`). Filter/sort come from the
    store; ids of deleted categories are dropped before filtering. The sort "tap again to flip" rule
    moved into `onSortModeClick`.
  - **Appearance:** `AppearanceViewModel` is now a `StateViewModel<AppearanceState(theme, gridSize)>`;
    both platforms show a "Library covers" segmented control. Grids map the size to a minimum cover
    width (90/114/150) and stay adaptive.
  - **Android:** same layout over the new state; the empty view now distinguishes "filters hide
    everything" from "library is empty" (matters now that filters persist).
  - **SwiftUI** (`Features/Library/`): large title, `.searchable`, a toolbar filter button (filled when
    filters are on), adaptive `LazyVGrid` of `LibraryCoverTile` (title over `coverScrim`, accent unread
    badge), `ContentUnavailableView` empty states, and `LibrarySheet` (Form: sort rows, status picker,
    downloaded/publication segmented, categories with checkmarks + any/all; Reset and a
    `.glassProminent` "Show N titles"). `LibraryItem` clashes with SwiftUI's
    `DeveloperToolsSupport.LibraryItem` — write `Shared.LibraryItem`.
  - **Verified:** `:core:data` (`SettingsRepositoryImpLibraryTest` 4) and `:core:domain` on iOS,
    `:features:library:logic` (11, host + iOS), `:features:settings:logic` host tests,
    `:androidApp:assembleDebug`, the simulator framework link, a Debug xcodebuild, and the Library tab
    live on the simulator (empty library: title, search, filter button, empty state).
  - **Sheet refinement (same day):** the iOS sheet is now a Sort | Filter segmented switch over one
    pane at a time, on the system Liquid Glass sheet (no painted background, medium/large detents).
    Reading status and categories are `FilterChip`s in a `FlowLayout` (new in
    `DesignSystem/Components/`). The open tab is VM state (`LibrarySheetTab`, `onSheetTabChange`),
    which Android's sheet now reads too.
  - **Not verified by hand:** the grid with content and each cover size (the simulator library is
    empty), the sheet's controls, filters surviving a relaunch, tap → Compose manga detail → back, and
    Android on a device.

  Next: **Phase 4 — Source browse**.
- 2026-10-11 — **Phase 4 — Source browse → Source home done** (pending commit and the hand checks below).
  - **ABI (additive, `ABI_VERSION` stays 4; `wire.rs` ↔ `Wire.kt` changed together):**
    - `Manga` gains optional `latest_chapter`, `latest_chapter_name`, `updated_at` (epoch ms) and
      `genres`; unknown/invalid values degrade to absent in `Mappers.kt`.
    - `Listing::New` / `SourceListing.NEW` (`"new"`).
    - Optional `source_home` export → `SourceHomeWire { sections: [{ listing, entries }] }`. The SDK's
      default answers `unsupported`; `SourceLoader.fetchSourceHome` (default `null`) returns `null` for
      that, and `GetSourceHome` falls back to one `manga_list` per declared listing (plus the default
      catalog for "All series"), each rail failing on its own. An older bundle without the export
      traps, which also falls back.
    - Sources: MangaDex declares `New` (`order[createdAt]`) and sends genres (its `lastChapter` is the
      *final* chapter, so no latest-chapter field). Manhastro sends genres, `updated_at`
      (`ultimo_capitulo`) and, on `/lancamentos`, the release's chapter. MangaFire is untouched (its
      list API is signed; field names unverified). `hello` implements `home()` as the conformance
      fixture. Every source's `version` was bumped so installed bundles update.
  - **Domain/data:** `GetSourceHome`, `ObserveLibraryKeys` (Room: library ⋈ manga by source →
    `source_manga_key`), `MangaRepository.observeLibraryKeys`. `GetSourceMeta`, `BrowseSource` and
    `CacheSourceManga` are plain interfaces now, so ViewModels can be tested with fakes.
  - **`:features:source:logic`** (exported): `SourceHomeViewModel` (assisted) and the moved
    `SourceBrowseViewModel` (now assisted with an initial listing/query, plus Swift-friendly `tiles` and
    `onItemClick`). `ScreenScope.assistedViewModel` + accessors `sourceHomeViewModel`,
    `sourceCatalogViewModel`. New destination `SourceCatalogGrid(sourceId, listing, query)`
    (`SourceCatalog` is taken by a domain interface in Swift). `SourceBrowse` now renders Source home.
  - **SwiftUI** (`Features/Source/`): `SourceHomeScreen` (large title + "en · 3 listings" subtitle,
    `.searchable` submitting to the catalog, Popular as a snapping `MangaHeroCard` carousel, cover
    rails with "Ch. N · 15 h ago" on Latest, an All series preview, per-rail retry) and
    `SourceCatalogScreen` (adaptive grid, infinite scroll, search). `LibraryCoverTile` became the
    shared `DesignSystem/Components/CoverTile` (unread badge, "In library" pill).
  - **Swift naming gotchas:** Kotlin `SourceListing.NEW` reaches Swift as `.theNew`; a second Kotlin
    `SourceItem` (settings) forced `CatalogItem`.
  - **Verified:** `GetSourceHomeTest` 3, `SourceHomeViewModelTest` 5 (host + iOS), the `hello`
    `source_home` engine test on Chicory and wasmi, `:androidApp:assembleDebug`, the framework link,
    a Debug xcodebuild. Live on the simulator with real MangaDex: Popular hero carousel with genres,
    Latest rail, All series grid, and the "New series" catalog.
  - **Not verified by hand:** the "In library" pill (empty simulator library), Manhastro's chapter
    captions, search submit, tapping into a manga, per-rail retry, and Android on a device.

  Next: **Phase 5 — Manga detail**.
- 2026-10-11 — **Source home follow-ups.**
  - **Distinct listings.** "All series" (the default catalog) duplicated Popular on MangaFire, MangaDex
    and Hipercool; it is now A–Z everywhere. Each site's sort options were checked live (MangaFire via a
    scratch VRF-signing harness, Hipercool via its tRPC sort enum):

    | Source | Popular | Latest | New | All series |
    |---|---|---|---|---|
    | MangaDex | `followedCount` | `latestUploadedChapter` | `createdAt` | `title` asc |
    | MangaFire | `views_30d` | `chapter_updated_at` | `created_at` (new) | `title` asc |
    | Hipercool | `popular` | `recent` | `newest` (new) | `alphabetical` |
    | Manhastro | `/rank/diario` | `/lancamentos` | — | `/dados` |

    MangaFire also sends `latestChapter` now (its update time is relative text, and sources have no
    clock). `SourceBrowseViewModel`'s "no listing = first tab" fallback is gone, and so is Android's tab
    row: each listing has its own "See all".
  - **Sensitive-content filter** (Settings → Sources, off by default; `show_sensitive_content` in
    DataStore):
    - Source level: `SourceMeta.nsfw` hides adult-only sources (Hipercool) from Home.
    - Content level: the host sends `allow_sensitive` in `MangaListRequest`/`SourceHomeRequest`
      (additive). MangaFire adds `content_rating[]=safe,suggestive` (78,335 → 64,825 titles), MangaDex
      adds the explicit ratings only when allowed (its chapter feed always takes every rating), and
      Manhastro (now `nsfw: false`, a mixed catalog) drops `adulta` titles.
    - Settings → Sources follows the switch too (`CatalogSource.nsfw`, from installed metadata).
      Not-installed sources aren't filtered: their metadata isn't known before install.
  - **Cover size everywhere:** the source catalog grid follows Appearance → Library covers like the
    library grid. The size → minimum width mapping lives once in `:core:presentation`
    (`LibraryGridSize.minCoverWidth`), read by Compose and Swift alike.
  - **Search-bar glitch:** the catalog and Source home keep one `ScrollView` for every state (spinner
    and errors inside it), and the catalog's search drawer is pinned. Swapping the scroll view under
    `.searchable` made the field collapse and re-show.
  - **Dev note:** `DevSourceInstaller` rewrites bundles asynchronously on launch, so a screen opened in
    the first instant (e.g. a temporary auto-push) can still run the previous bundle.
- 2026-10-11 — **Phase 5a — Manga detail in SwiftUI done** (pending commit and the hand checks below).
  - **`:features:manga:logic`** (exported) holds `MangaViewModel`/`MangaState`;
    `ScreenScope.mangaViewModel(mangaId:)`. `MangaState.Content` adds Swift-facing projections built
    lazily once per state — `header: MangaHeader` and `rows: List<ChapterRow>` (plain values, download
    state + progress, started page) — plus `resumeNumberLabel`, and `MangaCategoryChip.name`.
  - **Sheets are ViewModel state** (`sheet: MangaSheet?`, `onOpenSheet`/`onDismissSheet`): categories,
    chapter filter, reading status, reading mode, confirm-delete-downloads. Picking a status/mode or
    deleting all closes it in the ViewModel; Android's four sheets read it too (no visual change).
  - **HTML entities** decoded once in `core/source/.../abi/Mappers.kt` (`decodeHtmlEntities`, numeric +
    common named) for title, original title, author and description.
  - **SwiftUI `Features/Manga/MangaScreen.swift`**: cover-tinted hero, glass Reading-status menu +
    Categories, clamped synopsis, Source/Reading-mode rows, chapter header with filter/sort, rows with
    unread dot / date · group (or "Page N") / download control (menu to delete when downloaded,
    progress ring, retry), long-press selection with a glass action bar and select-all/above/below,
    floating "Read Chapter N", toolbar download menu + share, pull to refresh, refresh-failed alert.
  - **Bridge**: `ComposeDestinationView` ignores the bottom safe area too (the white strip).
  - **Verified:** `HtmlEntitiesTest` 3, `MangaContentProjectionTest` 2 (host + iOS),
    `:androidApp:assembleDebug`, framework link, Debug xcodebuild; live on the simulator with MangaDex
    titles (hero, rows with dates and groups, multi-group filter button, Read button).
  - **Not verified by hand:** each sheet, selection mode, downloads, the reader round trip, Android on a
    device. No ViewModel test for `MangaViewModel` itself (its use cases are sealed; the projection and
    the sheet logic are small).
  - **Metadata (5b):** feasibility confirmed and designed in `docs/design-manga-metadata.md`.

  Next: **Phase 5b — metadata**, or **Phase 6 — Activity**.
- 2026-10-11 — **Phase 5b — metadata enrichment done** (pending commit and the hand checks below).
  - **Identity:** optional ABI `anilist_id`/`mal_id` on `Manga`; MangaDex fills them from `links.al/mal`,
    MangaFire from `anilistId`/`malId` (strings on its API, parsed leniently). Versions bumped.
  - **Storage (Room v14, `Migration13To14`, SQL checked against the exported schema):**
    `manga_tracker_link` (one per manga row: ids, `matched_by`, `use_source_info`, `cover_choice`,
    `mangadex_id`) and `series_metadata` (one per **series**, keyed by AniList id, JSON payload,
    `fetched_at`). Two copies of One Piece from two sources = two links → one metadata row.
  - **`:core:metadata`** (new): `AniListProvider` (one GraphQL query: media + characters + reviews +
    recommendations + tags; search), `MangaDexProvider` (volume covers), own Ktor client per
    platform, a spacing rate limiter. Exposed to the app graphs via `api` in `:shared`.
  - **`EnrichManga`** (background, never throws): link from the source's ids **once per series**
    (recorded even when the source has none), then fetch only when the cache is older than **7 days**.
    `MangaViewModel` launches it as its own coroutine alongside the chapter/details syncs; the state
    folds `ObserveMangaMetadata` in starting from empty, so the page never waits on AniList.
  - **Precedence** in `MangaState.Content.header`: matched + not `useSourceInfo` → AniList title,
    synopsis, cover (user's pick first), status, genres; chapters always from the source.
  - **UI:** iOS — score badge, "Matched on AniList · Change", genre chips, Tags (spoilers blurred),
    Characters rail, Reviews, Recommendations rail (opens AniList), cover picker (long-press cover or
    ⋯ menu), match search sheet, "Use source info" / unlink in the ⋯ menu. Android — a compact
    metadata block on the Info tab with the "Use source info" switch.
  - **Verified:** `ProvidersTest` 3 (MockEngine), `EnrichMangaTest` 5 (link once, TTL skip, two copies
    share one row and one fetch, stale refetch, failure swallowed), precedence test, host + iOS;
    `:androidApp:assembleDebug`, framework link, Debug xcodebuild. Live: a MangaDex title matched via
    its ids and filled in (score 7.9, AniList cover, genres, tags) after the page loaded; the DB held one
    link + one metadata row, and a relaunch + reopen left `fetched_at` unchanged (served from cache).
  - **Deferred:** the chosen/AniList cover in library and history lists (needs a resolved-cover column
    joined into the library query), recommendation taps searching installed sources (needs global
    search), Android's full rails, MyAnimeList's own score.
  - **Not verified by hand:** the cover picker and match search sheets, unlink, a MangaFire title,
    offline open.

  Next: **global search** (see below), or **Phase 6 — Activity**.
- 2026-10-11 — **Manga detail de-crowded** (from the 17-09-42 recording: chapters 2+ screens down,
  genres and source shown twice, 5-line hero, Read bar over content).
  - **Shared state (`MangaState.Content`)**: `heroLine` (status/chapter count/rating pieces, no source,
    no match line), `tags` (header genres + AniList tags by rank, case-insensitive dedup, spoilers
    flagged), `tagRow` (first 3 non-spoiler tags + overflow count), `recommendationsPlacement`
    (`AboveChapters` when not in the library, `AboutOnly` when it is, `None`), and `MangaSheet.About`
    on the existing `sheet` input flow. Neither UI orders sections itself.
  - **iOS**: hero → actions → synopsis ("Show more" → About) → tag row (tap → About) →
    recommendations if `AboveChapters` → chapters; About sheet (detents) with full synopsis, AniList
    rating, all tags (spoilers blurred), characters, reviews, recommendations if `AboutOnly`, Source /
    Reading mode rows. Read button: content-sized `.glassProminent` capsule, "Read Ch. N".
  - **Android**: Chapters/Info tabs removed; one `LazyColumn` (synopsis, tag row, recommendations if
    `AboveChapters`, chapters) under the collapsing hero, which now shows the merged title/cover and the
    one-line `heroLine`; the former Info content is an M3 `ModalBottomSheet` About sheet.
  - **Strings** (en + pt-BR): `manga_about_title`, `manga_tags_more`, `manga_chapter_count_short`,
    `manga_read_chapter_short`, `manga_about_rating`.
  - **Verified:** `MangaContentProjectionTest` 6 (tag merge/dedup + spoilers + overflow, placement in
    and out of the library, hero line), host + iOS; `:androidApp:assembleDebug`, framework link,
    Debug xcodebuild. Live: a not-in-library series shows recommendations above the chapters; after
    adding it to the library (DB row checked) they leave the page and chapters follow the tag row
    within the first screen; the About sheet opens with the full synopsis and the AniList rating.
  - **Not verified by hand:** Android on a device (layout compiles; the collapsing hero over a single
    list is unchanged code), the About sheet's lower sections on a series with many characters.

- 2026-10-11 — **Metadata follow-ups** (owner's rule: what the user sets wins everywhere).
  - **List covers:** `manga.cover_override` (Room v15, `Migration14To15`) holds the user's pick, else the
    metadata cover when preferred, else null (the source's `cover_url`). `MangaCover.url` carries it, so
    library, history, home and updates show it with no UI change; `sourceUrl`/`sourceHeaders` keep the
    source's cover for the source syncs, which can no longer undo a pick. `ApplyMangaMetadata` writes it
    after every enrichment and every metadata edit.
  - **Reading mode from metadata:** `suggestedReadingMode` — "Long Strip" tag or KR/CN/TW → Webtoon,
    JP → right to left. `manga.reading_mode_origin` (`Default|Metadata|User`); picking a mode marks it
    `User` and `MangaDao.suggestReadingMode` never touches those rows. AniList query adds
    `countryOfOrigin`.
  - **Per language:** `series_metadata` keyed `(anilist_id, lang)`, `manga_tracker_link.lang` = the
    source's language. Outside English, title/synopsis come from MangaDex's text in that language
    (`MangaDexProvider`, renamed from `MangaDexCoverProvider`, also `LocalizedTextProvider`), else null →
    the source's own. ABI: optional `mangadex_id`; MangaFire (v6) fills it from `links.md`.
  - **Smoother page:** the metadata flow no longer starts from an empty placeholder, so a cached series
    opens with its metadata in the first frame; a first enrichment fades in (iOS `.animation` on the
    metadata, Android `Crossfade` on hero/synopsis + `animateItem`). iOS chapter rows animate on
    sort/filter/new chapters (Reduce Motion: short crossfade).
  - **Verified:** domain `ApplyMangaMetadataTest` 5 + `EnrichMangaTest` 8 (per-language rows, MangaDex
    text or none, cover/mode reach the row, user mode kept), `CoverOverrideMappingTest` 2, MangaFire
    engine test for the ids; v15 migration SQL matches `15.json`; `:androidApp:assembleDebug`, Debug
    xcodebuild. Live (temporary startup hook, removed): MangaFire pt-BR One Piece and Blue Lock got
    MangaDex's Portuguese synopsis; a manhwa MangaDex has no pt-BR text for kept MangaFire's (English).
  - **Known gaps:** MangaFire's own synopsis is English, so a pt-BR series MangaDex hasn't translated
    still reads in English. Links made before v15 from MangaFire have no MangaDex id (re-link to fill).
    Not verified by hand: list covers and the fade on a device/Android.
- 2026-10-11 — **Global search** (both platforms; owner's choices: a Search tab, rails per source,
  recommendation taps search).
  - **Domain:** `SearchSources(query, sourceIds)` — a `channelFlow` fan-out over `BrowseSource`, emitting
    each source's `SourceSearch` (`Loaded`/`Failed`) as it answers; 20 s timeout per source, at most 4 in
    parallel (each engine call holds an IO thread), a failure stays in its own rail.
  - **`:features:search:logic`** (exported to iOS) — `GlobalSearchViewModel` (`StateViewModel`, assisted
    `initialQuery`): library matches live as you type (`ObserveLibrary`); sources searched on submit
    only (no per-keystroke requests to every site), adult-only sources skipped unless sensitive content
    is on; results held in the ViewModel so coming back from a series doesn't search again; retry per
    rail, "See all" → `SourceCatalogGrid(sourceId, query)`, result → cache + Manga detail.
  - **Navigation:** `TopLevelDestination.Search` (the tab) and leaf `GlobalSearch(query)`;
    `MangaViewModel.onRecommendationClick` pushes the latter, so AniList recommendations now look the
    series up in your sources instead of opening AniList.
  - **iOS:** `Tab(value: .search, role: .search)` — its `.searchable` field sits in the Liquid Glass tab
    bar; `GlobalSearchScreen` draws the library rail and one rail per source with `CoverTile`.
    **Android:** `:features:search` `GlobalSearchRoute` (top bar with search open, `LazyColumn` of
    `LazyRow` rails, `animateItem`), a Search bottom-bar item (the design system's existing search glyph,
    made public — no Material Symbols dependency yet).
  - **Strings** (en + pt-BR): `search_*` in `:features:search`, `tab_search` in `:shared`.
  - **Verified:** `SearchSourcesTest` 3 (streams fastest first, failure + timeout isolated, parallel cap),
    `GlobalSearchViewModelTest` 5 (typing filters only the library, submit searches shown sources minus
    nsfw, initial query + per-rail retry, results survive resubscription, result/See all navigation);
    `:androidApp:assembleDebug`, Debug xcodebuild. Live (temporary auto-open, removed): the search tab sits
    apart in the glass tab bar; "dress" filled MangaDex pt-BR/en and MangaFire rails; "youth" showed the
    library rail and the "In library" pill on the MangaDex copy.
  - **Not verified by hand:** Android on a device, a recommendation tap end to end, Retry with the
    network off, the empty-field prompt.
- 2026-10-11 — **Phase 6: Activity** (SwiftUI; the redesign applied on both platforms; owner's choices:
  drop the distribution donuts, Android adopts the rework).
  - **`:features:activity:logic`** (exported to iOS): `ActivityViewModel` + a Swift-friendly
    `ActivityState` (plain Strings/ints, no domain `Manga`): `tab` (moved out of `rememberSaveable`),
    `HistoryDay`s of merged `HistoryRow`s (one per series per local day: count, chapter range, latest
    time, resume chapter), `StatsState` (all-time chapters/series/current + best streak; the period's
    chapters, reading days of days so far, bars per day — per month for a year — and the top 5 with a
    `RankChange` `New | Same | Up(n) | Down(n)`). Delete removes every read a row merged.
  - **Domain:** `ReadingStats` drops the distribution/category shares (and their manga/category reads),
    gains `bestStreak`; `MostReadManga.trend` → `previousRank`.
  - **iOS:** `Features/Activity/ActivityScreen.swift` — segmented History/Statistics, day sections with
    swipe Delete/Series, all-time and period tiles, Swift Charts `BarMark` with `chartXSelection`
    ("Oct 6 · 18 chapters"), Most read with rank words. The tab no longer uses the Compose bridge.
  - **Android:** day headers + merged rows, bar chart (Canvas, tap for the value) replacing the spline,
    labelled tiles + best/current streak, rank words; `ReadingCurveChart`/`ReadingDistributionDonut`
    and the relative-time captions deleted.
  - **Strings** (en + pt-BR): day labels, chapter range, streaks, period tiles, per day/month, rank words;
    distribution, category and relative-time keys removed.
  - **Verified:** `ActivityViewModelTest` 8 (merge/range/latest time, day split, resume + delete-all,
    tab state + rank words + all-time, period counts and bars, lazy subscription, period switch,
    teardown), `ReadingStreakTest` 2 + `LeaderboardRankTest` 1; `:androidApp:assembleDebug`, Debug
    xcodebuild. Live with 8 temporary reads (removed afterwards): History showed Today / 2 days ago / a
    date with "3 chapters · Ch. 1–3" rows; Statistics showed the all-time tiles, "8 chapters read this
    month · of 11 days with reading" and day bars.
  - **Not verified by hand:** Android on a device, swipe actions, bar selection by touch, Year view, pt-BR.
  - **Follow-ups (same day):** the chart spans the whole period (empty bars for days to come, so a week's
    first day isn't one full-width bar) and has its own margins (axis label and caption were clipped);
    Activity rows use `surfaceRaised` like Home/Settings. **Home → New chapters** now merges the same way:
    one row per series per day of discovery ("3 chapters · Ch. 418–421"), tap opens the earliest
    (`NewChapterRowsTest`); not seen live — the simulator has no pending chapters.

- 2026-10-11 — **Phase 7: Downloads queue** (SwiftUI; a parity port, no redesign planned).
  - **`:features:downloads:logic`** (exported to iOS): `DownloadsViewModel` moved out of the Compose
    module; `DownloadsState.series` is now plain `DownloadSeriesItem`/`DownloadChapterItem` (title, cover,
    failed count; `numberLabel`/`name`, `failed`/`error`, `progress`, pages) instead of domain
    `Manga`/`Chapter`, whose `Text` reached Swift as `id`. Android's rows read the same items.
  - **iOS:** `Features/Downloads/DownloadsScreen.swift` — status line (count, or pause/Wi-Fi hold with a
    symbol), foreground-only footnote, a section per series (cover header opens the series, trash clears
    it), chapter rows with a circular progress / error glyph, Try again / Verify, cancel button + swipe;
    toolbar pause/resume + cancel all (confirmed by an alert). No new strings.
  - **Verified:** `DownloadQueueItemsTest` (the domain → item mapping), host + iOS;
    `:androidApp:assembleDebug`; Debug xcodebuild.
  - **Not verified by hand:** a live queue on the simulator (progress, holds, cancel/retry), Android on a
    device.

- 2026-10-11 — **Phase 8, part A: reader navigation bugs** (shared ViewModel, before the SwiftUI port).
  - **Causes found** (repro tests failed first, 6 of them):
    - **Duplicate chapter numbers.** The spine kept every scanlator's copy, so from 56 (group B)
      "previous" was 56 (group A) and 55 looked unreachable; "next" walked into a copy of the chapter
      just read. The simulator's series has 7 doubled numbers.
    - **Index race.** Focus was reported as an index and resolved against the newest list, so a report
      from the list still on screen after a prepend/eviction named an item N away (end of chapter / next
      chapter).
    - **A finished chapter resumed on its last page.**
  - **Fixes:**
    - `siblingsOf` keeps one chapter per number (the opened one, else its group, else source order).
    - Focus and `ReaderCommand`s are by `ReaderItem.key`. Compose reads the key from the layout info,
      and waits ≤1 s for a command's key to reach the rendered list.
    - The strip applies `Reanchor` with `requestScrollToItem` (no fling cancel), for shifts beyond
      Compose's ~100-item key window.
    - Read chapters reopen at page 0.
    - `Reader(…, page)` opens at a page (for bookmarks).
    - `jumpTo` reseeds the window for a far chapter (`onChapterSelected`, for the Chapters sheet) without
      an empty frame.
    - The previous chapter is warmed once the reader settles.
  - **Verified:**
    - `ReaderViewModelTest` 45 (the 6 repros, far jump, open-at-page), iOS simulator.
    - `:androidApp:assembleDebug` and the Debug xcodebuild.
    - Live on the simulator (Compose reader, temporary scripted hook, removed): in paged L→R and webtoon,
      56 (Sniper Lord) → previous → 55 Sniper Lord → next → 56 → next → 57, each on page 1. A finished
      57 reopened on 1/13 and kept its read flag.
  - **Not verified by hand:** fling and swipe gestures (no gesture automation here), long webtoon
    chapters, Android on a device or emulator.

- 2026-10-11 — **Phase 8, part B: reader features + page bookmarks** (shared ViewModels + Android Compose;
  iOS gets the Bookmarks screen and Manga detail's markers natively; its reader is still the Compose one
  until part C). Owner's choices: a new `page_bookmark` table with `reading_progress.has_bookmark`
  retired (kept, unused); a bookmark stores its page's image reference; Material Icons Rounded
  (`material-icons-extended` 1.7.3) for new Android icons.
  - **`:features:reader:logic`** (Compose-free; exported in C): ViewModel, window, session and state
    moved out of `:features:reader`; state made Swift-friendly (`chapterNumberLabel`, `PageItem` with
    `imageUrl`/`imageHeaders`/`localPath`, no value classes). `@Immutable` replaced by a Compose
    `stabilityConfigurationFile` in `:features:reader`.
  - **Reader:** sheet + Chapters/Bookmarks segment as inputs; `isCurrentPageBookmarked`; the chapter list
    (the reader's spine, newest first, read/downloaded/bookmark count, built only while the sheet is
    open); bookmark list; reading mode via `SetReadingMode` (user origin), switched live on the same page
    (`initialItemIndex` moves to the focused key, layouts keyed on the mode); app-wide
    `ReaderBackground` (DataStore); chrome auto-hides after 3 s unless a sheet is open or the scrubber
    is held; chapter URL for ⋯ → Open in browser.
  - **Bookmarks data:** `PageBookmark` + `PageBookmarkRepository` + 4 use cases; Room v16
    (`Migration15To16`, SQL checked against `16.json`); "delete read downloads" keeps chapters with a
    bookmarked page. Source sync needed no change: the reader writes a progress row on open, so every
    bookmarked chapter already has one, which sync keeps. `observeBookmarkedChapters` removed (unused).
    `ObserveChapterDownloads` and `SetReadingMode` unsealed so ViewModel tests can fake them.
  - **Android:** chrome rebuilt (back · title over series · bookmark · ⋯ / prev · scrubber "12 / 48" ·
    next, Chapters, Reader settings) on opaque `surface` bars; Chapters sheet (segmented, scrolled to the
    current chapter, thumbnails through the page loader) and Settings sheet (4 modes, 3 swatches); page
    grounds and slide ink from the new generated `ReaderColors.kt` (`generate-ios-tokens.py` third
    argument).
  - **Manga detail (both):** bookmark marker + count on rows; "Bookmarked" in the chapter filter (the
    filter shows whenever the series has bookmarks); with it on, a tap opens the first bookmarked page.
  - **Library ⋯ → Bookmarks (both):** `Destination.Bookmarks`, `:features:bookmarks:logic`
    (`BookmarksViewModel`, grouped by series, newest first) + `:features:bookmarks` (Compose,
    swipe to delete) + `Features/Bookmarks/BookmarksScreen.swift`; ⋯ in Library's toolbar (iOS
    `ToolbarItemGroup` with `ellipsis`, Android `TanukiTopBar` gained an `actions` slot).
  - **Left out of Reader settings:** page scale/fit, tap zones, keep screen on, brightness, page gap,
    crop borders, double pages. The settings' 4 modes don't show `CONTINUOUS_VERTICAL` (a legacy mode
    laid out like webtoon).
  - **Verified:** reader 61 (+7: sheet jump near, chapter list only while open, mode switch keeps the
    page, bookmark toggle with image, bookmark opens its page, chrome auto-hide, background),
    `TogglePageBookmarkTest` 2, `MangaContentProjectionTest` 7, `BookmarksGroupingTest` 1, all core
    tests, host + iOS; `:androidApp:assembleDebug`, Debug xcodebuild.
  - **Not verified by hand:** any of B on a device (Android chrome/sheets/swipe, iOS Bookmarks screen and
    filter), a local (downloaded) page as a thumbnail, the Room v15→v16 migration on a real database.

  Next: **Phase 8, part C** (SwiftUI reader).

