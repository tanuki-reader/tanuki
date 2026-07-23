package app.tanuki.core.source.abi

import app.tanuki.core.domain.chapter.model.ChapterNumber
import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.common.model.toUrlOrNull
import app.tanuki.core.domain.manga.model.PublishStatus
import app.tanuki.core.domain.source.model.ChapterListing
import app.tanuki.core.domain.source.model.MangaListing
import app.tanuki.core.domain.source.model.MangaPage
import app.tanuki.core.domain.source.model.Page
import app.tanuki.core.domain.source.model.SourceListing
import app.tanuki.core.domain.source.model.SourceMeta
import kotlin.time.Instant

/**
 * Wire → domain mappers for the source ABI. Every mapping here targets a **pre-persistence** shape
 * ([SourceMeta], [MangaListing], [MangaPage], [ChapterListing], [Page]) — source-native types with no
 * local `Uuid` and no user state — so the conversion is total and lossless.
 *
 * Deliberately absent: `MangaWire → Manga` and `SourceMetaWire → Source`. Those domain types carry a
 * local `Uuid` and user state (favorite, reading status, dates) that exist only *after* a manga/source
 * is persisted — assigning them is the job of an install/add-to-library use case backed by the
 * repositories, not the ABI layer. The loader ([app.tanuki.core.source.WasmSourceLoader]) resolves the
 * `Source` identity itself (install-on-first-load); browsed manga stay [MangaListing]s until added.
 */

/**
 * Maps source self-description to the lightweight catalog entry the Browse screen lists, carrying
 * through the reported [Wire.SourceMetaWire.version] (for update checks) and the listings the source
 * declares (the Browse tabs).
 */
fun Wire.SourceMetaWire.toDomain(): SourceMeta =
    SourceMeta(
        id = id,
        name = Text(name),
        lang = Text(lang),
        version = version,
        iconUrl = iconUrl.toUrlOrNull(),
        listings = sourceListingsOf(listings),
    )

/**
 * Resolves the listing names a source declared against the contract's closed [SourceListing] set.
 *
 * Filtering the enum (rather than mapping the wire list) does three things at once, all of them part of
 * the contract: unknown names are **dropped** — a bundle built against a newer ABI can't smuggle in a
 * tab the app has no label or handling for — duplicates collapse, and the result comes back in
 * [SourceListing]'s order, so every source's Browse tabs read the same way regardless of the order the
 * bundle happened to list them in.
 */
private fun sourceListingsOf(wireNames: List<String>): List<SourceListing> =
    SourceListing.entries.filter { it.wireName in wireNames }

/**
 * Maps a source catalog/search entry to the browse-facing [MangaListing]. Total and lossless: every
 * wire field is carried through, URL strings are parsed leniently to domain [app.tanuki.core.domain.common.model.Url]
 * (unparseable → `null`), and [Wire.MangaWire.status] is resolved to a [PublishStatus] (unknown/absent
 * → [PublishStatus.UNKNOWN]). No persistence identity is assigned — that happens only when the user
 * adds the series to their library.
 */
fun Wire.MangaWire.toDomain(): MangaListing =
    MangaListing(
        sourceMangaKey = key,
        url = url.toUrlOrNull(),
        title = title,
        originalTitle = originalTitle,
        coverUrl = coverUrl.toUrlOrNull(),
        author = author,
        description = description,
        status = publishStatusOf(status),
    )

/** Maps a page of wire catalog entries to the browse-facing [MangaPage], preserving source order. */
fun Wire.MangaPageWire.toDomain(): MangaPage =
    MangaPage(
        entries = entries.map(Wire.MangaWire::toDomain),
        hasNextPage = hasNextPage,
    )

/**
 * Maps a wire chapter entry to the pre-persistence [ChapterListing]. Lenient at every field, since a
 * chapter list is the noisiest thing a source returns: a blank name becomes `null` (the persist step
 * derives a display name from the number), an absent/negative/non-finite number becomes `null` via
 * [ChapterNumber.orNull], and a non-positive [Wire.ChapterWire.dateUpload] is treated as "no date"
 * rather than 1970 — sources routinely send `0` for unknown.
 */
fun Wire.ChapterWire.toDomain(): ChapterListing =
    ChapterListing(
        chapterKey = key,
        name = name?.takeIf(String::isNotBlank),
        number = ChapterNumber.orNull(number),
        dateUpload = dateUpload?.takeIf { it > 0 }?.let(Instant::fromEpochMilliseconds),
        scanlator = scanlator?.takeIf(String::isNotBlank),
    )

/**
 * Resolves a wire status string to a [PublishStatus] by case-insensitive name match, per the ABI's
 * "enums travel as their string name; unknown values map to a documented fallback" rule. `null`,
 * blank, or unrecognised names map to [PublishStatus.UNKNOWN] rather than failing.
 */
private fun publishStatusOf(name: String?): PublishStatus =
    name?.let { raw -> PublishStatus.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } }
        ?: PublishStatus.UNKNOWN

/**
 * Maps a wire page to the domain [Page]. A source is expected to provide exactly one of
 * [Wire.PageWire.imageUrl] / [Wire.PageWire.url]; both are passed through (the reader resolves which to
 * fetch). Unparseable URL strings become `null`.
 */
fun Wire.PageWire.toDomain(): Page =
    Page(
        index = index.toUInt(),
        imageUrl = imageUrl.toUrlOrNull(),
        url = url.toUrlOrNull(),
    )
