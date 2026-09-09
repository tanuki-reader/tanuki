package app.tanuki.core.source

import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.common.model.Url
import app.tanuki.core.domain.common.model.toUrlOrNull
import app.tanuki.core.domain.common.service.ConnectionObserver
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.common.service.OfflineException
import app.tanuki.core.domain.common.service.uuidV7
import app.tanuki.core.domain.source.SourceBundleStore
import app.tanuki.core.domain.source.SourceLoader
import app.tanuki.core.domain.source.model.ChapterListing
import app.tanuki.core.domain.source.model.MangaListing
import app.tanuki.core.domain.source.model.MangaPage
import app.tanuki.core.domain.source.model.Page
import app.tanuki.core.domain.source.model.Source
import app.tanuki.core.domain.source.model.SourceListing
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The one and only [SourceLoader] implementation: it runs every source as a WASM module through a
 * [WasmEngine], speaking the [SourceAbi] over [SourceCodec]. It is the *only* type permitted to span
 * the two source seams — the app-facing [SourceLoader] and the runtime-facing [WasmEngine]/[WasmModule]
 * — so nothing WASM- or ABI-shaped leaks upward: callers see only domain types.
 *
 * Every method dispatches its own engine work, so callers never wrap these calls — but *which*
 * dispatcher depends on whether the guest can reach the network:
 *  - [loadSource] and [observeInstalledSources] only invoke the `SOURCE_META` export — interpretation and JSON
 *    decode, genuinely CPU-bound — so they run on [DispatcherProvider.default]. (The bundle reads
 *    beneath them already hop to [DispatcherProvider.io] inside the [SourceBundleStore].)
 *  - The `fetch*` calls run guest code that issues HTTP through the `tanuki.http_request` import, and
 *    the engines bridge that suspend host call with `runBlocking` — so the thread is **blocked** for a
 *    whole round-trip. That belongs on [DispatcherProvider.io], which is sized to be blocked and grows
 *    on demand; blocking [DispatcherProvider.default] would contend with every `flowOn(default)` UI
 *    state pipeline in the app for a pool only as wide as the CPU count.
 *
 * Every `fetch*` call is gated on [ConnectionObserver] first (see [requireConnection]). That guard lives
 * here rather than in [SourceHost] — the seam the guest's HTTP actually crosses — because the host call
 * runs *inside* the engine, on the far side of a synchronous WASM import: on iOS that is a C callback
 * invoked from Rust, and a Kotlin exception thrown across it terminates the process instead of
 * unwinding. Refusing before entering the engine keeps an offline failure an ordinary Kotlin exception
 * on both platforms.
 *
 * Modules are cached per source id behind a [Mutex] so concurrent calls don't double-instantiate, and
 * that cache is bounded: at most [MAX_CACHED_MODULES] modules are kept, the least recently used closed
 * to make room (see [trim]). An engine instance is the heaviest long-lived object the app holds — on
 * iOS `close` frees memory the Kotlin heap can't see at all — so "instantiated once" is deliberately no
 * longer the contract; "instantiated once *while in use*" is. Only the `fetch*` path caches:
 * `SOURCE_META` runs against a [withTransientModule] instance, because it is asked of every installed
 * bundle rather than of the source the user is looking at. The shared [host] is bound to every module's
 * imports, so all source HTTP flows through one Ktor client.
 *
 * [observeInstalledSources] and [fetchChapterPages] map straight to pre-persistence domain types. [fetchMangaList],
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
    private val connectionObserver: ConnectionObserver,
) : SourceLoader {

    private val modulesLock = Mutex()

    /**
     * Instantiated modules of the sources being *fetched* from, least-recently-used first.
     *
     * A [LinkedHashMap] rather than a plain map because that iteration order is the eviction order (see
     * [trim]); [acquire] re-inserts on every hit to keep it true.
     */
    private val modules = LinkedHashMap<String, LoadedModule>()

    override suspend fun loadSource(id: String): Source = withContext(dispatchers.default) {
        // Instantiating + decoding SOURCE_META both validates the bundle loads and yields display
        // metadata. The local Uuid is minted once, on first load, and reused via getSourceByNativeId
        // thereafter — so a source keeps a stable local identity across app runs.
        sourceRepository.getSourceByNativeId(id) ?: run {
            val meta = SourceCodec.decode<SourceMetaWire>(
                withTransientModule(id) { it.call(SourceAbi.Exports.SOURCE_META, EMPTY) },
            )
            val baseUrl = meta.baseUrl?.let(::Url)
                ?: error("Source \"$id\" reported no baseUrl in its metadata; cannot install it.")
            Source(
                id = uuidV7(),
                sourceId = id,
                lang = Text(meta.lang),
                name = Text(meta.name),
                url = baseUrl,
                // Snapshotted at install time for the same reason as the version: a screen showing this
                // source's badge shouldn't have to instantiate the module to find out what it looks
                // like. Unparseable → null, which the UI renders as the lettered avatar.
                iconUrl = meta.iconUrl.toUrlOrNull(),
                // Snapshot the bundle's version at install time so an update check can diff it against
                // a remote repo index without instantiating a single module.
                version = meta.version,
            ).also { sourceRepository.addSource(it) }
        }
    }

    override fun observeInstalledSources(): Flow<List<SourceMeta>> =
        bundleStore
            .observeInstalledIds()
            .map { ids ->
                // An uninstalled source leaves its instantiated module behind in the cache, where it would
                // keep answering calls for a bundle that is no longer on disk. Drop those first, so what
                // the enumeration below reports and what the loader will actually run agree.
                evictModulesOutside(ids)
                // Each installed bundle reports its own metadata via the SOURCE_META export. A source that
                // fails to load (corrupt bundle, ABI mismatch) is skipped so one bad source can't break
                // the whole list.
                ids.mapNotNull { id ->
                    runCatching {
                        SourceCodec.decode<SourceMetaWire>(
                            withTransientModule(id) { it.call(SourceAbi.Exports.SOURCE_META, EMPTY) },
                        ).toDomain()
                    }.getOrNull()
                }
            }.flowOn(dispatchers.default)

    override suspend fun fetchMangaList(
        sourceId: String,
        listing: SourceListing?,
        query: String?,
        page: Int,
        // `io`, not `default`: the guest's HTTP goes through a runBlocking bridge in the engine, so this
        // blocks its thread for the whole round-trip (see the dispatcher note on the class).
    ): MangaPage = withContext(dispatchers.io) {
        require(page >= 1) { "Page index is 1-based, but was $page." }
        requireConnection(sourceId, SourceAbi.Exports.MANGA_LIST)
        val request = SourceCodec.encode(
            // A blank query is normalised to null so a cleared search box browses rather than
            // searching for "" — guests only have to handle the null case.
            Wire.MangaListRequest(
                query = query?.takeIf(String::isNotBlank),
                page = page,
                listing = listing?.wireName,
            ),
        )
        SourceCodec.decodeEnvelope<Wire.MangaPageWire>(
            callModule(sourceId, SourceAbi.Exports.MANGA_LIST, request),
        ).toDomain()
    }

    override suspend fun fetchMangaDetails(sourceId: String, mangaKey: String): MangaListing =
        withContext(dispatchers.io) {
            requireConnection(sourceId, SourceAbi.Exports.MANGA_DETAILS)
            val request = SourceCodec.encode(Wire.MangaDetailsRequest(mangaKey = mangaKey))
            SourceCodec.decodeEnvelope<Wire.MangaWire>(
                callModule(sourceId, SourceAbi.Exports.MANGA_DETAILS, request),
            ).toDomain()
        }

    override suspend fun fetchChapterList(sourceId: String, mangaKey: String): List<ChapterListing> =
        withContext(dispatchers.io) {
            requireConnection(sourceId, SourceAbi.Exports.CHAPTER_LIST)
            val request = SourceCodec.encode(Wire.ChapterListRequest(mangaKey = mangaKey))
            SourceCodec.decodeEnvelope<List<Wire.ChapterWire>>(
                callModule(sourceId, SourceAbi.Exports.CHAPTER_LIST, request),
            ).map(Wire.ChapterWire::toDomain)
        }

    override suspend fun fetchChapterPages(sourceId: String, chapterId: String): List<Page> =
        withContext(dispatchers.io) {
            requireConnection(sourceId, SourceAbi.Exports.CHAPTER_PAGES)
            val request = SourceCodec.encode(Wire.ChapterPagesRequest(chapterKey = chapterId))
            SourceCodec.decodeEnvelope<List<Wire.PageWire>>(
                callModule(sourceId, SourceAbi.Exports.CHAPTER_PAGES, request),
            ).map(Wire.PageWire::toDomain)
        }

    /**
     * Refuses a source call the device has no network to make, before any of it happens.
     *
     * Every `fetch*` export exists to reach the source's server, so offline there is no partial answer
     * to give — the guest would issue its request, the host would fail it, and the guest would surface an
     * ABI error that says nothing about *why*. Failing here instead is both faster and legible: callers
     * get an [OfflineException] they can tell apart from a broken source, and no engine instance is
     * entered (which on iOS is what keeps the failure a Kotlin exception rather than a process abort).
     *
     * Only [ConnectionObserver.ConnectionStatus.OFFLINE] is refused. A slow or metered link is still a
     * link, and it is not this layer's place to decide that a screen the user is looking at isn't worth
     * the bytes.
     */
    private fun requireConnection(sourceId: String, export: String) {
        if (!connectionObserver.isOnline) {
            throw OfflineException("Cannot run \"$export\" for source \"$sourceId\" while offline")
        }
    }

    /**
     * Calls [export] on [sourceId]'s cached module, **one call at a time per source**.
     *
     * The exclusion is not optional. A [WasmModule] wraps a live engine instance with a single operand
     * stack and one linear memory; entering it twice at once interleaves two calls' stack traffic and
     * corrupts both (Chicory surfaces this as an `ArrayIndexOutOfBoundsException` from its interpreter,
     * far from the cause). The app does exactly that whenever one screen makes two source calls at once
     * — the detail screen refreshes chapters and details in parallel on open — so serializing here, at
     * the one place every export call passes through, is what makes those callers safe.
     *
     * Per source, not global: two different sources are separate instances sharing nothing, and one
     * source's slow feed shouldn't block another's browse.
     *
     * [acquire] / [release] bracket the call so the cap and the uninstall sweep can tell a module that is
     * merely cached from one that is mid-call — see [trim].
     */
    private suspend fun callModule(
        sourceId: String,
        export: String,
        input: ByteArray,
    ): ByteArray {
        val loaded = acquire(sourceId)
        return try {
            loaded.lock.withLock { loaded.module.call(export, input) }
        } finally {
            release(loaded)
        }
    }

    /**
     * Runs [block] against a module instantiated for [sourceId] alone, closed as soon as it returns.
     *
     * The metadata path uses this rather than the cache. `SOURCE_META` is asked of **every installed
     * bundle** by [observeInstalledSources], so routing it through [callModule] left an instance resident
     * per installed source — the largest term in this cache, and for sources the user may never open.
     * Re-instantiating instead is affordable because that enumeration is driven by
     * [SourceBundleStore.observeInstalledIds], a revision counter behind a `distinctUntilChanged`: it
     * runs at launch and on install/remove, not per screen. Peak cost becomes one live module instead of
     * one per bundle.
     *
     * The ABI check happens here too, which is what lets the caller's `runCatching` skip a bundle that
     * targets another ABI version instead of failing the whole enumeration.
     */
    private suspend fun <T> withTransientModule(sourceId: String, block: (WasmModule) -> T): T {
        val module = instantiate(sourceId)
        return try {
            block(module)
        } finally {
            module.close()
        }
    }

    /**
     * Takes [sourceId]'s cached module for one call, instantiating it on first use, marking it in-use and
     * moving it to the most-recently-used end. Balanced by [release] — never call it without one.
     *
     * [modulesLock] guards the *map* only, and is released before the call itself runs: a source's own
     * [LoadedModule.lock] serializes its calls (see [callModule]), so a long guest call can't block
     * instantiating an unrelated source. That release is exactly why [LoadedModule.inUse] exists — a
     * module handed out here is reachable by nobody else, but is not yet holding its own lock, so a
     * concurrent [trim] or [evictModulesOutside] would otherwise be free to close it out from under the
     * caller.
     */
    private suspend fun acquire(sourceId: String): LoadedModule = modulesLock.withLock {
        // remove + put rather than a plain get: a LinkedHashMap keeps insertion order, so re-inserting a
        // hit is what makes iteration order least-recently-used first for trim().
        val loaded = modules.remove(sourceId) ?: LoadedModule(instantiate(sourceId))
        modules[sourceId] = loaded
        loaded.inUse++
        trim()
        loaded
    }

    /**
     * Ends the borrow [acquire] began: drops the in-use mark, closes the module if it was retired while
     * the call was running, and brings the cache back under its cap.
     *
     * [NonCancellable] because this runs from a `finally` and [Mutex.lock] is cancellable — a fetch
     * cancelled while it held a module would otherwise skip the decrement and leave that module pinned
     * in the cache for the life of the process, which is the exact leak this whole item is about.
     */
    private suspend fun release(loaded: LoadedModule) = withContext(NonCancellable) {
        modulesLock.withLock {
            loaded.inUse--
            if (loaded.inUse == 0 && loaded.closeWhenIdle) loaded.module.close()
            trim()
        }
    }

    /**
     * Closes least-recently-used modules until at most [MAX_CACHED_MODULES] remain. Call under
     * [modulesLock].
     *
     * The cap is **soft**: a module with a call in flight is skipped rather than waited for, so calls to
     * more distinct sources than the cap can hold exceed it briefly. Every [release] trims again, so the
     * excess lasts only as long as the concurrent calls do. Closing under [modulesLock] is safe because a
     * victim is removed from the map first and [LoadedModule.inUse] only ever rises under that same lock,
     * so nothing can acquire it after the decision.
     */
    private fun trim() {
        while (modules.size > MAX_CACHED_MODULES) {
            // Copied out of the entry before the removal: Kotlin/Native invalidates a `MutableEntry` the
            // moment its backing map changes and throws on the next read, where the JVM's tolerates it.
            val (id, victim) = modules.entries.firstOrNull { it.value.inUse == 0 }
                ?.let { it.key to it.value }
                ?: return
            modules.remove(id)
            victim.module.close()
        }
    }

    /** Reads [sourceId]'s bundle, instantiates it and checks that it speaks this host's ABI version. */
    private suspend fun instantiate(sourceId: String): WasmModule {
        val bytes = bundleStore.readBundle(sourceId)
            ?: error("Source \"$sourceId\" is not installed.")
        return engine.instantiate(sourceId, bytes, host).also { module ->
            val version = module.callReturningI32(SourceAbi.Exports.ABI_VERSION)
            require(version == SourceAbi.VERSION) {
                "Source \"$sourceId\" targets ABI v$version, but the host speaks v${SourceAbi.VERSION}."
            }
        }
    }

    /**
     * Retires every cached module whose source is no longer in [installed], closing each one so the
     * engine releases its instance rather than leaving it to answer calls for a bundle that is gone.
     *
     * A module mid-call is removed from the map immediately — so no later [acquire] can find it — but
     * closed by its own [release] rather than here: on iOS `close` frees the native instance, and freeing
     * one while the guest is executing inside it is a use-after-free rather than a leak.
     *
     * Only *removal* is handled here. A bundle rewritten in place keeps its id, so this cannot see it —
     * today that is harmless, because the only writer is the dev installer and it runs at launch before
     * anything is loaded. The real installer will know it is replacing a bundle and should evict that id
     * directly.
     */
    private suspend fun evictModulesOutside(installed: List<String>) {
        val keep = installed.toSet()
        modulesLock.withLock {
            val removed = modules.keys.filterNot { it in keep }
            removed.forEach { id ->
                val loaded = modules.remove(id) ?: return@forEach
                if (loaded.inUse == 0) loaded.module.close() else loaded.closeWhenIdle = true
            }
        }
    }

    /**
     * A cached module, the lock that keeps its single engine instance single-threaded, and the two flags
     * that say whether it may be closed. Both flags are guarded by [modulesLock], never by [lock].
     */
    private class LoadedModule(
        val module: WasmModule,
        val lock: Mutex = Mutex(),
    ) {
        /** Calls currently borrowing this module. Non-zero means it must not be closed. */
        var inUse: Int = 0

        /** Set when the module was retired mid-call; its last [release] does the closing. */
        var closeWhenIdle: Boolean = false
    }

    private companion object {
        /** Reused empty payload for no-argument exports (e.g. SOURCE_META). */
        val EMPTY = ByteArray(0)

        /**
         * How many instantiated modules the fetch path may keep.
         *
         * Three, because the app's access pattern is overwhelmingly one source at a time: a reading
         * session never leaves its series' source, and the detail screen's parallel details+chapters
         * refresh is two calls to the *same* one. Two spare slots cover moving between a couple of
         * sources in browse without paying to re-instantiate on every hop, and everything past that is a
         * source the user has moved on from.
         */
        const val MAX_CACHED_MODULES = 3
    }
}
