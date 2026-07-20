package app.tanuki.core.domain.source

import app.tanuki.core.domain.manga.model.Manga
import app.tanuki.core.domain.source.model.Page
import app.tanuki.core.domain.source.model.Source
import app.tanuki.core.domain.source.model.SourceMeta

/**
 * The single seam between the app and the WASM extension runtime.
 *
 * Everything above this interface is runtime-agnostic: it MUST NOT leak WASM engine details
 * (memory, module handles, host functions). That is exactly what lets the engine be swapped per
 * platform behind one Koin binding — a single `WasmSourceLoader` whose engine is provided via
 * `expect`/`actual`. See "Key Interface — Never Break This" in CLAUDE.md; do not widen this with
 * runtime types.
 *
 * IDs here are *source-native strings* (e.g. `"mangadex"`, a source's own chapter id), not the
 * local persistence `Uuid`s. Content is addressed the way the source/ABI addresses it, and is
 * mapped to local `Uuid` identities only once persisted.
 */
interface SourceLoader {
    /** Loads (instantiates) the source identified by its source-native [id]. */
    suspend fun loadSource(id: String): Source

    /** Lists all sources known to the runtime, as lightweight [SourceMeta] entries. */
    suspend fun listSources(): List<SourceMeta>

    /**
     * Fetches the source's manga catalog.
     *
     * NOTE — known rough edge: returning [Manga] (a *persisted* entity with a local `Uuid`,
     * favorite flag, dates, reading status…) for freshly-fetched catalog data is awkward, since
     * those fields don't exist at fetch time. The cleaner shape is a separate lightweight source DTO
     * that a mapper inflates into a [Manga] on add-to-library. Left as [Manga] here to match the
     * CLAUDE.md contract; revisit when wiring the first real source.
     */
    suspend fun fetchMangaList(sourceId: String): List<Manga>

    /** Fetches the ordered pages of a chapter, addressed by source-native [chapterId]. */
    suspend fun fetchChapterPages(
        sourceId: String,
        chapterId: String
    ): List<Page>
}