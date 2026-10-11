package app.tanuki.core.domain.source

import app.tanuki.core.domain.source.model.ChapterListing
import app.tanuki.core.domain.source.model.MangaListing
import app.tanuki.core.domain.source.model.MangaPage
import app.tanuki.core.domain.source.model.Page
import app.tanuki.core.domain.source.model.Source
import app.tanuki.core.domain.source.model.SourceHome
import app.tanuki.core.domain.source.model.SourceListing
import app.tanuki.core.domain.source.model.SourceMeta
import kotlinx.coroutines.flow.Flow

/**
 * The single seam between the app and the WASM extension runtime.
 *
 * Everything above this interface is runtime-agnostic: it MUST NOT leak WASM engine details
 * (memory, module handles, host functions). That is exactly what lets the engine be swapped per
 * platform behind one Metro binding — a single `WasmSourceLoader` whose engine is provided via
 * `expect`/`actual`. See "Key Interface — Never Break This" in CLAUDE.md; do not widen this with
 * runtime types.
 *
 * IDs here are *source-native strings* (e.g. `"mangadex"`, a source's own chapter id), not the
 * local persistence `Uuid`s. Content is addressed the way the source/ABI addresses it, and is
 * mapped to local `Uuid` identities only once persisted.
 */
interface SourceLoader {
    /**
     * Loads the source identified by its source-native [id], returning it as a persisted [Source].
     *
     * The [Source] carries a local `Uuid` (what stored manga foreign-key to). It is assigned on first
     * load — the source is installed into the local `SourceRepository` — and reused on every load
     * thereafter, so the same source always maps to the same local identity.
     */
    suspend fun loadSource(id: String): Source

    /**
     * All sources known to the runtime as lightweight [SourceMeta] entries, re-emitted whenever the
     * installed set changes — so a screen listing sources follows an install or an uninstall rather than
     * holding a snapshot that quietly goes stale.
     *
     * A [Flow] rather than a `suspend` list because the runtime, not the caller, knows when the set
     * moved. Each emission re-reads every installed bundle's metadata, which is comparatively expensive,
     * so it is cold and unshared: collect it once per screen, not per row. A source whose bundle fails to
     * load is skipped rather than failing the emission — one corrupt bundle cannot empty the list.
     */
    fun observeInstalledSources(): Flow<List<SourceMeta>>

    /**
     * Fetches one page of the source's browseable catalog as pre-persistence [MangaListing]s.
     *
     * These are source-native results with no local `Uuid` and no user state — an add-to-library use
     * case inflates a chosen [MangaListing] into a persisted `Manga`. Keeping the browse shape separate
     * is what keeps persistence identity out of the source layer (see [MangaListing]).
     *
     * @param listing which listing to browse, or `null` for the source's default catalog. Callers pass
     *   one the source declared in [SourceMeta.listings]; a source asked for a listing it doesn't serve
     *   falls back to its default rather than failing.
     * @param query free-text search; `null` or blank browses instead of searching. A source that
     *   cannot combine search with [listing] is expected to let [query] win.
     * @param page 1-based page index. Callers page forward only while [MangaPage.hasNextPage].
     */
    suspend fun fetchMangaList(
        sourceId: String,
        listing: SourceListing? = null,
        query: String? = null,
        page: Int = 1
    ): MangaPage

    /**
     * The source's own one-call home (the first page of several listings), or `null` when the source
     * doesn't offer one — callers then build it from [fetchMangaList], one call per declared listing.
     * Optional in the source contract, so most sources answer `null`.
     */
    suspend fun fetchSourceHome(sourceId: String): SourceHome? = null

    /**
     * Fetches the full detail record for one series, addressed by its source-native [mangaKey].
     *
     * Returns the same [MangaListing] shape as [fetchMangaList] rather than a separate type: a browse
     * entry and a detail record differ only in *completeness*, not in kind — both are pre-persistence,
     * source-native, and identity-free. A catalog entry typically carries just title and cover, while
     * this fills in description, author, and status.
     */
    suspend fun fetchMangaDetails(
        sourceId: String,
        mangaKey: String
    ): MangaListing

    /**
     * Fetches every chapter the source lists for one series, addressed by source-native [mangaKey].
     *
     * Unpaginated by design — sources publish complete chapter lists, and the app needs all of them to
     * compute unread counts and detect gaps. Order is the source's own; callers sort by
     * [ChapterListing.number] rather than relying on it.
     */
    suspend fun fetchChapterList(
        sourceId: String,
        mangaKey: String
    ): List<ChapterListing>

    /** Fetches the ordered pages of a chapter, addressed by source-native [chapterId]. */
    suspend fun fetchChapterPages(
        sourceId: String,
        chapterId: String
    ): List<Page>
}