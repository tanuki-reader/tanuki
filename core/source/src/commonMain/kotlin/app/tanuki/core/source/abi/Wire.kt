package app.tanuki.core.source.abi

import kotlinx.serialization.Serializable

/**
 * The on-the-wire payload types of the source ABI — the JSON shapes crossing every
 * [app.tanuki.core.source.runtime.WasmModule.call] and host import. They are **distinct from the domain
 * models on purpose**: wire types mirror exactly what a source provides (source-native string ids, no
 * local `Uuid`s, no user state), and are mapped into domain types by [toDomain]-style mappers. That
 * keeps the ABI free to evolve without dragging the domain along, and keeps persistence identity out
 * of the source layer.
 *
 * Compatibility rules (so a host and a guest built against different minor revisions still interop):
 *  - **Add only optional fields** within a [SourceAbi.VERSION]; readers ignore unknown keys
 *    ([SourceCodec]). A required new field is a breaking change → bump the version.
 *  - Enums travel as their **string name**, not ordinal, so reordering is safe; unknown values map to a
 *    documented fallback rather than failing.
 */
object Wire {

    /**
     * Generic result envelope wrapping every fallible export output, so a guest can report a failure
     * in-band instead of trapping. Exactly one of [ok] / [error] is set.
     */
    @Serializable
    data class Envelope<T>(
        val ok: T? = null,
        val error: AbiError? = null,
    )

    /** A structured guest-side error carried by [Envelope.error]. */
    @Serializable
    data class AbiError(
        val message: String,
        /** Optional machine-readable code for callers that branch on failure kind (e.g. `"http"`, `"parse"`). */
        val code: String? = null,
    )

    /** Source self-description, returned by [SourceAbi.Exports.SOURCE_META]. */
    @Serializable
    data class SourceMetaWire(
        /** Source-native id (e.g. `"mangadex"`); stable identity of the source. */
        val id: String,
        val name: String,
        /** BCP-47 language tag the source serves (e.g. `"en"`, `"pt-BR"`). */
        val lang: String,
        /**
         * The source's base URL. Optional on the wire (older guests may omit it), but required to
         * persist a [app.tanuki.core.domain.source.model.Source]; [app.tanuki.core.source.WasmSourceLoader.loadSource]
         * fails with a clear error if a source it must install reports none.
         */
        val baseUrl: String? = null,
        /**
         * Monotonic source version for update checks; absent on sources that don't report one.
         * Persisted onto [app.tanuki.core.domain.source.model.Source.version] at install time so an
         * update check can diff it against a remote repo index without loading the bundle. Monotonic
         * integer, not semver — comparison is a plain `>`.
         */
        val version: Int? = null,
        /** Whether the source serves adult content; defaults to safe. */
        val nsfw: Boolean = false,
        /**
         * Browseable listings this source offers, in the order it wants them shown. Empty (the
         * default, and what every v1 guest sends) means the source has only its default catalog.
         */
        val listings: List<ListingWire> = emptyList(),
    )

    /**
     * One listing a source declares in [SourceMetaWire.listings] — a Browse-screen tab.
     * Mirrors [app.tanuki.core.domain.source.model.SourceListing].
     */
    @Serializable
    data class ListingWire(
        /** Source-native listing id, echoed back in [MangaListRequest.listing]. Opaque to the host. */
        val key: String,
        /** Human-readable tab label. */
        val name: String,
    )

    /** A catalog/search entry, as a source provides it — pre-persistence, hence a source-native [key]. */
    @Serializable
    data class MangaWire(
        /** Source-native manga id (Mihon's "url"/key). Addresses this manga in later calls. */
        val key: String,
        /**
         * Absolute permalink to this series on the source's site (e.g. `https://mangadex.org/title/…`).
         * The source owns its URL scheme, so it — not the app — builds this. Persisted as the manga's
         * natural key; absent on sources that don't publish one, in which case the app falls back to the
         * source's base URL.
         */
        val url: String? = null,
        val title: String,
        /** Source's original/romanized title when it differs from [title]; absent otherwise. */
        val originalTitle: String? = null,
        val coverUrl: String? = null,
        val author: String? = null,
        val description: String? = null,
        /** Publication status name matching [app.tanuki.core.domain.manga.model.PublishStatus]; unknown → UNKNOWN. */
        val status: String? = null,
    )

    /** Request for [SourceAbi.Exports.MANGA_LIST]. */
    @Serializable
    data class MangaListRequest(
        /** Free-text search; `null`/blank means "browse" (the source's default listing). */
        val query: String? = null,
        /** 1-based page index for the source's pagination. */
        val page: Int = 1,
        /**
         * Which declared listing ([ListingWire.key]) to browse; `null` selects the source's default
         * catalog. A guest that receives a listing key it doesn't recognise should fall back to its
         * default rather than erroring. When both this and [query] are set and the source cannot
         * combine them, [query] wins.
         */
        val listing: String? = null,
    )

    /**
     * Output of [SourceAbi.Exports.MANGA_LIST] — one page of results plus a termination signal.
     * A bare list can't distinguish "short page" from "last page", which is exactly what an
     * infinite-scrolling grid needs to know.
     */
    @Serializable
    data class MangaPageWire(
        val entries: List<MangaWire> = emptyList(),
        /** Whether `page + 1` would return more. Sources that can't tell report `false`. */
        val hasNextPage: Boolean = false,
    )

    /** Request for [SourceAbi.Exports.MANGA_DETAILS]. */
    @Serializable
    data class MangaDetailsRequest(
        /** Source-native manga id ([MangaWire.key]) whose full record to fetch. */
        val mangaKey: String,
    )

    /** Request for [SourceAbi.Exports.CHAPTER_LIST]. */
    @Serializable
    data class ChapterListRequest(
        /** Source-native manga id ([MangaWire.key]) whose chapters to list. */
        val mangaKey: String,
    )

    /**
     * One chapter entry from [SourceAbi.Exports.CHAPTER_LIST] — pre-persistence, so a source-native
     * [key] and no local identity or reading progress. Mirrors
     * [app.tanuki.core.domain.source.model.ChapterListing].
     */
    @Serializable
    data class ChapterWire(
        /** Source-native chapter id. Addresses this chapter in [ChapterPagesRequest]. */
        val key: String,
        /** Chapter title; absent on the many sources that publish only a number. */
        val name: String? = null,
        /** Chapter number such as `4` or `4.5`; absent when the source reports none. Negative or
         *  non-finite values are treated as absent (see [app.tanuki.core.domain.chapter.model.ChapterNumber.orNull]). */
        val number: Double? = null,
        /** Publication time as Unix epoch **milliseconds**; absent when the source reports none. */
        val dateUpload: Long? = null,
        /** Translation group credited by the source; absent when none is given. */
        val scanlator: String? = null,
    )

    /** Request for [SourceAbi.Exports.CHAPTER_PAGES]. */
    @Serializable
    data class ChapterPagesRequest(
        /** Source-native chapter id whose pages to fetch. */
        val chapterKey: String,
    )

    /** One page descriptor, returned within [SourceAbi.Exports.CHAPTER_PAGES]. Mirrors [app.tanuki.core.domain.source.model.Page]. */
    @Serializable
    data class PageWire(
        val index: Int,
        /** Direct image URL; `null` when it must be resolved from [url]. */
        val imageUrl: String? = null,
        /** Page URL to resolve when [imageUrl] is absent. */
        val url: String? = null,
    )

    /** A network request a guest asks the host to perform via [SourceAbi.Imports.HTTP_REQUEST]. */
    @Serializable
    data class HttpRequestWire(
        val url: String,
        /** HTTP method, uppercase (`"GET"`, `"POST"`…). Defaults to GET. */
        val method: String = "GET",
        val headers: Map<String, String> = emptyMap(),
        /** Base64-encoded request body; `null` for bodyless methods. Base64 so binary survives JSON. */
        val bodyBase64: String? = null,
    )

    /** The host's response to an [HttpRequestWire]. */
    @Serializable
    data class HttpResponseWire(
        val status: Int,
        val headers: Map<String, String> = emptyMap(),
        /** Base64-encoded response body (may be HTML, JSON, or binary). */
        val bodyBase64: String,
    )

    /** Severity for [SourceAbi.Imports.LOG]; the `i32` level passed is the ordinal of this enum. */
    @Serializable
    enum class LogLevel { DEBUG, INFO, WARN, ERROR }
}