package app.tanuki.core.domain.source.model

import app.tanuki.core.domain.common.model.Text

/**
 * One browseable listing a source offers — the "Popular", "Latest Updates", "Staff Picks" style tabs
 * of a Browse screen.
 *
 * Listings are **declared by the source**, not by the app: a source advertises them in its metadata
 * ([SourceMeta.listings]) and the Browse screen renders one tab per entry. That keeps the app from
 * hardcoding a listing vocabulary that only some sources implement — a source with a single default
 * catalog simply declares no listings, and the screen falls back to the unfiltered browse.
 *
 * @property key Source-native listing identifier (e.g. `"popular"`, `"latest"`), passed straight back
 *   to the source as [app.tanuki.core.domain.source.SourceLoader.fetchMangaList]'s `listing` argument.
 *   Opaque to the app — never branch on its value.
 * @property name Human-readable tab label as the source provides it.
 */
data class SourceListing(
    val key: String,
    val name: Text
)
