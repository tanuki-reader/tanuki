# Decision log

Short, dated records of non-obvious engineering decisions — what was decided, why, and (for
deferrals) the concrete trigger to revisit. Newest first. This is the version-controlled companion to
the design doc (`session-source-and-browse.md`); keep entries brief and link to code where useful.

---

## 2026-09-06 — The download queue is worked one chapter at a time, in order

**Decision:** `DownloadManager` takes the queue strictly in `queuedAt` order, one chapter at a time,
with a few *pages* of that chapter in flight at once (`MAX_CONCURRENT_PAGES = 3`). It does **not** copy
`UpdateLibrary`'s "sequential per source, three sources at once", which `design-downloads.md` §5 had
asked for.

**Why:** the two runs look alike and are not. A library update is invisible work over N sources, where
throughput is the only thing that matters and nobody can tell what order it happened in. A download
queue is something the user is *looking at*: they queued twelve chapters and expect the first one to
finish first. Working three at once also triples the page bytes held in memory, and turns "downloading
2 of 12" into a number that is true of nothing. The parallelism that actually pays — hiding per-request
latency — is inside a chapter, and there it costs nothing extra because those pages were going to be
fetched anyway. `DownloadRepository.nextQueued()`, written in phase 1, is already exactly this shape.

**Cancelling is a flag the engine checks, not a coroutine cancellation.** `DownloadManager.abort(ids)`
marks chapters abandoned; the run checks the mark either side of each page fetch, drops any bytes that
arrive after it, and discards the part. The alternative — holding the run's `Job` and cancelling it —
needs atomic cross-thread state to buy back at most one page's latency, and introduces a way to leave a
half-written file. The residual race (a cancel landing inside the store's write) leaves a part directory
that the phase-8 reconciliation sweep reclaims, which is a sweep that has to exist regardless for a user
who clears app storage.

**There is no `DownloadScheduler.stop()`.** A run ends when the queue is empty, so ending one early
means taking its work away — abort plus deleting the rows, which is what cancel and delete already do.
A `stop()` would create the one state that cannot be recovered from: an engine told to stop with rows
still waiting for it.

**Consequence for phase 3:** `GetChapterPages` must *not* become download-aware, contrary to
`design-downloads.md` §6. The downloader calls it and must always reach the source; a local-first
decision inside it would have the engine download a chapter from its own copy. The local-or-remote
choice belongs in a new use case above it.

---

## 2026-09-05 — Downloads: the shape settled before the engine was written

**Decision:** the four questions `design-downloads.md` §11 left open are answered as recommended there,
and phase 1 (the storage spine) is built against those answers. Recorded here because each of them is
cheap now and a migration later.

**Chapter files are addressed by local `Uuid`, not by title** — `<root>/<mangaId>/<chapterId>/`. Titles
need sanitizing (`/`, `:`, emoji, RTL), collide across sources, and change whenever a source edits its
metadata; each of those silently orphans a folder whose contents nothing can any longer identify. The
price is a tree that isn't browsable from a file manager, which Mihon optimizes for because its users
copy folders to a PC. If we want that, it is a **CBZ export** over this layout — a feature — rather than
a reason to make the live layout fragile.

**Completion is `manifest.json` existing, and the queue's states do not include `Paused`.** Pages land
in a sibling `<chapterId>.part/` and the manifest is written last, by an atomic move of the directory,
so a crash can leave something resumable but never a half-chapter that reads as downloaded. Pausing is a
property of the *queue*, so it will be one preference rather than a row state: a paused chapter has to
keep its place in line, and "resume all" that rewrites N rows loses the ordering it was meant to
preserve.

**The database is an index over the disk and the two are reconciled, not assumed to agree.** The user
can clear app storage; a write can die. So `ChapterDownloadStore.storedChapters()` reports what is
actually there and the *domain* decides what counts as an orphan — the store is deliberately not given
the set of known ids to filter against, because "a chapter the sync pruned" and "a part a cancelled
download left" are domain facts, not filesystem ones.

**Downloads are excluded from cloud backup on both platforms**, and the Android half was the surprise:
`allowBackup="true"` with no rules meant a downloaded library counted against Auto Backup's 25 MB
per-app quota — and an app over that quota gets *no* backup at all, silently. One downloaded series
would therefore have cost the user the library, categories, progress and history that are actually worth
backing up. `backup_rules.xml` / `data_extraction_rules.xml` now exclude `files/downloads` and
`files/sources`. On iOS the same job is done by `isExcludedFromBackup`, set on every resolve rather than
only at creation (the flag is an extended attribute, and a restore recreates the directory without it).

**iOS downloads run in the foreground**, with a background grace period and a `BGProcessingTask`
continuation, and the queue screen will say so. Out-of-process downloading needs
`URLSession(background:)`, which is per-request and cannot reuse the Ktor client — so the Cloudflare
interceptor, the cookie jar and the host-owned User-Agent would all be bypassed *for downloads
specifically*, which is the traffic most likely to be challenged. Revisit if users report queues
stalling.

**The queue screen gets its own `:features:downloads` module** rather than living in `:features:manga`:
it spans series, so putting it there would need a feature→feature dependency, which the module rules
forbid. **Auto-download is per-category from day one** — `Category` already exists, and retrofitting the
setting onto an all/off toggle is a preference migration for no saving.

---

## 2026-08-22 — Cloudflare challenges solve silently first, and are shown only if that fails

**Decision:** the session layer is composed the moment a challenge arrives — full-size, laid out, opaque
and painting — but z-ordered **underneath** the app (`SilentZIndex`), so the user keeps reading while it
solves. It is put on screen only when the challenge is still up after a per-kind budget. This *amends*
the 2026-07-30 entry below rather than reversing it: the visible browser is still how a challenge that
needs a person gets solved, it is just no longer the first thing tried.

**Why it isn't a return to the headless solvers.** Those failed because their engine was **zero-size and
never attached to a window**, so it never rasterized and Turnstile never ran. The property that mattered
was *painting*, not *being seen*. Occlusion happens in the platform compositor and the page cannot observe
it — `SiteSessionHost` already relied on this, drawing `ConnectingCover` over a live, still-solving
WebView. So the rule the code now keeps, and must go on keeping: **while silent the layer changes only its
draw order.** Never fade it (`alpha` on a layer can skip rasterization), never shrink it, never take it out
of the composition to hide it. `graphicsLayer` is applied *only* once revealed.

**The budget comes from `_cf_chl_opt.cType`** (`ChallengeType`), read by the probe that was already polling
every 400ms — one JS evaluation, so the flag and the kind describe the same instant. `interactive` reveals
at once (waiting cannot help), `managed` gets 8s (it may escalate), `non-interactive`/`jschal` 12s (a person
cannot help either way). Nothing under ~6s, because Cloudflare's own JS challenge has a deliberate ~5s delay
and a tighter budget would reveal challenges that were always going to succeed. `Unknown` — what every
session reports on its first poll — is treated as `managed`; a zero budget there would defeat the feature.

**`SILENT_SESSION_CEILING` (25s) is not a second budget.** Revealing keys on *still being on an
interstitial*, which is the right test for "has this escalated", but it leaves a gap: a challenge can leave
the interstitial without ever issuing a clearance (a redirect to an error page, a WAF block behind it).
Nothing in the solve path has a timeout, so such a session would suspend the request forever with nothing
on screen to dismiss — invisible, and worse than the challenge it replaced. The ceiling reveals it so the
user has an affordance.

**Touch, not just sight.** A silent layer sits under the app but Compose will still route a tap that
nothing above consumed down to it, so the engine is switched to non-interactive while silent
(`OnTouchListener` consuming on Android, `userInteractionEnabled` on iOS) and handed the touches back on
reveal. Applied in the interop `update` block, never the factory: rebuilding the engine would abandon a
challenge seconds from clearing. Same reason the chrome is a conditional *sibling* of the web view rather
than a separate layout — the view must keep its call site across the reveal.

**Still to verify on device:** that `UIKitView` really composites *under* Compose content on iOS. The
existing `ConnectingCover` suggests it does, but it draws over the interop view inside the same layer,
which is not the same test. If iOS turns out to always draw interop on top, the silent tier is
Android-only until CMP's interop layering allows it.

**What this does not buy:** solving while the app is actually backgrounded. No window means no paint, on
both platforms (an Android `Service` could only paint through a `SYSTEM_ALERT_WINDOW` overlay, which is not
a permission worth asking for). Downloads still need the `NeedsVerification` path — see
`design-downloads.md` §5.1.

---

## 2026-07-30 — Cloudflare challenges are solved in a browser the user can see

**Decision:** the challenge is shown on screen. `SiteSessionCoordinator` (`core/source/net/session/`,
Compose-free) is a bridge: network code calls `request()` and suspends, `SiteSessionHost` renders a real
browser (`SiteSessionWebView`, `expect`/`actual` over `WebView` / `WKWebView`) and resolves it.
`InteractiveCloudflareSolver` is caller #1. **Both headless solvers are deleted** — they were broken by
construction, not merely untuned.

**Why:** MangaFire serves a *managed* challenge (`cType: 'managed'`), which renders a Turnstile widget and
can escalate to a checkbox. The old solvers built their browser offscreen at zero size and never attached
it to a window, so the widget never painted: the challenge's JS ran (device logs show its WebRTC
fingerprinting probes) and then stalled until the 30s timeout. Reproduced identically on an Android
emulator **and** a physical iPhone, which rules out the emulator's broken GPU. Nothing headless can tap a
checkbox either. Mihon's solver is offscreen too, and MangaFire is long-broken there for the same reason.

**The host owns one User-Agent.** `DEFAULT_SOURCE_USER_AGENT` behind `SourceUserAgentProvider` (suspend,
consulted per request, so a settings-backed UA is a one-line DI swap), stamped on every request by
`sourceUserAgentPlugin`, overwriting whatever a source set. The per-source `USER_AGENT =
"Tanuki (+https://tanuki.app)"` constants are gone from all four Rust crates — that UA got every request
challenged, and the solver then forced it onto the WebView, where Turnstile cross-checks the UA against
the JS environment and so a Chromium claiming to be Tanuki could never clear. Solvers now use the engine's
**own** UA and report it back; `CloudflareChallenge` deliberately has no `userAgent` field.

**Knowing when a challenge is finished took four tries**, each one a live-verified correction:

1. *Cookie present* → wrong. Cookie stores are shared and persistent (Android's `CookieManager` outlives
   the process), so a stale `cf_clearance` is already there; the check passed 5ms in, before the page had
   loaded, and replayed a dead cookie. Tell: a "solve" measured in milliseconds instead of seconds.
2. *Delete the stale cookie first* (`SiteSessionWebView(clearCookie = …)`), so the browser must re-solve.
   Mihon does the same (`cookieManager.remove(url, COOKIE_NAMES, 0)`). Also required: without it Cloudflare
   accepts its own stale clearance in the browser, issues no new one, and the session waits forever.
3. *Require a **new** value* — sound by construction, since this session only exists because a request
   carrying the old cookie was challenged.
4. *Wait for the browser to leave the interstitial* (`window._cf_chl_opt`). Cloudflare sets `cf_clearance`
   **during** the challenge, before the round trip that commits it server-side; replaying 48ms later was
   re-challenged and cost the user a second challenge. The `cf_chl_*` cookies are useless for this — they
   persist long after the flow ends, so waiting for them to clear hangs.

**The session loads the host root, not the challenged URL.** A clearance is domain-scoped, so the root
earns the same one — while being ordinary HTML rather than the API's raw JSON, which is both saner to show
someone and a document the `_cf_chl_opt` probe can actually run against. The browser is covered by a
spinner whenever it isn't showing a challenge (it must keep rendering, so it stays laid out underneath).

**Detection narrowed** (`CloudflareDetection`): `server: cloudflare` + `cf-ray` ride on *every* proxied
response, so origin 403s (a bad VRF signature, a rejected key) were spinning a pointless 30s browser. It
now keys only on markers Cloudflare sets when actually serving a challenge — `cf-mitigated: challenge`,
the `chlray` `server-timing` metric, or legacy `cf-chl-bypass` — both live markers pinned in
`CloudflareDetectionTest`. Trade: a challenge setting none of the three is missed and surfaces as a plain
403, which is the better failure.

**Two conclusions reached and retracted**, recorded so they aren't re-derived:
- *"`cf_clearance` is TLS/JA3-fingerprint-bound, so OkHttp can never work"* — **wrong**, and it nearly
  bought a Cronet dependency or a browser-backed transport. It rested on a curl matrix run against a
  clearance that had silently expired hours earlier. A `cf_clearance` lives ~30 min: timestamp every
  manual replay experiment before trusting it.
- *"Missing Chromium client hints cause the rejection"* — **unproven**; adding them changed nothing.
  `CfClearance.headers` keeps them (replaying the identity you were handed is defensible, and Cloudflare
  does list them in `critical-ch`), but nothing here rests on it.

**Reusable for source login** — the coordinator is generic. A login passes
`SiteSessionCompletion.WhenUserConfirms` and reuses the screen, cookie harvesting and navigation; what it
additionally needs is a **persistent** cookie store, which `SourceCookieJar`/`CloudflareCookieStore`
deliberately are not (a clearance is short-lived and IP-bound, so persisting it is a liability).

**Verified** end to end on Android and iOS: one challenge, no retry, browse loads. `static.mfcdn.nl` is
not protected (200 with no cookie), so covers and pages were never affected.

---

## 2026-07-29 — Covers can carry request headers (`MangaCover.headers`, schema v9)

**Decision:** the ABI's per-page `headers` gained a per-series twin — `MangaWire.coverHeaders` →
`MangaListing.coverHeaders` → `MangaCover.headers`, persisted in a new `manga.cover_headers` column
(schema **v9**, `Migration8To9`, `name: value` per line). Additive within the ABI version, exactly as
`Page.headers` was: an older bundle omits it and decodes to empty.

**Why:** Hipercool's image CDN 403s any request without `Referer: https://lerhentais.com/` — and checks
the value, so a generic or absent one fails. Pages were already fine (the reader applies `Page.headers`),
but **covers had no equivalent**, so the entire source rendered as blank tiles everywhere covers appear.

**Why on the cover and not looked up per screen:** covers are drawn in six places (library grid, browse
grid, two home rows, history, detail hero) from a *persisted* row, with no source in scope to ask and no
guest call in flight. Attaching the headers to the thing being fetched is the only shape that doesn't
require every screen to join its way back to a source. Cost is a redundant copy of a per-source constant
on each row; the alternative was a source lookup in six view models.

`coverImageRequest` (design system) is the single builder every cover component uses, mirroring the
reader's `Page.toImageRequest`; the two stay separate because pages run on their own `ImageLoader`.
`CacheSourceManga` now also **refreshes** the stored headers on its existing-row path — the one thing
about a cached row worth rewriting, since a row cached before its source gained headers would otherwise
stay a blank tile forever (browse returns early; only opening the series syncs details).

---

## 2026-07-29 — pt-BR source batch: Manhastro + Hipercool built, per-language bundles, source cookie jar

**Decision:** added two pt-BR sources and made the multi-language ones ship one bundle per language.
Surveyed seven requested sources; three could not be built and are recorded below rather than half-built.

> **Correction (2026-07-29, same day): Manhastro is NOT usable — the site requires an account.**
> Found by running it in the app, after the source had been built and tested. Tanuki has no source
> authentication (no login UI, no credential storage, no way for a guest to carry a session it did not
> earn itself), so the source is finished-but-blocked, exactly like Argos below. It stays bundled and
> building so the work isn't lost, not because it works; see the crate's module docs.
>
> The trap worth recording: its metadata endpoints (`/dados`, `/rank/diario`, `/lancamentos`,
> `/dados/{id}`) **answer anonymously**, and so does `/paginas/{id}` for some chapters — which is why
> every probe below passed and the gate was missed until the app hit it. An anonymous `200` from those
> is not evidence the source works; the reader is. Two later observations to re-check if it is resumed:
> `/paginas/{id}` now returns **sliced** page images (`1_part0.avif`, `1_part1.avif`), which this guest
> would emit as separate ABI pages and the reader would render as half images, and the origin
> intermittently answers `525`.

**Built:**
- **Manhastro** (`native/sources/manhastro/`, `api2.manhastro.net`) — plain JSON, no signing/gate.
  `/dados?page&nome&manga_id` is catalog + search + single-series lookup in one endpoint (the site's own
  frontend bundle documents the full filter set); `/rank/diario` and `/lancamentos` back Popular/Latest and
  return **whole records**, so neither needs the id→record second pass the reference extension does. Page
  images need no `Referer`. Titles are bilingual (`titulo_brasil` displayed, `titulo` → `originalTitle`).
- **Hipercool** (`native/sources/hipercool/`, `lerhentais.com`) — tRPC. Needs a `__st` cookie, minted by
  fetching the site root with an HTML `Accept` after a `401`, and an `x-flux-node` header without which
  `reader.chapterPages` alone answers FORBIDDEN. **No WebView needed** — the reference drives one to
  capture request headers, but those headers turn out to be exactly cookie + `x-flux-node`, both of which
  a guest can obtain itself. Each tRPC procedure is called on its own, not batched behind `auth.me`.

**Per-language bundles (reverses "one crate = one bundle"):** the ABI gives a source one `lang`, so
MangaDex and MangaFire now compile **twice** from one crate behind a `lang-en` / `lang-pt-br` cargo
feature — Mihon's one-extension-per-language model, and no ABI change. English keeps the plain
`mangadex`/`mangafire` id (no library re-pointing); pt-BR is `-pt-br`. `devSourceBundles` in
`shared/build.gradle.kts` is now `(bundleId, crate, feature)` with a per-bundle `--target-dir`.

**Source cookie jar (new, host-side):** `core/source/net/cookie/` — an in-memory per-host jar plus a Ktor
plugin, because the client had **no cookie jar at all** and a guest is stateless between exports, so a
site's `Set-Cookie` was dropped the moment a response was decoded. Installed **after** the Cloudflare
plugin so it sits innermost: the jar goes *under* both the guest's own `Cookie` header and the
`cf_clearance`, and a challenge replay passes back through it (so a cookie set by the challenge response
rides along). `applyClearance` now **merges** into the `Cookie` header instead of replacing it — replacing
sent the clearance and dropped the site session, i.e. "solved the challenge, still logged out". Ktor's own
`HttpCookies` is unusable here: it overwrites the header rather than merging.

**Also:** `native/source-api/src/util.rs` — `percent_encode`, `strip_html`, `parse_datetime_millis`,
`first_number` were being copy-pasted per crate; six sources made that untenable. MangaDex/MangaFire now
use the shared ones.

**Verified:** 50 tests green on **both** engines (Chicory + wasmi), including new
`ManhastroSourceEngineTest` (6), `HipercoolSourceEngineTest` (6) and `SourceCookiePluginTest` (4); all
seven `.wasm` bundles build; Android app assembles. Every endpoint above was probed against the live
sites first — the shapes are current, not taken on the reference extensions' word.

### Not built, and why

- **Gekkou Scans — dead.** The scan announced its shutdown; keiyoushi removed it as a dead source
  (#9358); `gekkou.site` is now a parked ad page and `gekkou.space` a placeholder. Nothing to implement.
- **Argos Scan — needs login.** `argoscomics.online` returns `401` on *every* endpoint; the reference
  carries a Discord `session` cookie. Revisit when source-level authentication exists. The sibling **Argos
  Comics** (`aniargos.com`) is live and auth-free but was ruled out for the same login reason.
- **MangaLivre (`toonlivre.net`) — blocked on a rotated proof-of-work.** Browse/search/details/chapters
  are reachable (`/api/mangas/search`, `/api/manga-by-slug/{id}` — the latter returns the full chapter list
  inline). The **page list** is not: `/api/chapter-token/{mangaId}/{chapterId}` and
  `/api/mangas/{id}/chapters/{id}` both answer `403 {"error":"Acesso negado. Assinatura ausente."}` without
  a proof-of-work cookie. The reference's parameters (`toon_h`, seed/LCG/FNV constants) were reimplemented
  and tested live — **still refused**, so the site has rotated them, and the current ones live in a heavily
  obfuscated `assets/security-*.js` (string-array rotation) that the reference's regexes no longer match.
  Two prerequisites beyond that: **a guest has no clock**, and the cookie payload is `{"ts":<now>,"pow":…}`
  — the fix is reading the `Date` response header rather than adding an ABI import; and the API 302s
  non-browser clients to a dead host (`www.mangalivre.net`) unless the request looks like a browser
  (HTTP/2 + `sec-ch-ua` + a real UA), which the guest can supply. **Revisit trigger:** the current PoW
  parameters, read out of a browser session (`document.cookie` on a chapter page names the cookie). Not
  shipped half-built: browse would work and opening any chapter would fail, which reads as a broken source.
  A speculative `/cdn-cgi/` redirect rule was added to `CloudflareDetection` and then **reverted** — the
  block MangaLivre actually serves is a plain `302` to another host, so the rule had no consumer.

  There is a **different site of the same name**, `mangalivre.to` (keiyoushi's "Manga Livre.to"), which is
  live, unblocked, and has no proof-of-work — but it is a **Madara** WordPress theme, i.e. pure HTML
  scraping: `POST /wp-admin/admin-ajax.php` (`madara_load_more`), `/?s=…&post_type=wp-manga`,
  `POST /manga/{slug}/ajax/chapters/`, all returning HTML fragments. Every Tanuki guest so far parses JSON,
  and the SDK's `strip_html` is a tag-stripper, not a parser. Doing Madara means **choosing an in-wasm HTML
  strategy** — a real parser with CSS selectors (`tl`, tens of KB; `scraper`/html5ever, hundreds) versus
  targeted string scanning — and that choice lands in every future guest's bundle size, so it is its own
  decision rather than a detail of one source. It is also the highest-leverage one available: most of the
  pt-BR scanlator sites in keiyoushi's catalogue are Madara.

---

## 2026-07-26 — MangaFire source built (JSON API + VRF signing wired)

**Decision:** built the MangaFire guest (`native/sources/mangafire/`). Key finding: **current MangaFire
is a clean JSON API** (`/api/titles`, `/api/titles/{hid}`, `/api/titles/{hid}/chapters`,
`/api/chapters/{id}`) — **no HTML scraping, no CSS selectors, no AJAX** — so the guest is serde-over-JSON
like MangaDex, no in-wasm HTML parser. Page images are direct URLs needing a `Referer`, carried via the
ABI's per-page `headers`. All five exports + `meta()` implemented.

**VRF signing (`vrf.rs`) — now wired** from the reference `VrfSigner.kt`: a 3-stage substitution cipher
(`prev = TABLE[(data[i] ^ KEY[i%len] ^ prev) & 0xFF]`, IVs `0x5A/0x35/0xBA`), keyed by six base64
constants stored verbatim and decoded at runtime (as the reference does — no hand-transcription of
bytes). The **signed input** is the `/api`-stripped path + params **sorted by key** with `key[]`→`key[i]`
indexing over **decoded** values; the wire request re-emits encoded values + the `vrf` token.

**DTOs** confirmed against the real `Dto.kt`: `PosterDto{small,medium,large}` (cover = large→medium→small),
`EntityDto{title}` (authors joined), details/pages wrapped in `{data:…}` (lists are `{items,meta}`),
`meta.hasNext` drives pagination, `createdAt` epoch seconds ×1000, permalink `/title/{hid}-{slug}`, status
`releasing/finished/on_hiatus/discontinued` → ongoing/completed/hiatus/cancelled. Synopsis HTML is
tag-stripped in-guest (no DOM parser in wasm).

**Verified:** `MangaFireSourceEngineTest` (canned JSON, CapturingHost) exercises meta + a signed
`manga_list` (asserts the `order[views_30d]` param + a `vrf` token + parsing) + `chapter_pages` (asserts
the `Referer` header) — green on **Chicory and wasmi**.

**Remaining (needs the live site — the project doesn't hit live services in tests):** confirm the `vrf`
token is accepted by the server (only checkable live) and tune the WebView/WKWebView Cloudflare solvers
against a real challenge. Smaller follow-ups: list volumes (`/api/titles/{hid}/volumes`), a raster
`icon_url`, and whether `type` is the right `scanlator` stand-in.

---

## 2026-07-26 — Cloudflare interceptor scaffolded (ahead of MangaFire)

> **Largely superseded (2026-07-30) — see the entry at the top of this log.** The plugin, the per-host
> store and the `CloudflareChallengeSolver` seam all survived contact with a live target; the *solvers*
> did not. Every "needs a live target to finish" item below was answered by finishing it, and the answer
> to most of them was that an offscreen browser cannot solve a managed challenge at all.

**Decision:** built the Cloudflare interceptor as a **Ktor client plugin** on the one shared source
client (`core/source/net/cloudflare/`), with the browser-engine challenge solver behind an
`expect`/`actual`-style DI seam (`CloudflareChallengeSolver`): Android `WebView`, iOS headless
`WKWebView`. Reverses the 2026-07-22 deferral now that it's the agreed next step. Flow: attach any stored
`cf_clearance` for the host → send → on a `403`/`503` Cloudflare challenge, the solver opens a WebView,
extracts `cf_clearance`, the plugin stores it per host and replays **once**. The `cf_clearance` is bound
to the User-Agent, so the solved UA is part of the clearance and replayed with it.

**Verified:** the common interceptor logic (detect → solve → retry, replay-without-resolve,
unsolved→surface) is covered by `CloudflareInterceptorTest` (MockEngine + fake solver), green on Chicory
**and** wasmi; androidMain/iosMain compile; the full Android app assembles (Metro graph resolves the new
bindings). Solvers use the common `DispatcherProvider.main` (WebView/WKWebView are main-thread-only —
`io` would crash), not a hardcoded `Dispatchers.Main`.

**Scaffold, not done — needs a live Cloudflare target (MangaFire) to finish/verify:**
- The WebView/WKWebView solvers' challenge *timing* (poll interval/timeout) and whether a challenge needs
  interaction are first estimates; a managed-challenge/Turnstile can only be tuned live.
- iOS doesn't yet read WebKit's real default UA (needs an `evaluateJavaScript("navigator.userAgent")`
  round-trip); today it replays the handed UA or `null`.
- `CloudflareCookieStore` is in-memory (process-lived); persisting per host with the cookie's expiry is a
  later upgrade if cold-start challenge latency bites.
- ~~Image loaders bypass it.~~ **Done (2026-07-26):** the cover and page Coil loaders
  (`newTanukiImageLoader` / `newTanukiPageImageLoader`) now fetch over the shared client via
  `KtorNetworkFetcherFactory(httpClient = …)`, wired from `AppGraph.sourceHttpClient` in `App.kt`, so
  image fetches inherit the `cf_clearance` a source's API calls solved for the same host.
- ~~Page-image `headers` ABI field.~~ **Done (2026-07-26):** `Page`/`PageWire`/`wire.rs Page` gained an
  additive `headers` map (no ABI bump); the reader applies it via Coil `httpHeaders()` on the page
  ImageRequest (render + prefetch share one builder). MangaFire's `Referer` now has a path; the guest
  emits it when that source lands.

---

## 2026-07-26 — Page descrambling is an app-applied descriptor, not guest-processed bytes

**Decision:** for scrambled page images (MangaFire), the guest emits a **closed-vocabulary transform
descriptor** on each `Page` (v1: `TileShuffle { cols, rows, permutation }`) computed during
`chapter_pages`; the **app** fetches the image (through the Cloudflare-carrying client), decodes it, and
applies the transform with **native pixel ops** (a Coil `Transformation`). Bytes are not sent into the
guest for processing. Also adds per-page `headers` (Referer/hotlink). Full design + type sketches:
`design-page-image-abi.md`.

**Why:** the interpreters have no JIT, so decoding+reshuffling a multi-MB image in-guest would cost
seconds and bloat linear memory; the descriptor is tens of bytes. Keeping the transform a closed set the
app implements mirrors the listings decision (2026-07-23) — the app can reason about and perform every
transform, never run arbitrary source logic. MangaDex's direct-URL path is unchanged. The descriptor is
computed from the scramble key the page-list AJAX already returns, so there is **no new export and no
extra per-page fetch** — only `Page` grows.

**Cost / trade:** a scramble scheme the vocabulary can't express (byte-level XOR/decrypt) isn't supported
until the vocabulary + app grow. The escape hatch — a guest `process_page_image(context, bytes)` export
(Aidoku's model) — is **designed to be additive later**, built only when a real source needs it.

**Versioning:** additive on the wire, but an old host that ignored `transform` would show a *scrambled*
image — a silent wrong result. So bump `ABI_VERSION`/`SourceAbi.VERSION` (v4 → v5) together, making a
mismatched host refuse the source loudly.

**O1 resolved (2026-07-26): MangaFire does not scramble today, so descramble is deferred.** Checked the
canonical keiyoushi extension: `getPageList` returns `page.url` directly, `PageDto` is just `{url}`, and
the latest image commit (`cf202f5`) only *added a `Referer` header*. MangaFire's real barriers are
Cloudflare/WAF + VRF request signing, not scrambled bytes. Building the descramble tier now would repeat
the "build blind against no live target" mistake the Cloudflare interceptor was deferred to avoid.

So the plan splits:
- **Build now (MangaFire):** add only per-page `headers` to `Page`/`PageWire` (the `Referer`) — additive,
  no ABI bump on its own — and route the page/cover image loader through the Cloudflare-carrying client.
  VRF signing is guest-side (no ABI change).
- **Deferred to the first source that actually scrambles:** the `transform`/`PageTransform` tier (with the
  ABI bump), and the guest byte-processing escape hatch. The full design + `TileShuffle` vocabulary is
  kept on the shelf in `design-page-image-abi.md`; O1's count-vs-piece-size answer is recorded there for
  when that source appears. (Scrambling is a real pattern — older MangaFire builds shipped an
  `ImageInterceptor` — so the design isn't wasted.)

**Trigger to build the descramble tier:** the first scrambling source. **Trigger for the header change:**
the MangaFire milestone, alongside the Cloudflare interceptor (the page loader must move onto the
interceptor-carrying client for MangaFire to fetch at all).

---

## 2026-07-23 — Listings are a closed contract vocabulary, not a source-invented one

**Decision:** the ABI defines the set of browse listings (`popular`, `latest` for now) as an enum on
both sides — `SourceListing` (host) / `Listing` (`wire.rs`, guest). A source declares only *which* of
them it serves; it can neither invent a listing nor name one. Unknown names in a bundle's metadata are
dropped by the mapper rather than failing the decode, and the app renders listings in the enum's order,
not the order the bundle listed them.

**Why:** with source-declared `key`+`name` pairs the app could not know what any source would offer
until it loaded it, could not reason about a listing (no `if popular` without branching on an opaque
string the docs called un-branchable), and — the concrete breakage — could not translate the tab label,
since a source ships one language. A closed set means the app knows up front what a source *can* be
asked for and only decides what to show: both tabs, one, or none (default catalog, no tab row).

**Cost:** adding a listing is now an ABI change (new variant + `listing_*` strings in every language +
an ABI version bump), not a source-side edit. That is the intended trade — the app owns presentation.

**Consequence for the deferred catalog cache below:** listing keys are no longer opaque, so per-listing
freshness *is* something the app may decide. That paragraph's advice to have the source declare a TTL
no longer applies.

---

## 2026-07-22 — Defer a browse-catalog cache until an expensive (scraping) source

**Decision:** do not build a cache over `SourceLoader.fetchMangaList` yet.

**Why:** the expensive part — cover *images* — is already cached (Coil memory + disk, see
`TanukiImageLoader`). Re-opening a source's Popular/Latest tab only re-fetches a few KB of JSON plus a
WASM parse; the covers themselves paint from cache. That's a real but tiny, infrequent, unmeasured cost,
and a cache would add a TTL to tune, a refresh affordance, and staleness risk for a marginal win.

**Heuristic:** a catalog cache's value scales with how expensive a source's fetch is. MangaDex is one
clean JSON call (caching earns ~nothing). A scraping source (HTML parse, multiple round-trips, image
descrambling) makes re-fetching genuinely expensive.

**Trigger to revisit:** the first scraping source — **MangaFire** — which is also the first Cloudflare
target, so the catalog cache and the Cloudflare interceptor land at the same milestone rather than as
speculative builds now.

**When it's time:** in-memory only (not Room — catalogs are churny); a `SourceCatalog` between
`SourceBrowseViewModel` and `SourceLoader`, keyed by `(sourceId, listing, query, page)`, LRU-bounded,
with a uniform modest TTL + explicit refresh. Per-listing freshness ("Latest" going stale faster than
"Popular") is the app's call to make if it turns out to matter — the listing vocabulary is closed and
app-owned (see the 2026-07-23 entry), so this no longer has to be deferred to source metadata. If MangaDex ever throttles, the fix is request politeness (rate-limit
headers / debounce), not a results cache.

---

## 2026-07-22 — Defer the Cloudflare interceptor until a Cloudflare-protected source

**Decision:** do not build the Cloudflare interceptor (WebView challenge-solving + `cf_clearance`
extraction) yet.

**Why:** it can't be verified against current sources. The interceptor's whole job is detect a CF
challenge (403/503) → solve it in a headless WebView → extract `cf_clearance` → retry. MangaDex's API
isn't CF-protected, so building it now means writing two WebView bindings (Android `WebView`, iOS
`WKWebView` via `expect`/`actual`) and challenge-detection logic that nothing ever exercises — building
blind.

**Trigger to revisit:** **MangaFire** (source #2), the first real CF target — bring them up together so
the source provides a live challenge and the interceptor is testable. It's independent of everything
else, so there's no cost to waiting. When it lands, the cover image loader's `TODO(cloudflare)` should
route through the same interceptor-carrying Ktor client.

---

## 2026-07-22 — Image resolution is source/ABI-controlled, not Coil; adaptive quality is a reader-path concern

**Decision:** don't try to control cover download quality by network condition app-side.

**Why:** image *resolution* (bytes downloaded) is set by the source-provided URL, not by Coil. The guest
`.wasm` emits the cover URL (e.g. MangaDex `.../{id}/{fileName}.512.jpg`, where `.512` is MangaDex's
thumbnail-size suffix). Coil only controls *decode/memory* quality (downsampling, RGB565); it can't
rewrite the URL to fetch a smaller image on a slow connection. Covers are ~30–80 KB and `AsyncImage`
already downsamples to the tile bounds, so adaptive quality earns little here.

**Trigger to revisit:** the **reader-page path** (full-size pages, MBs each), where bytes actually
matter. True network-adaptive quality needs the **source ABI to expose size variants** + the app picking
one by network type (`expect`/`actual`: Android `ConnectivityManager`, iOS `NWPathMonitor`). Design that
into the source ABI when building the reader — don't retrofit it onto covers.
