# Decision log

Short, dated records of non-obvious engineering decisions — what was decided, why, and (for
deferrals) the concrete trigger to revisit. Newest first. This is the version-controlled companion to
the design doc (`session-source-and-browse.md`); keep entries brief and link to code where useful.

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
