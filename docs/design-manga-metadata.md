# Manga metadata enrichment (Phase 5b) — design

> Status: **built** (2026-10-11, Phase 5b) — see the progress log in `swiftui-migration.md` for what shipped
> and what was deferred (cover in library/history lists, Android rails, global search). Designed 2026-10-11. Phase 5a shipped the SwiftUI manga page with room for this.
> Decisions taken with the owner: AniList as the primary provider (no API key), MangaDex volume covers
> for the cover picker, MyAnimeList id kept for linking; enriched metadata takes over by default with a
> per-series "Use source info" switch.

## Goal

The same series looks the same whatever source it was found in: one title, cover, synopsis, genre set,
status, plus what sources never carry — a score, tags, characters, reviews, recommendations — and a
choice of covers. Chapters always come from the source; only *descriptive* metadata is unified.

## Feasibility (verified live)

| Need | Where it comes from | Verified |
|---|---|---|
| Exact identity | MangaDex details `links { mal, al, mu, … }`; MangaFire details `malId`, `anilistId`, `links {mal, al, mu, md, mb}` | ✓ |
| Score, popularity, genres, ranked tags (+ spoiler flag), cover, banner, status, year | AniList `Media(idMal:)` / `Media(id:)` GraphQL, no key, ~90 req/min | ✓ |
| Characters (with images), staff | AniList `characters`, `staff` | ✓ |
| User comments | AniList `reviews` (summary + score + body) | ✓ |
| Recommendations | AniList `recommendations` (carry `idMal`) | ✓ |
| Several covers to choose from | MangaDex `GET /cover?manga[]=…` (one per volume), AniList cover, the source's own cover | ✓ (MangaDex covers API) |
| MyAnimeList's own score / pictures | Official MAL API needs a client id; Jikan (unofficial) timed out from the dev network | deferred |

Only sources that publish no ids (Hipercool, Manhastro today) need a fuzzy match: an AniList title
search the user confirms once ("Is this the right series?").

## Architecture

```
source (WASM)  ──manga_details──▶ MangaListing { …, anilistId?, malId? }   (new optional ABI fields)
                                        │
                    SyncSourceMangaDetails persists ids ─▶ manga_tracker_link
                                        │
:core:metadata (new, Ktor)  MetadataProvider ◀── EnrichManga use case ──▶ series_metadata (cache)
   AniListProvider (GraphQL)           │                                        │
   MangaDexCovers                      ▼                                        ▼
                               ObserveMangaMetadata(mangaId) ── merged with source data by precedence
```

- **`:core:domain`**: `MetadataProvider` interface (`lookup(anilistId | malId)`, `search(title, year?)`),
  `SeriesMetadata`, `TrackerLink`, use cases `EnrichManga`, `ObserveMangaMetadata`, `LinkManga`,
  `UnlinkManga`, `SetCoverChoice`. Runtime-agnostic, like `SourceLoader`.
- **`:core:metadata`** (new module): `AniListProvider` (GraphQL over the app's Ktor client — *not* the
  Cloudflare/source client; AniList is a plain API), `MangaDexCoverProvider`, a token-bucket rate limiter
  (AniList 90/min, MangaDex 5/s), DTO → domain mappers. One Metro binding swaps providers.
- **ABI (additive)**: `Manga` gains optional `anilist_id: Option<i64>`, `mal_id: Option<i64>`. MangaDex
  fills them from `links.al`/`links.mal`, MangaFire from `anilistId`/`malId`. No version bump.

### Storage (Room, one migration)

- `manga_tracker_link(manga_id PK → manga, anilist_id, mal_id, matched_by: SOURCE_ID|SEARCH|USER,
  use_source_info BOOL default 0, cover_choice TEXT?)`
- `series_metadata(anilist_id PK, mal_id, payload JSON, fetched_at)` — keyed by **AniList id**, so two
  library entries of the same series (from different sources) share one row: that is what "keyed by
  manga, not by source" means in storage. JSON payload because the shape is presentation data read
  whole, never queried by column.
- TTL: 7 days for metadata, refreshed lazily when the detail page opens (same pattern as the details
  sync's staleness gate); covers list fetched on demand when the picker opens.

### Precedence (one rule, in the ViewModel's projection)

`linked && !useSourceInfo` → title (English, else romaji), cover (user's choice, else AniList's),
synopsis (AniList, HTML stripped), genres (AniList), status (AniList). Otherwise the source's. Chapters,
reading mode, library state: always local/source. The library grid and history use the same resolved
title/cover (a `resolved_cover_url` column on the link, so list queries don't parse JSON).

## UI (both platforms; slots already in the Phase 5a page)

- Header: score badge (★ 8.4 · #312) and "Matched on AniList · Change" under the status line.
- Genre chips (AniList genres) + a "Tags" row; spoiler tags blurred until tapped.
- Cover: long-press the hero cover → picker (AniList, MangaDex volumes, source) → stored choice.
- Sections below the info rows: **Characters** rail (image + name + role), **Reviews** (summary, score,
  "Read more" opens the AniList review), **Recommendations** rail (tapping searches installed sources).
- Settings → per series: "Use source info" switch, "Unlink", "Search again".

## Risks / open points

- AniList rate limit (90/min): enrich on detail open only, never in bulk on library refresh; queue with
  backoff on 429.
- Fuzzy matches can be wrong → never auto-link a search result; the user confirms.
- Review bodies are long Markdown/HTML → show summaries; full text links out.
- MyAnimeList score: add a `MalProvider` later behind the same interface once a client id is registered.
- Offline: metadata is cached; nothing blocks the page if AniList is unreachable (source data shows).

## Estimate

~6–9 dev-days: ABI ids + source fills (1), Room + migration (1), `:core:metadata` AniList + covers +
limiter with tests (2–3), precedence + use cases (1), UI on both platforms incl. cover picker and
link/unlink (2–3).
