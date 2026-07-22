package app.tanuki.core.source

import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.common.model.Url
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.common.service.uuidV7
import app.tanuki.core.domain.source.SourceBundleStore
import app.tanuki.core.domain.source.SourceLoader
import app.tanuki.core.domain.source.model.ChapterListing
import app.tanuki.core.domain.source.model.MangaListing
import app.tanuki.core.domain.source.model.MangaPage
import app.tanuki.core.domain.source.model.Page
import app.tanuki.core.domain.source.model.Source
import app.tanuki.core.domain.source.model.SourceMeta
import app.tanuki.core.domain.source.repository.SourceRepository
import app.tanuki.core.source.abi.SourceAbi
import app.tanuki.core.source.abi.SourceCodec
import app.tanuki.core.source.abi.Wire
import app.tanuki.core.source.abi.Wire.SourceMetaWire
import app.tanuki.core.source.abi.toDomain
import app.tanuki.core.source.runtime.SourceHost
import app.tanuki.core.source.runtime.WasmEngine
import app.tanuki.core.source.runtime.WasmModule
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The one and only [SourceLoader] implementation: it runs every source as a WASM module through a
 * [WasmEngine], speaking the [SourceAbi] over [SourceCodec]. It is the *only* type permitted to span
 * the two source seams — the app-facing [SourceLoader] and the runtime-facing [WasmEngine]/[WasmModule]
 * — so nothing WASM- or ABI-shaped leaks upward: callers see only domain types.
 *
 * Engine work runs on [DispatcherProvider.default] (CPU-bound interpretation + JSON decode), never the
 * caller's thread. Modules are cached per source id behind a [Mutex] so a source is instantiated once
 * (and its ABI version checked once) and concurrent calls don't double-instantiate. The shared [host]
 * is bound to every module's imports, so all source HTTP flows through one Ktor client.
 *
 * [listSources] and [fetchChapterPages] map straight to pre-persistence domain types. [fetchMangaList],
 * [fetchMangaDetails] and [fetchChapterList] return browse-shaped types ([MangaPage], [MangaListing],
 * [ChapterListing]) — no local identity, no user state; add-to-library and chapter-sync use cases
 * inflate the persisted `Manga`/`Chapter`. [loadSource] resolves the source's local [Source] identity,
 * installing it into [sourceRepository] on first load (snapshotting the bundle's reported version) and
 * reusing the assigned `Uuid` thereafter.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class WasmSourceLoader(
    private val engine: WasmEngine,
    private val host: SourceHost,
    private val bundleStore: SourceBundleStore,
    private val sourceRepository: SourceRepository,
    private val dispatchers: DispatcherProvider,
) : SourceLoader {

    private val modulesLock = Mutex()
    private val modules = mutableMapOf<String, WasmModule>()

    override suspend fun loadSource(id: String): Source = withContext(dispatchers.default) {
        // Instantiating + decoding SOURCE_META both validates the bundle loads and yields display
        // metadata. The local Uuid is minted once, on first load, and reused via getSourceByNativeId
        // thereafter — so a source keeps a stable local identity across app runs.
        sourceRepository.getSourceByNativeId(id) ?: run {
            val meta = SourceCodec.decode<SourceMetaWire>(
                moduleFor(id).call(SourceAbi.Exports.SOURCE_META, EMPTY),
            )
            val baseUrl = meta.baseUrl?.let(::Url)
                ?: error("Source \"$id\" reported no baseUrl in its metadata; cannot install it.")
            Source(
                id = uuidV7(),
                sourceId = id,
                lang = Text(meta.lang),
                name = Text(meta.name),
                url = baseUrl,
                // Snapshot the bundle's version at install time so an update check can diff it against
                // a remote repo index without instantiating a single module.
                version = meta.version,
            ).also { sourceRepository.addSource(it) }
        }
    }

    override suspend fun listSources(): List<SourceMeta> = withContext(dispatchers.default) {
        // Each installed bundle reports its own metadata via the SOURCE_META export. A source that fails
        // to load (corrupt bundle, ABI mismatch) is skipped so one bad source can't break the whole list.
        bundleStore.installedIds().mapNotNull { id ->
            runCatching {
                SourceCodec.decode<SourceMetaWire>(
                    moduleFor(id).call(SourceAbi.Exports.SOURCE_META, EMPTY),
                ).toDomain()
            }.getOrNull()
        }
    }

    override suspend fun fetchMangaList(
        sourceId: String,
        listing: String?,
        query: String?,
        page: Int,
    ): MangaPage = withContext(dispatchers.default) {
        require(page >= 1) { "Page index is 1-based, but was $page." }
        val request = SourceCodec.encode(
            // A blank query is normalised to null so a cleared search box browses rather than
            // searching for "" — guests only have to handle the null case.
            Wire.MangaListRequest(
                query = query?.takeIf(String::isNotBlank),
                page = page,
                listing = listing,
            ),
        )
        SourceCodec.decodeEnvelope<Wire.MangaPageWire>(
            moduleFor(sourceId).call(SourceAbi.Exports.MANGA_LIST, request),
        ).toDomain()
    }

    override suspend fun fetchMangaDetails(sourceId: String, mangaKey: String): MangaListing =
        withContext(dispatchers.default) {
            val request = SourceCodec.encode(Wire.MangaDetailsRequest(mangaKey = mangaKey))
            SourceCodec.decodeEnvelope<Wire.MangaWire>(
                moduleFor(sourceId).call(SourceAbi.Exports.MANGA_DETAILS, request),
            ).toDomain()
        }

    override suspend fun fetchChapterList(sourceId: String, mangaKey: String): List<ChapterListing> =
        withContext(dispatchers.default) {
            val request = SourceCodec.encode(Wire.ChapterListRequest(mangaKey = mangaKey))
            SourceCodec.decodeEnvelope<List<Wire.ChapterWire>>(
                moduleFor(sourceId).call(SourceAbi.Exports.CHAPTER_LIST, request),
            ).map(Wire.ChapterWire::toDomain)
        }

    override suspend fun fetchChapterPages(sourceId: String, chapterId: String): List<Page> =
        withContext(dispatchers.default) {
            val request = SourceCodec.encode(Wire.ChapterPagesRequest(chapterKey = chapterId))
            SourceCodec.decodeEnvelope<List<Wire.PageWire>>(
                moduleFor(sourceId).call(SourceAbi.Exports.CHAPTER_PAGES, request),
            ).map(Wire.PageWire::toDomain)
        }

    /**
     * Returns the instantiated module for [sourceId], instantiating + version-checking it on first use
     * and caching it thereafter.
     */
    private suspend fun moduleFor(sourceId: String): WasmModule = modulesLock.withLock {
        // Not getOrPut: reading the bundle is a suspend call, which getOrPut's lambda can't make.
        modules[sourceId] ?: run {
            val bytes = bundleStore.readBundle(sourceId)
                ?: error("Source \"$sourceId\" is not installed.")
            engine.instantiate(sourceId, bytes, host).also { module ->
                val version = module.callReturningI32(SourceAbi.Exports.ABI_VERSION)
                require(version == SourceAbi.VERSION) {
                    "Source \"$sourceId\" targets ABI v$version, but the host speaks v${SourceAbi.VERSION}."
                }
                modules[sourceId] = module
            }
        }
    }

    private companion object {
        /** Reused empty payload for no-argument exports (e.g. SOURCE_META). */
        val EMPTY = ByteArray(0)
    }
}