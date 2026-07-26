# Design — Page-image / descramble ABI

Status: **proposed** (design-first; nothing built). Prerequisite for **MangaFire** (source #2), which
serves Cloudflare-protected **and** scrambled page images. Read alongside `decisions.md` and
`session-source-and-browse.md`.

The goal is to settle the page-image contract *before* MangaFire, because it is the one part of the
source ABI that a scrambling source forces to change — discovering it mid-implementation would mean an
ABI bump on top of a half-written source.

---

## 0. Research findings — MangaFire does not scramble today (2026-07-26)

Investigated the real MangaFire implementation before committing to the descramble tier. **MangaFire
currently serves clean (unscrambled) images.** Evidence, from the canonical
[keiyoushi/extensions-source](https://github.com/keiyoushi/extensions-source) extension:

- `getPageList()` returns `Page(index, imageUrl = page.url)` directly; `PageDto` has a single `url` field
  — no interceptor, no bitmap split, no key/offset.
- The most recent image-related change ([`cf202f5`](https://github.com/keiyoushi/extensions-source/commit/cf202f5),
  "Fix image couldn't be loaded") **added a `Referer: $baseUrl/` header** — it did not touch descramble.
- MangaFire's actual barriers are **Cloudflare/WAF** ([issue #4035](https://github.com/keiyoushi/extensions-source/issues/4035))
  and **VRF request signing** (the extension's `VrfSigner.kt`) — request-side concerns, not image bytes.
- Scrambling *was* present in older MangaFire builds (a v1.4.16 APK shipped an `ImageInterceptor`), so
  grid-shuffle descrambling is a real pattern that can reappear or turn up in another source — the design
  below stays valid as the blueprint for the **first source that actually scrambles**.

**Consequence for the plan (O1 resolved):**
- The **descramble tier is DEFERRED** — building it now would repeat the exact "building blind against no
  live target" mistake the Cloudflare interceptor was deferred to avoid. `TileShuffle` and the O1
  count-vs-piece-size question do not block MangaFire.
- What MangaFire actually needs from the page ABI is **per-page request headers** (the `Referer`), plus
  routing the image fetch through the **Cloudflare-carrying client** and VRF signing (guest-side, already
  expressible via `http_request` — no ABI change). See the revised §5.

---

## 1. Problem

Today a page is a **URL descriptor and nothing else**:

```
chapter_pages (guest) → Vec<Page { index, image_url, url }>      # wire.rs / Wire.PageWire
   → domain Page(index, imageUrl, url)                            # core/domain
   → ReaderPage: AsyncImage(model = page.imageUrl, LocalPageImageLoader)   # Coil fetches the URL directly
```

The guest is entirely out of the image path — it hands back a URL and Coil fetches it. That is perfect
for MangaDex (a plain CDN URL) and breaks for MangaFire in two independent ways:

1. **Cloudflare.** The page image host is CF-protected, and the page loader (`LocalPageImageLoader`) is a
   *separate* Coil client that does not carry `cf_clearance`. A direct fetch gets a 403 challenge page.
2. **Scrambling.** MangaFire ships each page as a JPEG whose tiles are shuffled by a per-image key; the
   real image only exists after the tiles are put back. Coil fetching a URL cannot produce it, and this
   is not something a URL can express.

MangaFire also needs **per-image request headers** (a `Referer`/hotlink guard) that the current `Page`
can't carry.

## 2. Requirements

**Functional**
- F1. Keep MangaDex's zero-cost path: a source that returns a direct URL still just works.
- F2. Let a source declare that a page's bytes must be **transformed** before display, and describe the
  transform precisely enough that the app can perform it.
- F3. Let a source attach **per-request headers** to a page fetch (Referer, UA), independent of transform.
- F4. Route page (and cover) fetches through the **Cloudflare-carrying shared client** — orthogonal to
  transform, but the page path must opt into it.
- F5. Preserve reader caching: the *displayed* (post-transform) image must be memory/disk cached, so
  paging back to a page doesn't re-fetch or re-descramble.

**Non-functional**
- N1. **Heavy pixel work must not run in the WASM interpreter.** wasmi/Chicory have no JIT; decoding and
  reshuffling a multi-MB image there would cost seconds and balloon linear memory. (Contrast: metadata
  JSON is KB.)
- N2. **Minimal bytes across the WASM boundary.** A page image is 1–5 MB; the metadata to describe a
  descramble is tens of bytes. Move the latter, never the former, if avoidable.
- N3. **Closed, app-owned transform vocabulary**, consistent with the 2026-07-23 "listings are a closed
  contract" decision — the app must be able to *reason about* and *implement* every transform, not run
  arbitrary source-supplied logic.
- N4. Additive on the wire where possible; loud (version-gated) where a silent-wrong-image is the failure.
- N5. Both engines, always (`testAndroidHostTest` + `iosSimulatorArm64Test`).

## 3. Options considered

### Option A — Guest returns transformed bytes
New export `page_image(pageKey) → bytes`: the guest fetches (via `http_request`, so through CF), decodes,
descrambles, and returns the final image bytes; the app treats the source as the byte provider.

- ➕ Fully general; any transform is just Rust; no app-side vocabulary.
- ➖ Violates **N1/N2**: multi-MB images cross the boundary and are decoded+re-encoded in the interpreter.
  Needs a wasm32 image codec in the guest (the `image` crate is large and interpreted-slow). Rejected as
  the default; kept as a future escape hatch (§5, deferred).

### Option B — App fetches bytes; guest emits a descramble **descriptor** (recommended)
`chapter_pages` gains, per page, an optional structured **transform descriptor** (a closed vocabulary)
plus optional headers. The app fetches the URL through the CF client, decodes to a native bitmap, applies
the descriptor with **native pixel ops**, and displays/caches the result.

- ➕ Heavy work is native (fast, GPU/JIT); only tens of bytes cross the boundary (**N1/N2**). MangaDex
  path unchanged (**F1**). The descriptor is computed once, during `chapter_pages` — **no extra per-page
  round trip and no new export.**
- ➖ Needs a closed transform vocabulary the app implements (**N3**); a scheme the vocabulary can't
  express isn't supported until the vocabulary (and app) grow — the same trade the listings enum makes.

### Option C — App fetches, hands bytes back to guest to post-process (Aidoku's `process_page_image`)
`chapter_pages` returns URLs; a new export `process_page_image(context, bytes) → bytes` lets the guest
transform bytes the *app* fetched (so CF/networking stays app-side, unlike A).

- ➕ MangaDex stays a direct URL; general transforms without an app vocabulary.
- ➖ Still violates **N1/N2** (bytes cross the boundary, interpreted processing). Strictly more machinery
  than B for MangaFire, whose scramble *is* descriptor-expressible.

## 4. Recommended design — layered, Option B now

Three tiers, cheapest first. A page is exactly one of:

1. **Direct** — `imageUrl` set, no transform. App fetches and shows it. (MangaDex — unchanged.)
2. **Transform** — `imageUrl` + a `PageTransform` descriptor (+ optional `headers`). App fetches, decodes,
   applies the descriptor natively, shows/caches. (MangaFire.)
3. **Deferred escape hatch** — guest byte-processing (Option C) for schemes the vocabulary can't express
   (byte-level XOR/decrypt). *Designed to be addable later without a break; not built now* (§5).

The crucial property that makes tier 2 cheap: **the descriptor is produced during `chapter_pages`.**
MangaFire's page-list AJAX call already returns each image URL plus its scramble key; the guest computes
the permutation from the key right there and emits it on the `Page`. No image bytes are fetched by the
guest, no per-page export, nothing large crosses the boundary.

### 4.1 Wire shape (both sides change together)

Guest — `native/source-api/src/wire.rs`:

```rust
/// One page of a chapter. Mirrors `Wire.PageWire`.
#[derive(Serialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct Page {
    pub index: i32,
    #[serde(skip_serializing_if = "Option::is_none")] pub image_url: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")] pub url: Option<String>,
    /// Per-request headers for fetching this page (Referer / UA). Empty = none.
    #[serde(skip_serializing_if = "BTreeMap::is_empty", default)]
    pub headers: BTreeMap<String, String>,
    /// How the fetched bytes must be transformed before display. Absent = show as-is.
    #[serde(skip_serializing_if = "Option::is_none")] pub transform: Option<PageTransform>,
}

/// CLOSED vocabulary of page transforms the app implements natively. A guest can only pick a variant;
/// it cannot describe arbitrary logic. Adding a variant is an ABI change (bump + app support), exactly
/// like adding a Listing.
#[derive(Serialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum PageTransform {
    /// Unshuffle a grid of tiles. The image is divided into `cols` × `rows` tiles (row-major); the tile
    /// currently at display position `i` actually belongs at `permutation[i]`. Dimension-independent:
    /// the app derives tile size from the decoded image and the grid, so the guest needs no image fetch.
    TileShuffle { cols: u32, rows: u32, permutation: Vec<u32> },
}
```

Host — `core/source/.../abi/Wire.kt` (mirror, additive fields + a sealed `PageTransform`):

```kotlin
@Serializable
data class PageWire(
    val index: Int,
    val imageUrl: String? = null,
    val url: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val transform: PageTransformWire? = null,
)

@Serializable
@JsonClassDiscriminator("kind")
sealed interface PageTransformWire {
    @Serializable @SerialName("tile_shuffle")
    data class TileShuffle(val cols: Int, val rows: Int, val permutation: List<Int>) : PageTransformWire
}
```

Unknown `kind` (a source built against a newer contract) must **degrade loudly, not silently**: the mapper
maps an unrecognized transform to a sentinel the reader renders as a "page needs a newer app" error, never
as the scrambled bytes. (Mirrors how unknown listing names are dropped — but here we cannot *show*
un-transformed bytes, so it is an explicit error page, not a silent pass-through.)

### 4.2 Domain + reader integration (Coil)

Domain `Page` gains `headers: Map<String,String>` and `transform: PageTransform?` (a domain sealed type,
mapped from the wire). The reader stops passing a bare URL to Coil and instead builds a request that
Coil can fetch (through the CF client), decode, transform, and cache:

```kotlin
ImageRequest.Builder(context)
    .data(page.imageUrl ?: page.url)
    .httpHeaders(page.headers.toNetworkHeaders())          // F3
    .apply { page.transform?.let { transformations(it.toCoilTransformation()) } }  // F2
    .build()
```

- **CF (F4):** `LocalPageImageLoader` is rebuilt on the **shared, interceptor-carrying Ktor client**
  (the same wiring the cover loader's `TODO(cloudflare)` needs). This is a Cloudflare-milestone task, not
  an ABI concern, but the page path depends on it.
- **Descramble (F2):** each `PageTransform` maps to a Coil `Transformation` (`TileShuffle` → a tile-blit
  over the decoded `Bitmap`). Pixel work is native Skia/Android bitmap ops — implemented once against
  Coil 3's `Bitmap` (Skia on iOS, platform on Android; `expect`/`actual` only if a pixel op differs).
- **Caching (F5):** Coil keys a transformed result by the transformation's `cacheKey`, so the descrambled
  page is memory+disk cached — paging back neither re-fetches nor re-descrambles.

### 4.3 Data flow

```
chapter_pages (guest, MangaFire)
  ├─ AJAX page list → [ {url, scrambleKey}, … ]      (one http_request, through host→CF)
  ├─ per page: permutation = descramble_algo(scrambleKey)   (guest Rust; tiny)
  └─ Vec<Page { image_url, headers, transform: TileShuffle{cols,rows,permutation} }>
       → host maps to domain Page(…, transform)
          → reader builds ImageRequest(data=url, headers, transformations=[Descramble])
             → Coil: fetch (CF client) → decode bitmap → TileShuffle transform → cache → display
```

Only the small `Page` descriptor crosses the WASM boundary; the multi-MB image never does.

## 5. What we build now vs defer

Revised after §0: MangaFire needs headers + CF, **not** descramble. So the *only* page-ABI change to
build now is per-page `headers`; both `transform` tiers are deferred against a real scrambling source.

**Now (for MangaFire):**
- Wire: add **only** `headers` to `Page`/`PageWire` (guest attaches the `Referer` the app must send when
  fetching that page). Keep `Wire.kt` ↔ `wire.rs` field-for-field identical (one commit). This is
  *additive* — an old host ignoring `headers` just omits a header, a recoverable failure, **not** a
  silent wrong image — so it does **not** force an ABI bump on its own (contrast §6).
- Domain `Page` + mapper gain `headers`; reader passes them into the `ImageRequest`.
- Route `LocalPageImageLoader` (and the cover loader) at the **CF client** — the Cloudflare milestone.
- VRF signing stays entirely guest-side (the guest sets its own `http_request` headers) — no ABI change.

**Deferred — tier-2 descramble (this doc's main design), against the first scrambling source:**
- Add `transform` + `PageTransform`/`PageTransformWire` (`TileShuffle`), the unknown-transform sentinel,
  the Coil `Transformation`, and the ABI bump (§6). Confirm that source's real grid model to close O1
  (§7) at that point. Everything above the wire is already designed here.

**Deferred — tier-3 escape hatch (Option C) — additive, no break when it lands:**
- A `process_page_image(context, bytes) → bytes` export for byte-level schemes (decrypt/XOR) a descriptor
  can't express. Trigger: the first source that needs it. Because tier-2 pages are marked by `transform`
  and tier-3 by a *different* new field, adding it later is additive — an old host simply never asks a
  new-enough source to post-process.

## 6. Versioning decision

Adding optional `headers`/`transform` to `PageWire` is technically **additive** (old readers ignore
them). But a host that ignores `transform` shows a **scrambled image** — a silent wrong result, which
N4/the project's "loud not silent" rule forbids. Therefore: **bump `ABI_VERSION` / `SourceAbi.VERSION`
together** (v4 → v5) so an old host refuses a v5 source rather than mis-rendering it. The cost (a source
must be built against ≥ the host) is the intended, visible failure.

## 7. Open decisions (resolve before coding)

- **O1 — Grid model: count vs pixel size. [RESOLVED — moot for MangaFire; see §0.]** MangaFire does not
  scramble today, so this no longer blocks anything. The standing design answer for the *first actual
  scrambling source*: `TileShuffle` is defined by tile **count** (`cols`×`rows`), dimension-independent so
  the guest needs no image fetch. If that source scrambles by a **fixed piece size** (variable count per
  image), prefer (a) the guest reads only the JPEG SOF dimension header via a **ranged fetch** (a few KB)
  and converts to a count grid — keeping the algorithm guest-side — over (b) a `TileShufflePx` variant
  that would push the permutation algorithm app-side (N3 forbids). Confirm against that source's real
  scheme when it arrives.
- **O2 — Error semantics for an unknown/failed transform.** Confirm the reader gets a distinct
  `ReaderState`/page state for "transform unsupported" vs "fetch failed" so the message is truthful.
- **O3 — Remainder tiles.** When image dims aren't divisible by the grid, define whether edge tiles get
  the remainder (recommended) or the source guarantees divisibility. Pin this in the `TileShuffle` doc so
  guest and app agree pixel-for-pixel.

## 8. Trade-offs (summary)

| Concern | Recommended (B/descriptor) | A (guest bytes) | C (post-process) |
|---|---|---|---|
| Pixel work location | native (fast) | interpreter (slow) | interpreter (slow) |
| Bytes across WASM boundary | descriptor only | full image ×2 | full image ×2 |
| MangaDex cost | unchanged | unchanged | unchanged |
| Generality | closed vocabulary | total | total |
| New export needed | no | yes | yes |
| Best fit | MangaFire (grid shuffle) | — | byte-level schemes (deferred) |

The recommendation deliberately trades generality for speed and boundary-cleanliness, and buys the
generality back later — only if a real source needs it — via the deferred post-process hook.