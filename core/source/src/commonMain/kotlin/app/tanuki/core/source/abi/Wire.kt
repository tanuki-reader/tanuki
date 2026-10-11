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
        /**
         * Remote URL of the source's icon/logo, shown beside its name in the source list. Optional
         * (older guests omit it → the UI falls back to a lettered avatar). Purely additive, so adding
         * it did not require an ABI [SourceAbi.VERSION] bump — an old bundle that omits the field still
         * decodes cleanly to `null`.
         */
        val iconUrl: String? = null,
        /** Whether the source serves adult content; defaults to safe. */
        val nsfw: Boolean = false,
        /**
         * Which of the contract's listings this source serves, as
         * [app.tanuki.core.domain.source.model.SourceListing.wireName] values (`"popular"`, `"latest"`).
         * Empty (the default) means the source has only its default catalog.
         *
         * Typed as raw strings rather than the domain enum for the same reason
         * [MangaWire.status] is: a bundle built against a newer contract may name a listing this build
         * doesn't know, and an unknown name must degrade (the mapper drops it) rather than sink the
         * whole metadata decode. The vocabulary is still closed — the resolution in [toDomain] is what
         * enforces it host-side, and `wire.rs`'s `Listing` enum enforces it guest-side.
         */
        val listings: List<String> = emptyList(),
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
        /**
         * Per-request headers to send when fetching [coverUrl] — the cover's counterpart to
         * [PageWire.headers], for sources whose image CDN checks the `Referer` (Hipercool's 403s
         * without it). Empty — the default — means none, so this is additive within [SourceAbi.VERSION]
         * exactly as the page headers were: an older bundle omits it and decodes cleanly.
         *
         * Unlike a page's, these are **persisted** with the manga: a library cover is rendered long
         * after the browse call that produced it, with no source round-trip to re-ask.
         */
        val coverHeaders: Map<String, String> = emptyMap(),
        val author: String? = null,
        val description: String? = null,
        /** Publication status name matching [app.tanuki.core.domain.manga.model.PublishStatus]; unknown → UNKNOWN. */
        val status: String? = null,
        /**
         * Number of the newest chapter, when the listing shows it (`222`, `10.5`). Optional and additive
         * within [SourceAbi.VERSION], like everything below: it feeds the "Ch. 222" captions on a
         * source's Latest rail. Negative or non-finite values are treated as absent.
         */
        val latestChapter: Double? = null,
        /** The newest chapter's own name, for sources that don't number chapters. */
        val latestChapterName: String? = null,
        /** When the series last got a chapter, Unix epoch **milliseconds**; non-positive = unknown. */
        val updatedAt: Long? = null,
        /** Genre/tag names as the source shows them; empty when none. */
        val genres: List<String> = emptyList(),
        /**
         * The series' AniList id, when the source links to it — lets the app match the series exactly
         * for its metadata instead of guessing by title. Optional; usually only on details.
         */
        val anilistId: Long? = null,
        /** The series' MyAnimeList id, when the source links to it. Same purpose as [anilistId]. */
        val malId: Long? = null,
        /** The series' MangaDex id, when the source links to it (localized text, volume covers). */
        val mangadexId: String? = null,
    )

    /** Request for [SourceAbi.Exports.SOURCE_HOME]; empty today, an object so it can grow optional fields. */
    @Serializable
    data class SourceHomeRequest(
        /** Same meaning as [MangaListRequest.allowSensitive]. */
        val allowSensitive: Boolean = false,
    )

    /**
     * Output of the optional [SourceAbi.Exports.SOURCE_HOME]: the first page of several listings in one
     * call. A section is keyed by a [app.tanuki.core.domain.source.model.SourceListing.wireName]; an
     * unknown name is dropped by the mapper, the same way [SourceMetaWire.listings] treats one.
     */
    @Serializable
    data class SourceHomeWire(
        val sections: List<SourceHomeSectionWire> = emptyList(),
    )

    /** One [SourceHomeWire] section. */
    @Serializable
    data class SourceHomeSectionWire(
        val listing: String,
        val entries: List<MangaWire> = emptyList(),
    )

    /** Request for [SourceAbi.Exports.MANGA_LIST]. */
    @Serializable
    data class MangaListRequest(
        /** Free-text search; `null`/blank means "browse" (the source's default listing). */
        val query: String? = null,
        /** 1-based page index for the source's pagination. */
        val page: Int = 1,
        /**
         * Which listing to browse, as a [app.tanuki.core.domain.source.model.SourceListing.wireName];
         * `null` selects the source's default catalog. The host only sends one the source declared in
         * [SourceMetaWire.listings], and a guest that doesn't recognise the value falls back to its
         * default rather than erroring. When both this and [query] are set and the source cannot
         * combine them, [query] wins.
         */
        val listing: String? = null,
        /**
         * Whether the user allows sensitive (adult) content. `false` asks a source with mixed content to
         * leave it out; an older guest ignores the field.
         */
        val allowSensitive: Boolean = false,
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
        /**
         * Per-request headers the app must send when fetching this page's image (e.g. a `Referer` for a
         * hotlink-guarded host). Empty — the default — means none. Additive within [SourceAbi.VERSION]:
         * an older host ignores it (and just omits a header, a recoverable fetch failure), so unlike a
         * page transform this needs no version bump. See `docs/design-page-image-abi.md`.
         */
        val headers: Map<String, String> = emptyMap(),
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

    /**
     * The host's response to an [HttpRequestWire].
     *
     * Deliberately **not** `@Serializable`: unlike every other wire type this one never becomes JSON.
     * It is framed by [HttpFrame] so the body crosses as raw bytes — see that object for why carrying
     * a body through JSON cost seconds per call.
     */
    data class HttpResponseWire(
        val status: Int,
        val headers: Map<String, String> = emptyMap(),
        /** Raw response body, exactly as received (may be HTML, JSON, or binary). */
        val body: ByteArray,
    ) {
        // ByteArray identity is referential, which would make two equal responses compare unequal —
        // worth overriding since tests build these by value.
        override fun equals(other: Any?): Boolean =
            this === other ||
                (
                    other is HttpResponseWire &&
                        status == other.status &&
                        headers == other.headers &&
                        body.contentEquals(other.body)
                )

        override fun hashCode(): Int =
            (status * 31 + headers.hashCode()) * 31 + body.contentHashCode()
    }

    /** Severity for [SourceAbi.Imports.LOG]; the `i32` level passed is the ordinal of this enum. */
    @Serializable
    enum class LogLevel { DEBUG, INFO, WARN, ERROR }
}