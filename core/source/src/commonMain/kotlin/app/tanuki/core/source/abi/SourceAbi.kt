package app.tanuki.core.source.abi

/**
 * The Tanuki **source ABI** — the contract every `.wasm` source is compiled against, and the only
 * thing the host and a guest must agree on. It is engine-agnostic (any embedded
 * [app.tanuki.core.source.runtime.WasmEngine] can host it) and language-agnostic (a guest may be Rust,
 * AssemblyScript, Kotlin/Wasm…). This file is the machine-checkable half of the spec — the names and
 * version the host code references.
 *
 * It has three parts:
 *  1. **Memory ABI** — how raw bytes cross linear memory ([Memory]). Handled entirely inside the engine
 *     binding, below [app.tanuki.core.source.runtime.WasmModule.call]; never surfaced to the loader.
 *  2. **Guest exports** — functions the host calls on a source ([Exports]).
 *  3. **Host imports** — functions a source calls back into the host ([Imports]); imported under
 *     module [Imports.MODULE]. Payloads are JSON (see [Wire] / [SourceCodec]).
 *
 * This package is the seed of the future `tanuki/source-api` artifact (CLAUDE.md → "Source ecosystem
 * & naming"): when that repo is extracted, these declarations + [Wire] move there largely unchanged,
 * and the host depends on the published SDK instead.
 */
object SourceAbi {
    /**
     * ABI revision the host speaks. A source advertises its own via [Exports.ABI_VERSION]; the host
     * refuses to run a source whose version it doesn't recognise (no compatibility shims in v1).
     * Bump on any breaking change to exports, imports, the memory ABI, or a wire shape.
     */
    const val VERSION: Int = 2

    /** Memory-ABI exports a guest must provide so the host can move bytes in/out of its linear memory. */
    object Memory {
        /**
         * `alloc(size: i32) -> i32`. Reserves [size] bytes in guest linear memory and returns the
         * pointer. The host writes input bytes there before calling an export; the guest's allocator
         * owns the region until a matching [DEALLOC].
         */
        const val ALLOC = "tanuki_alloc"

        /** `dealloc(ptr: i32, size: i32)`. Frees a region previously returned by [ALLOC]. */
        const val DEALLOC = "tanuki_dealloc"
    }

    /** Functions the host calls on a loaded source. */
    object Exports {
        /** `abi_version() -> i32`. Returns the [SourceAbi.VERSION] this source was built for. */
        const val ABI_VERSION = "abi_version"

        /**
         * `source_meta(ptr,len) -> (ptr,len)`. Input is ignored (there's nothing to pass), but the
         * export still takes `(ptr,len)` so EVERY bytes-export shares one `(i32,i32) -> i64` signature
         * — the host's memory ABI calls them uniformly, and wasmi rejects an arity mismatch even though
         * Chicory tolerates one. Returns a [Wire.SourceMetaWire] (JSON). Used both to describe an
         * installed source and, across all installed bundles, to build the source list.
         */
        const val SOURCE_META = "source_meta"

        /**
         * `manga_list(ptr,len) -> (ptr,len)`. Input [Wire.MangaListRequest], output a
         * [Wire.Envelope] of [Wire.MangaPageWire] (JSON) — one page of the source's browseable
         * catalog/search, plus whether another page follows.
         */
        const val MANGA_LIST = "manga_list"

        /**
         * `manga_details(ptr,len) -> (ptr,len)`. Input [Wire.MangaDetailsRequest], output a
         * [Wire.Envelope] of [Wire.MangaWire] (JSON) — the full record for one series. Same shape as a
         * catalog entry, just more of it filled in (description, author, status).
         */
        const val MANGA_DETAILS = "manga_details"

        /**
         * `chapter_list(ptr,len) -> (ptr,len)`. Input [Wire.ChapterListRequest], output a
         * [Wire.Envelope] of `List<`[Wire.ChapterWire]`>` (JSON) — every chapter of one series.
         * Unpaginated: the app needs the whole list to compute unread counts and detect gaps.
         */
        const val CHAPTER_LIST = "chapter_list"

        /**
         * `chapter_pages(ptr,len) -> (ptr,len)`. Input [Wire.ChapterPagesRequest], output a
         * [Wire.Envelope] of `List<`[Wire.PageWire]`>` (JSON) — the ordered pages of one chapter.
         */
        const val CHAPTER_PAGES = "chapter_pages"
    }

    /** Functions a guest imports from the host. All live under the [MODULE] import namespace. */
    object Imports {
        /** WASM import module name the host binds its functions under. */
        const val MODULE = "tanuki"

        /**
         * `http_request(ptr,len) -> (ptr,len)`. Input [Wire.HttpRequestWire], output [Wire.HttpResponseWire]
         * (JSON). Synchronous from the guest's view — the host blocks the engine's worker thread on the
         * suspending [app.tanuki.core.source.runtime.SourceHost.httpRequest]. Routing every source request
         * through the host's shared Ktor client is what lets the Cloudflare interceptor's cf_clearance
         * cookie apply uniformly (CLAUDE.md → "Cloudflare Bypass").
         */
        const val HTTP_REQUEST = "http_request"

        /**
         * `log(level: i32, ptr: i32, len: i32)`. Emits a UTF-8 message at the given [Wire.LogLevel]
         * ordinal; no return. For source diagnostics surfaced through the host logger.
         */
        const val LOG = "log"
    }
}