# Decision log

Short, dated records of non-obvious engineering decisions — what was decided, why, and (for
deferrals) the concrete trigger to revisit. Newest first. This is the version-controlled companion to
the design doc (`session-source-and-browse.md`); keep entries brief and link to code where useful.

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
with a uniform modest TTL + explicit refresh. Do **not** hardcode "Popular vs Latest" freshness in the
app — listing keys are opaque; if per-listing TTL is truly needed, have the source declare it in
`SourceListing` metadata. If MangaDex ever throttles, the fix is request politeness (rate-limit
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
