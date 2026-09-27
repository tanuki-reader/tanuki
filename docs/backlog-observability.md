# Tanuki Observability Backlog — logging + Sentry


## Context

You want to know how the app behaves on real devices. Right now you effectively can't: the
`Logger` → `CrashReporter` → Sentry seam is well designed, but almost nothing flows through it.

Three findings, in order of severity:

1. **`iosApp/iosApp/iOSApp.swift` bypasses the whole architecture.** It calls `SentrySDK.start`
   eagerly at launch with a **DSN hardcoded into a committed file**, `sendDefaultPii = true`,
   `tracesSampleRate = 1.0` and profiling at 1.0. That's the Sentry wizard's sample code. It
   contradicts `SentryCrashReporter`'s opt-in consent design, contradicts nearly every privacy
   decision documented in that file's KDoc, and races the KMP SDK's own `Sentry.init`. **Every
   issue currently in the org came from it**, including the `"This app uses Sentry! :)"` test event.
2. **`logger.error` is called zero times in the entire app.** `DefaultLogger` forwards only
   `LogPriority.ERROR` to `CrashReporter.reportError`. So no failure the app *knows about* ever
   reaches Sentry — only native crashes do. There are 29 `logger.warn` sites and 15 `tryIo`
   call sites, all of them invisible.
3. **Breadcrumbs are always empty.** `Logger.warn` and `Logger.info` hardcode
   `addToBreadcrumbs = false`, and only `error` defaults to `true` — which nothing calls. Every
   Sentry event therefore arrives with no trail. Your `SIGABRT` issue showing `culprit: ?` is
   this plus missing symbols.

Plus: **Android has no Sentry integration at all** (no Gradle plugin, no mapping upload,
`versionCode = 1` hardcoded, `isMinifyEnabled = false`).

Live state of `tanuki-jc/tanuki` (checked via the Sentry API):

| Issue | What it really is |
|---|---|
| `TANUKI-4` — "This app uses Sentry! :)" | the wizard's test event; delete |
| `TANUKI-1` — `SIGABRT: Signal 6` | culprit `?` — unsymbolicated, no breadcrumbs, unactionable |
| `TANUKI-2` — `WatchdogTermination` | **the OS killed the app for RAM.** Cross-reference the efficiency backlog: unbounded `ReaderViewModel.pagesCache` |

**Decisions made:** remove the Swift init entirely; one Sentry project tagged by platform;
crashes + handled errors + breadcrumbs (no performance tracing for now); anonymous per-install
UUID for reliable user counts.

> **Status (2026-09-27):** Phase 0 (0.1, 0.2) and Phase 1 (1.1–1.5) are implemented; see the ✅ notes
> under each item. Phase 2 is not started. Phase 3 is mostly applied: first through the Sentry API
> (2026-09-26), then through the web UI (2026-09-27). The "Still manual" list at the end of Phase 3
> shows what's left: hiding `debug` once it exists, the dashboard and saved searches once events flow,
> and the release filter, which the current plan doesn't allow.

Items are ordered so each is independently shippable. Phase 0 is a correctness/privacy fix and
should go first regardless of how far you take the rest.

---

## Phase 0 — Stop the contradiction (do this first)

### 0.1 Delete the Swift-side `SentrySDK.start`

✅ Done — the block and `import Sentry` are gone; the SPM package stays linked.

**Problem:** `iosApp/iosApp/iOSApp.swift` starts Sentry before any consent is read, with PII on
and full tracing/profiling. It also means two independent `init` calls (Swift's and the KMP
SDK's from `SentryCrashReporter.setEnabled`) against the same SDK instance.

**Fix:** delete the entire `SentrySDK.start { … }` block and the `import Sentry`. Leave the
`registerLibraryUpdateTask()` / `registerDownloadTask()` /
`requestNotificationAuthorization()` calls — those are correct and load-bearing (the KDoc
explains why they must run before launch finishes).

`SentryCrashReporter` then owns the SDK lifecycle on both platforms, which is what its KDoc
already claims. Accept the documented blind window between launch and the preference resolving —
that KDoc explicitly says not to "fix" it by initializing eagerly.

**Keep the SPM dependency.** The Sentry Cocoa framework must stay installed in Xcode; the KMP
plugin links against it. Only the Swift-side *initialization* goes away.

### 0.2 Purge the committed DSN

✅ Done going forward — the literal is gone from the tree and `local.properties` carries `sentry.dsn`. It
remains in history, by decision (see below); the per-key rate limit is set.

**Problem:** the project's DSN is in git history (it was the literal in `iOSApp.swift`). `shared/build.gradle.kts` already has a careful DSN-injection mechanism
(`TANUKI_SENTRY_DSN` env → `tanuki.sentry.dsn` Gradle property → `sentry.dsn` in the
git-ignored `local.properties`) whose entire stated purpose is "the DSN never enters public git
history." The Swift file defeats it.

**Fix:** removing the block in 0.1 removes the literal. Then put the same DSN into
`local.properties` as `sentry.dsn=…` so your local builds keep reporting.

A DSN is a write-only ingest key, not a cryptographic secret — it ships in every binary and can
be extracted. So **do not rotate in a panic**; instead handle abuse server-side (item 5.4,
per-key rate limits). But do stop adding it to history.

**Files:** `iosApp/iosApp/iOSApp.swift`, `local.properties` (git-ignored, not committed).

### 0.3 Fix the `WatchdogTermination`

Not a Sentry change — a real OOM on a real device. It is almost certainly
`ReaderViewModel.pagesCache` growing unbounded across a long reading session (efficiency
backlog item 4.1). Worth knowing that your observability setup already earned its keep by
catching it.

---

## Phase 1 — Make the app actually report (the highest-value phase)

### 1.1 Turn `warn` into breadcrumbs

✅ Done — `info` and `warn` both breadcrumb. Per-item `info` was demoted to `debug`
(`AutoDownloadNewChapters`, `DeleteReadChapterDownloads`, `DownloadManager` abandon/hold). Scrubbed at
the call site: titles (`UpdateLibrary`, `AutoDownloadNewChapters`) became ids, the Cloudflare solver no
longer logs challenge URLs, and source browse no longer logs the search text. A guest source's `ERROR`
log is now logged as `WARN` (`KtorSourceHost`), so third-party text can never become a report.

**Problem:** `Logger.warn` (`core/domain/.../common/service/Logger.kt`) passes
`addToBreadcrumbs = false`. Combined with 1.2 below, this is why every event has an empty trail.

**Fix:** flip the `warn` helper's default to `addToBreadcrumbs = true`. A warning is *exactly*
the "something recoverable went wrong" context you want in the trail leading to a crash —
that's what its own KDoc says it means.

Consider `info` too, but more carefully: 27 sites of lifecycle noise could crowd out the useful
crumbs (Sentry keeps the last ~100). Recommendation: `warn` → breadcrumbs unconditionally;
`info` → breadcrumbs, but audit the 27 sites first and demote anything per-item or per-page to
`debug`.

**Privacy check before flipping:** `CrashReporter`'s KDoc warns that breadcrumb text "can
include source URLs and manga titles." Audit the 29 `warn` call sites for interpolated titles
and URLs — `tryIo("Chapter refresh failed for $mangaId")` is fine (an opaque UUID), but anything
interpolating a title or a source URL must be scrubbed to a host or an id first. **Do this audit
as part of the same change, not after.** Item 5.3 adds a server-side backstop, but the fix
belongs at the call site.

### 1.2 Introduce `logger.error` at real failure sites

✅ Done — `reader.pages_unavailable` (reader page list), `source.browse_failed` (first page → error
screen), `download.failed` (only `DownloadError.Unknown`), `library_update.total_failure` (every series
failed, not all of them offline), `cloudflare.clearance_rejected` (solved but still challenged).
An `OfflineException` is never reported. `tryIo` gained an optional `reportAs` label rather than a
sibling function; `TanukiViewModel.logFailure` holds the offline rule. Room/WASM-internal sites were not
promoted: they surface through the reader/browse events, and a migration failure crashes anyway.

**Problem:** zero call sites. `DefaultLogger` reports only `ERROR` to the reporter, so the app
currently tells Sentry nothing about its own failures.

**Fix:** promote genuine failures from `warn` to `error`, with an `analyticsEvent` label so they
group and can be searched by kind. Candidates, roughly in value order:

| Where | Suggested `analyticsEvent` |
|---|---|
| `WasmSourceLoader` — ABI exception, module instantiation failure | `source.abi_failure`, `source.load_failure` |
| Cloudflare interceptor — solver gave up / challenge unsolved | `cloudflare.unsolved` |
| `DownloadManager` — chapter failed after all retries | `download.exhausted` |
| `UpdateLibrary` — a run where *every* series failed | `library_update.total_failure` |
| Room — migration failure, constraint violation | `db.migration_failure` |
| `ReaderViewModel` — page list resolution failed for a chapter | `reader.pages_unavailable` |

**The distinction to hold:** `warn` = the app carried on and the user probably didn't notice.
`error` = the user saw something broken. Don't promote a best-effort refresh that silently fell
back to cache — that's a `warn`, and it belongs in the trail, not the inbox. Getting this line
wrong in the noisy direction is how a crash-reporting project becomes unreadable.

`tryIo` (`TanukiViewModel.kt:114`) logs `warn` — that's the right default for a ViewModel-level
best-effort. Leave it; add a sibling `tryIoOrError` only if a specific screen needs it.

### 1.3 Attach an anonymous install ID

✅ Done — `SettingsRepository.getOrCreateInstallId` / `GetInstallId`; set via `Sentry.setUser` on start.
No "reset identifier" UI yet.

**Problem:** `sendDefaultPii = false` and no user id is ever set, so Sentry cannot distinguish
one user crashing 400 times from 400 users crashing once. Every "users affected" number you're
looking at is unreliable, and crash-free-rate is only meaningful with a stable id.

**Fix:** generate a random `Uuid` on first launch, store it in the existing DataStore preference
store (`:core:data`, the `PreferencesFileFactory` expect/actual — see the pattern already used
for the theme preference), and set it as Sentry's user id in `configure`.

It must be: random, generated on-device, tied to no account, no email, no device identifier, and
regenerated if the user clears data. That keeps `sendDefaultPii = false` honest. Add a "reset
identifier" affordance next to the crash-reporting toggle if you want to be thorough.

### 1.4 Tag platform explicitly

✅ Done — `BuildEnvironment.platform: AppPlatform`; tag `tanuki.platform` = `android` / `ios`.

Sentry auto-tags `os.name`, but that's derived and inconsistent across SDK layers. Set an
explicit `tanuki.platform` tag (`"android"` / `"ios"`) in `SentryCrashReporter.configure` via a
new `BuildEnvironment` property or an expect/actual constant. Cheap, and it makes every saved
search and dashboard filter unambiguous.

See "Do you need to differentiate Android and iOS?" below for why this matters.

### 1.5 Consider Sentry structured logs

✅ Done — 0.27.0 has it (`options.logs.enabled`, `Sentry.logger`). Rather than a new `LogReceiver`,
`SentryCrashReporter.recordBreadcrumb` also sends each breadcrumb as a log, so the breadcrumb flag is the
single opt-in/privacy gate for both.

The Swift init had `options.experimental.enableLogs = true` — Sentry's newer structured-logging
product, which would let `info`/`warn` flow as searchable logs rather than only as breadcrumbs
attached to a crash. That's much closer to "general information about how the app is behaving"
than breadcrumbs are.

**Check whether the KMP SDK at 0.27.0 exposes it** (it is pre-1.0 and lags the native SDKs). If
it does, a `LogReceiver` forwarding `INFO`/`WARN` to Sentry logs is a natural fit for the
existing `Logger.LogReceiver` seam — that's precisely what the seam is for. If it doesn't, defer;
don't drop to a platform-specific hack for it.

---

## Phase 2 — Set up Sentry on Android

Currently nothing. The KMP SDK's `androidMain` does pull `sentry-android` transitively, so
`SentryCrashReporter` will already capture crashes on Android once started — but with no
symbolication, no release health, and no ANR data.

### 2.1 Apply the Sentry Android Gradle plugin

Add `io.sentry.android.gradle` to `androidApp/build.gradle.kts` (version to
`gradle/libs.versions.toml` — CLAUDE.md forbids inline versions). It provides ProGuard/R8
mapping upload, optional source context, and build-time auto-instrumentation.

**Critical, easy to miss:** the plugin's dependency brings Sentry's `ContentProvider`
auto-initializer, which starts the SDK at process start — **destroying the opt-in consent model
you just repaired in Phase 0.** Disable it explicitly in `androidApp/src/main/AndroidManifest.xml`:

```xml
<meta-data android:name="io.sentry.auto-init" android:value="false" />
```

Verify it took effect by launching with crash reporting *off* and confirming no session is sent.

Configure the plugin conservatively to start:
- `autoUploadProguardMapping = true` (gated on an auth token being present, so contributor
  builds don't fail)
- `tracingInstrumentation.enabled = false` — matches the deliberate `tracesSampleRate = null`
- `includeSourceContext = false` initially; it uploads source snippets, which is a separate
  privacy call

### 2.2 Enable R8 and upload mappings

`androidApp/build.gradle.kts:47` has `isMinifyEnabled = false` on release. Turn it on for real
releases and let the plugin upload the mapping file, otherwise every production stack trace is
obfuscated. Keep a debug/staging build type unminified.

**Watch out:** Metro-generated DI code, Room, and Compose all need keep rules. Turning on R8 is
its own small project — verify the app actually launches and that WASM sources still load before
shipping it. If that's more than you want right now, leaving `isMinifyEnabled = false` is a
legitimate choice: stack traces stay readable and the mapping upload becomes a no-op. Just make
it a decision rather than an accident.

### 2.3 Fix `versionCode`

`androidApp/build.gradle.kts:36` hardcodes `versionCode = 1`. `versionName` correctly comes from
the version catalog (with a comment explaining it must match what iOS reports), but the build
number does not. Sentry uses it as `dist`, which is what separates two builds of the same
version — without it you cannot tell a fixed build from a broken one.

Derive it from git commit count or a CI build number, and set `options.dist` in
`SentryCrashReporter.configure` to match on both platforms (iOS reads `CFBundleVersion`).

### 2.4 ANR reporting

The Android SDK reports Application Not Responding events, which have no iOS equivalent and are
the single most useful Android-only signal. Confirm it's active once the manifest flag in 2.1 is
in place (ANR detection starts with the SDK, so it only works after consent — expected).

Your app has real ANR candidates: `WasmSourceLoader`'s per-source `Mutex` and the
`FileSystemChapterDownloadStore` directory walks.

### 2.5 Upload iOS dSYMs (the Android item's twin)

Not Android, but it's the same class of gap and belongs here. `TANUKI-1` shows `culprit: ?`
because no debug symbols were ever uploaded. Add a `sentry-cli upload-dif` Run Script build phase
in Xcode (or the Sentry SPM plugin), and set the build's debug information format to **DWARF with
dSYM File** for Release.

Without this, iOS crash reports are permanently unreadable — and readable iOS stack traces are
the stated reason this SDK was chosen over Crashlytics in the first place.

---

## Phase 3 — Changes on the Sentry side (`tanuki-jc/tanuki`)

Everything here is done in the Sentry web UI, not in code. Roughly in the order I'd do them.

### 3.1 Housekeeping

- **Delete `TANUKI-4`** ("This app uses Sentry! :)") — it's the wizard's test event.
- **Resolve `TANUKI-1` and `TANUKI-2` as "resolved in next release"** once Phase 0 + 2.5 land, so
  they re-open if they recur with symbols attached and you get a genuinely readable report.
- Set the project's platform to Kotlin Multiplatform (or Apple/Android) so Sentry picks sensible
  grouping and suggestions.

### 3.2 Environments

`SentryCrashReporter.configure` already sets `environment` to `debug` or `production`. In
**Settings → Environments**, hide `debug` so it never pollutes your default issue stream, and
make `production` the default for all saved searches and alerts.

### 3.3 Data scrubbing (do this before Phase 1 breadcrumbs ship)

**Settings → Security & Privacy:**
- Enable **Data Scrubber** and **Use Default Scrubbers**.
- Keep **Require Data Scrubber** on.
- Add **Advanced Data Scrubbing** rules as a server-side backstop for the breadcrumb content
  1.1 will start sending. At minimum: mask anything matching a URL in breadcrumb messages and in
  the `tanuki.message` tag.

This is a backstop, not the fix — scrub at the call site (1.1). But it protects you from the next
`logger.warn("Failed to load $title")` someone adds without thinking.

### 3.4 Rate limits and spike protection

Because the DSN ships inside every binary and can be extracted (the build script's comment
acknowledges this and says abuse is "handled server-side by the rate limit on the key, not here"
— that rate limit does not exist yet):

- **Settings → Client Keys (DSN) → Rate Limit**: set a per-key ceiling, e.g. a few thousand
  events/hour. Start generous and tighten once you know your real volume.
- **Settings → Spike Protection**: turn it on.
- **Settings → Inbound Filters**: enable the standard noise filters, and add a filter for any
  release string that isn't `app.tanuki@<version>` — that's how a fork's build gets rejected.

### 3.5 Alerts

Two are enough to start:

1. **Issue alert** — "a new issue is created in `environment:production`". Immediate, high signal
   while your volume is low.
2. **Metric alert** — crash-free *session* rate drops below ~99% over 1 hour. This is your
   north-star; `enableAutoSessionTracking = true` is already set, so the data is flowing.

Add a third once you have Android traffic: a metric alert **split by `tanuki.platform`**, so a
regression on one platform isn't diluted by health on the other.

### 3.6 Dashboard

**Dashboards → Create Dashboard**, "Tanuki Health". Suggested widgets:

| Widget | Why |
|---|---|
| Crash-free session rate, **grouped by `tanuki.platform`** | the one number that says "is the app OK" |
| Events over time, grouped by `release` | did the last release regress? |
| Top issues by event count, filtered to `production` | what to fix next |
| Events grouped by `tanuki.event` tag | which *kind* of failure dominates — this is what 1.2's `analyticsEvent` labels buy you |
| Events grouped by `tanuki.tag` | which subsystem is noisiest (source layer? downloads? reader?) |
| Adoption by release | how fast users update — context for everything else |

The last two only become useful after Phase 1 ships; build the dashboard then, not before.

✅ Built 2026-09-27: <https://tanuki-jc.sentry.io/dashboard/6140066/>. Saved filters are `production`
and 14 days. What was actually built differs from the table above in three ways:

- **Crash-free rate can't be split by `tanuki.platform`.** Session data (the Releases dataset) can
  only be grouped by `environment`, `project`, `release` and `session.status`, never by a custom
  tag. The widget shows crash-free sessions and crash-free users, unsplit. To split sessions per
  platform, give each platform its own release name, e.g. `app.tanuki.android@x` /
  `app.tanuki.ios@x`, set in `SentryCrashReporter.configure` alongside 2.3. The release-health table
  then splits by platform on its own. (This also changes the release-name pattern in 3.4's
  release filter.)
- **The Sentry UI only offers tags it has already seen**, so `tanuki.*` tags can't be picked in the
  widget builder yet. Workarounds used:
  - "Errors by platform" is two filtered queries (`tanuki.platform:android` / `:ios`) rather than a
    group-by.
  - The `tanuki.event` and `tanuki.tag` widgets were built as Discover queries (tag set in the URL)
    and added via "Add to Dashboard". They're top-5 time series grouped by that tag +
    `tanuki.platform`, filtered with `has:<tag>`.
- **Adoption** is a "Release health & adoption" table: release, crash-free sessions, crash-free
  users, users, sessions.

Widgets: Crash-free rate (sessions / users) · Errors by platform · Errors by release · Top
unresolved issues · Errors by failure kind (`tanuki.event`) · Errors by subsystem (`tanuki.tag`) ·
Release health & adoption · Unresolved issues — Android · Unresolved issues — iOS.

### 3.7 Saved searches

Create and pin: `is:unresolved tanuki.platform:android environment:production` and the same for
`ios`. This is the day-to-day answer to "one project or two" (see below).

⚠️ **Not available on the free Developer plan:** saved issue searches need the Team plan. Instead,
the dashboard has two issue tables, "Unresolved issues — Android" and "— iOS". They filter with
`is:unresolved tanuki.platform:<platform>`, sort by events, and inherit the dashboard's
`production` filter.

### 3.8 Releases

Once 2.3 gives you a real `dist`, wire release creation into your build (`sentry-cli releases
new` / the Gradle plugin does this for Android). Associating commits with releases is what turns
"this issue started in 1.2" into "this issue started in *this commit*", and it's the single
biggest quality-of-life win in the Sentry UI.

### Applied via the Sentry API (2026-09-26)

- Client key rate limit: **100 events / hour** (3.4). Raise it once real volume is known.
- `TANUKI-4` ignored forever. The API can't delete issues, so it's ignored rather than deleted.
- `TANUKI-2` has a note pointing at efficiency item 4.1. `TANUKI-1`/`TANUKI-2` are still open, by design (3.1).
- Project platform set to `kotlin`.
- Metric monitor **"Crash-free session rate below 99%"** (`production`, 1h window) created —
  <https://tanuki-jc.sentry.io/monitors/2302031/>. **It has no connected alert yet**, so it notifies
  no one (see below).

### Applied via the Sentry web UI (2026-09-27)

- **Alerts (3.5):**
  - The crash-free monitor is connected to "Send a notification for high priority issues".
  - New alert **"New issue in production"**: triggers on "a new issue is created", filtered to
    `production`. It notifies suggested assignees, or recently active members if there are none.
- **Data scrubbing (3.3):**
  - Project level: Data Scrubber and Use Default Scrubbers were already on.
  - Org level: **Require Data Scrubber** was *off* and is now on.
  - Two advanced rules, both masking `(?i)\b(?:https?|wss?)://\S+`:
    - Errors dataset, source `$breadcrumb.message || tags.'tanuki.message'`
    - Logs dataset, attribute `message`. Needed because 1.5 also sends each breadcrumb as a log.
- **Spike protection (3.4):** already enabled for `tanuki`.
- **Inbound filters (3.4):** turned on browser extensions, localhost and web crawlers. Health-check,
  hydration and ChunkLoadError filters were already on.

### Still manual

- [x] Connect the monitor to an alert, and create the "new issue in `environment:production`" issue
      alert (3.5).
- [x] Security & Privacy: Data Scrubber + default scrubbers + advanced URL-masking rules (3.3).
- [ ] **Verify** the `tags.'tanuki.message'` selector actually masks. Sentry accepted the rule, but it
      has not been checked against a real event. Look at the first Phase 1 event carrying a URL.
- [x] Spike protection on; standard inbound filters on (3.4).
- [ ] ~~Reject releases other than `app.tanuki@*`~~. **Not possible on the current plan:** custom
      release filters need a Business plan or higher. They're also blocklist globs, so "everything
      except `app.tanuki@*`" can't be written as one anyway. Rely on the per-key rate limit for now.
- [ ] Hide the `debug` environment (3.2). It doesn't exist yet; it only appears after the first
      debug-build event. Hide it then.
- [ ] Use "Send Test Notification" on the new alert to confirm delivery.
- [x] "Tanuki Health" dashboard (3.6). Per-platform issue lists were added as dashboard tables,
      because saved searches (3.7) need the Team plan.
- [ ] Once Phase 1 events arrive, check that the `tanuki.platform` / `tanuki.event` / `tanuki.tag`
      widgets fill in.
- [ ] Decide on per-platform release names (see 3.6). This is the only way to split crash-free
      rate by platform and to build the per-platform crash-free alert (3.5, third alert).
- [ ] Check what `TANUKI-5` / `TANUKI-6` are: new issues since this backlog was written, both
      `HTTPClientError … 503`, together ~450 events and most of the event volume. HTTP client
      errors suggest failed-request capture was on (the Cocoa SDK default under the old Swift init),
      even though `SentryCrashReporter` deliberately disables it.

---

## Do you need to differentiate Android and iOS?

**Yes — but with a tag, not a second project.** That's the decision recorded above; here's the
reasoning, since it's the thing most likely to get second-guessed later.

**Why differentiate at all:**
- The crash classes barely overlap. `WatchdogTermination` and `SIGABRT` are Apple-only; ANR is
  Android-only. A single blended crash-free rate hides a platform-specific collapse.
- The symbolication pipelines are completely separate (dSYM upload vs R8 mapping upload), and
  they fail independently and silently.
- The SDKs behave differently. `enableUnhandledCppExceptionMonitoring = false` exists *only*
  because of an Apple-target problem — the KDoc explains that with it on, a Kotlin exception from
  a Compose callback surfaces as an `ExceptionObjHolderImpl` C++ crash instead of a Kotlin stack
  trace.
- Adoption curves differ, so "which release is this?" needs a per-platform answer.

**Why one project anyway:** the app is ~95% shared Kotlin. A bug in `WasmSourceLoader` or
`ReaderViewModel` is *one bug*. Split into two projects it becomes two unrelated issues with two
separate resolution states that you must correlate by hand — actively fighting the reason you
chose KMP. You'd also split quota, alerts and release health for no benefit.

One project + `tanuki.platform` tag + pinned saved searches (3.7) + dashboard widgets grouped by
that tag gives you every per-platform view you'd get from two projects, while shared-code bugs
still group once.

**Revisit only if** the platforms diverge enough to need genuinely different alert routing or
separate quota budgets — neither is true today.

---

## Should you add analytics alongside Sentry on the free tier?

**Short answer: no — not yet.** Squeeze Sentry first. But the free tier's shape does change one
thing in Phase 1, so this isn't a no-op.

### What the free tier actually gives you

Sentry's free Developer plan is **5,000 errors/month, 1 user**, 50 replays. Release health and
sessions are *not* billed as errors, so `enableAutoSessionTracking = true` gives you crash-free
rate and release adoption at no quota cost. That is genuinely most of "how is my app behaving on
user devices."

**5,000/month is ~166/day, and that is the binding constraint.** It reframes item 1.2: a single
chatty `logger.error` — say one that fires on every failed page fetch for a user on a bad
connection — can burn the month's quota in an afternoon, and Sentry then drops everything else,
including the crash you actually needed. Concretely, add to 1.2:

- Keep `error` genuinely rare (the `warn` vs `error` line in 1.2 is now a quota decision, not
  just a taste one).
- Never call `error` inside a per-page or per-chapter loop. `DownloadManager` failing a whole
  chapter is one error; the three page retries beneath it are breadcrumbs.
- Set the per-key rate limit (item 3.4) **before** Phase 1 ships, so a runaway call site
  degrades gracefully instead of blinding you.
- If a source-layer error proves unavoidably frequent, sample it client-side rather than letting
  Sentry drop arbitrarily — you choose what survives, not the quota.

### What it doesn't give you

Product analytics: retention, feature adoption, "does anyone use the webtoon reader", funnels.
Sentry is not that tool and never will be. If you want those questions answered, it's a second
system.

### Why hold off anyway

1. **You have no signal yet.** Nothing the app knows about currently reaches Sentry at all.
   Phases 0–2 will surface far more than you expect. Adding a second pipeline before reading the
   first is optimizing a system you haven't observed.
2. **The codebase has taken a deliberate anti-tracking stance**, and it's written down.
   `CrashReporter`'s KDoc says the `eventName` "names *this kind of failure* — it is not
   behavioural analytics, and nothing here tracks what a user reads." `SentryCrashReporter`
   disables `enableCaptureFailedRequests` specifically because "in this app the HTTP traffic *is*
   the user's reading activity." Adding product analytics reverses a documented decision — fine
   if deliberate, bad if it happens by drift.
3. **Every SDK is a recurring cost:** another consent surface in settings, another privacy policy
   line, binary size, and a KMP integration to maintain.

### If you do add one later: Aptabase

It's the only option that fits this app without friction:

- **Official Kotlin SDK** ([aptabase/aptabase-kotlin](https://github.com/aptabase/aptabase-kotlin))
  covering Android, iOS and desktop — no expect/actual glue or community wrapper needed.
- **Privacy-first by construction:** no unique identifiers, no cookies, no fingerprinting,
  no cross-site tracking. Compatible with the stance already in the code.
- **1M events/month free** — three orders of magnitude past Sentry's error budget, because
  events are cheap when they aren't stack traces.
- **AGPLv3, self-hostable** if you'd rather own the data outright.

Architecturally it slots in cleanly: add an `Analytics` interface to `:core:domain` mirroring
`CrashReporter` exactly — app-owned types only, a `NoOpAnalytics` default binding, the real
implementation in `:shared` via `@ContributesBinding(replaces = [...])`, gated on its own opt-in
preference. The `analyticsEvent` parameter already threaded through `Logger.log` is the seam;
that plumbing exists and is currently only used for Sentry tags.

### What to avoid

- **Firebase Analytics / Crashlytics** — not KMP-native (needs the GitLive wrapper), pulls Google
  Play Services into a FOSS reader that a chunk of its likely audience installs specifically to
  avoid that, and duplicates what Sentry already does.
- **PostHog** — generous free tier (1M events), but no first-party KMP SDK and it's an
  identity-centric product analytics tool; the impedance mismatch with this app's privacy posture
  is high.

---

## Verification

**Phase 0:**
```bash
git grep -n "ingest.*sentry.io"      # must return nothing after 0.2
grep -rn "SentrySDK.start" iosApp/    # must return nothing
```
Then build iOS from Xcode, launch with crash reporting **off**, and confirm **no** event or
session reaches `tanuki-jc/tanuki`. That's the test that the consent model actually works — and
it is the test that fails today.

**Phase 1:** with reporting on, trigger a real failure (airplane mode, then open a series with
no cached chapters). Confirm in Sentry that the event carries breadcrumbs, a `tanuki.event` tag,
a `tanuki.platform` tag, and a stable user id across two consecutive crashes.

Also add unit tests alongside the existing ones —
`core/domain/src/commonTest/.../DefaultLoggerTest.kt` and its `RecordingCrashReporter` already
establish the pattern for asserting what reaches the reporter. Cover: `warn` now produces a
breadcrumb; `error` produces a report; `debug` in a release build produces neither.

**Phase 2:**
```bash
./gradlew :androidApp:assembleRelease   # mapping upload runs (or no-ops without a token)
./gradlew :androidApp:assembleDebug     # stays green
```
Force a crash in a release build, then confirm the Sentry stack trace is deobfuscated. Separately,
launch with reporting **off** and confirm no session is sent — that verifies the
`io.sentry.auto-init=false` manifest flag from 2.1 actually took.

**Phase 2.5:** crash a Release iOS build and confirm the stack trace shows Kotlin frames rather
than `culprit: ?`.

**Sentry side:** after the alerts in 3.5 exist, use Sentry's "Send Test Notification" rather than
waiting for a real crash.

Standard project constraints apply throughout — from CLAUDE.md: keep both targets green, never
pipe gradle through `tail` or use `-q` when checking results, and check the embedded
`Shared.framework` mtime before believing an iOS-only bug.

---

## See also

- `docs/backlog-efficiency.md` — memory, bandwidth and query efficiency. Item 4.1 there is the
  likely cause of the `WatchdogTermination` (`TANUKI-2`) recorded here.
