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
import app.tanuki.core.domain.source.model.SourceHome
import app.tanuki.core.domain.source.model.SourceListing
import app.tanuki.core.domain.source.model.SourceMeta
import app.tanuki.core.domain.settings.usecase.ObserveShowSensitiveContent
import app.tanuki.core.domain.source.repository.SourceRepository
import app.tanuki.core.source.abi.SourceAbi
import app.tanuki.core.source.abi.SourceAbiException
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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
 * Modules are cached per source id, and that cache is bounded: at most [MAX_CACHED_MODULES] sources are
 * kept, the least recently used closed to make room (see [trim]). Each keeps **one** idle instance; up to
 * [MAX_INSTANCES_PER_SOURCE] calls to one source run at once, each on an instance of its own, and the
 * overflow instance is closed when its call returns (see [callModule]). An engine instance is the heaviest
 * long-lived object the app holds — on iOS `close` frees memory the Kotlin heap can't see at all — so
 * "instantiated once" is deliberately not the contract; "one instance per source *while idle*" is. Only
 * the `fetch*` path caches:
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
    private val observeShowSensitiveContent: ObserveShowSensitiveContent,
) : SourceLoader {

    private val modulesLock = Mutex()

    /**
     * The instance pools of the sources being *fetched* from, least-recently-used first.
     *
     * A [LinkedHashMap] rather than a plain map because that iteration order is the eviction order (see
     * [trim]); [pin] re-inserts on every call to keep it true.
     */
    private val modules = LinkedHashMap<String, SourceModules>()

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
                allowSensitive = allowSensitive(),
            ),
        )
        SourceCodec.decodeEnvelope<Wire.MangaPageWire>(
            callModule(sourceId, SourceAbi.Exports.MANGA_LIST, request),
        ).toDomain()
    }

    override suspend fun fetchSourceHome(sourceId: String): SourceHome? =
        withContext(dispatchers.io) {
            requireConnection(sourceId, SourceAbi.Exports.SOURCE_HOME)
            val request = SourceCodec.encode(Wire.SourceHomeRequest(allowSensitive = allowSensitive()))
            try {
                SourceCodec.decodeEnvelope<Wire.SourceHomeWire>(
                    callModule(sourceId, SourceAbi.Exports.SOURCE_HOME, request),
                ).toDomain()
            } catch (e: SourceAbiException) {
                // The SDK's default answer for a source that doesn't implement it. Anything else (a bundle
                // built before the export existed traps on the missing symbol) propagates, and the caller
                // falls back to per-listing fetches either way.
                if (e.code == UNSUPPORTED) null else throw e
            }
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
    /**
     * The user's sensitive-content choice, read per call. A host concern rather than a [SourceLoader]
     * argument: no caller should be able to ask a source for content the user hasn't allowed.
     */
    private suspend fun allowSensitive(): Boolean = observeShowSensitiveContent().first()

    private fun requireConnection(sourceId: String, export: String) {
        if (!connectionObserver.isOnline) {
            throw OfflineException("Cannot run \"$export\" for source \"$sourceId\" while offline")
        }
    }

    /**
     * Calls [export] on an instance of [sourceId]'s module that **no other call is using**.
     *
     * The exclusion is not optional. A [WasmModule] wraps a live engine instance with a single operand
     * stack and one linear memory; entering it twice at once interleaves two calls' stack traffic and
     * corrupts both (Chicory surfaces this as an `ArrayIndexOutOfBoundsException` from its interpreter,
     * far from the cause). So an instance is *owned* by the call running on it, from [take] to [giveBack].
     *
     * What is not required is that a source have only one instance, and that used to be the costly part.
     * Both engines bridge the guest's HTTP import with `runBlocking`, so a call holds its instance for
     * whole round-trips — retry backoff included, and a Cloudflare challenge the user is answering — and
     * a single instance per source queued everything behind that: the detail screen's parallel details +
     * chapters refresh, a browse behind a background library run working the same source, the reader
     * opening a chapter behind a download's page list. Up to [MAX_INSTANCES_PER_SOURCE] calls now overlap,
     * each on its own instance; past that a call waits for one to be handed back rather than making one.
     * No guest keeps state between calls, and none may — the cache can close an instance and make a fresh
     * one at any time (see [WasmModule]).
     *
     * [pin] / [unpin] bracket the whole call, *including* the wait, so the cap and the uninstall sweep can
     * tell a source that is merely cached from one that has a call running or queued — see [trim].
     */
    private suspend fun callModule(
        sourceId: String,
        export: String,
        input: ByteArray,
    ): ByteArray {
        val pool = pin(sourceId)
        try {
            return pool.permits.withPermit {
                val module = take(sourceId, pool)
                try {
                    module.call(export, input)
                } finally {
                    giveBack(pool, module)
                }
            }
        } finally {
            unpin(pool)
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
     * Marks [sourceId]'s pool as having a call, creating it on first use and moving it to the
     * most-recently-used end. Balanced by [unpin] — never call it without one.
     *
     * [modulesLock] guards the *map* only, and is not held while a call waits for or runs on an instance:
     * a long guest call must not block instantiating an unrelated source. That is exactly why
     * [SourceModules.borrowers] exists — a pool whose calls are running or waiting holds no lock of its
     * own that [trim] or [evictModulesOutside] could see, so they would otherwise be free to close it out
     * from under them.
     */
    private suspend fun pin(sourceId: String): SourceModules = modulesLock.withLock {
        // remove + put rather than a plain get: a LinkedHashMap keeps insertion order, so re-inserting a
        // hit is what makes iteration order least-recently-used first for trim().
        val pool = modules.remove(sourceId) ?: SourceModules()
        modules[sourceId] = pool
        pool.borrowers++
        trim()
        pool
    }

    /**
     * Ends the call [pin] began and brings the cache back under its cap.
     *
     * [NonCancellable] because this runs from a `finally` and [Mutex.lock] is cancellable — a fetch
     * cancelled here would otherwise skip the decrement and leave its source pinned in the cache for the
     * life of the process.
     */
    private suspend fun unpin(pool: SourceModules) = withContext(NonCancellable) {
        modulesLock.withLock {
            pool.borrowers--
            trim()
        }
    }

    /**
     * Hands the calling coroutine an instance of its own: the pool's idle one if it has it, a fresh one
     * otherwise. Called holding one of [SourceModules.permits], which is what bounds how many exist.
     */
    private suspend fun take(sourceId: String, pool: SourceModules): WasmModule = modulesLock.withLock {
        pool.idle.removeLastOrNull() ?: instantiate(sourceId)
    }

    /**
     * Returns an instance after its call: kept as the pool's idle instance if it has none, closed
     * otherwise — the second instance exists for the overlap only, so a burst leaves memory where it
     * found it. A pool retired mid-call ([SourceModules.retired]) closes everything handed back.
     *
     * [NonCancellable] for the same reason as [unpin]: a skipped hand-back leaks a live instance.
     */
    private suspend fun giveBack(pool: SourceModules, module: WasmModule) = withContext(NonCancellable) {
        modulesLock.withLock {
            if (pool.retired || pool.idle.isNotEmpty()) module.close() else pool.idle.addLast(module)
        }
    }

    /**
     * Closes least-recently-used sources until at most [MAX_CACHED_MODULES] remain. Call under
     * [modulesLock].
     *
     * The cap is **soft**: a source with a call running or waiting is skipped rather than waited for, so
     * calls to more distinct sources than the cap can hold exceed it briefly. Every [unpin] trims again,
     * so the excess lasts only as long as the concurrent calls do. Closing under [modulesLock] is safe
     * because a victim is removed from the map first and [SourceModules.borrowers] only ever rises under
     * that same lock, so nothing can reach it after the decision — and an unpinned pool's instances are
     * all idle, none executing.
     */
    private fun trim() {
        while (modules.size > MAX_CACHED_MODULES) {
            // Copied out of the entry before the removal: Kotlin/Native invalidates a `MutableEntry` the
            // moment its backing map changes and throws on the next read, where the JVM's tolerates it.
            val (id, victim) = modules.entries.firstOrNull { it.value.borrowers == 0 }
                ?.let { it.key to it.value }
                ?: return
            modules.remove(id)
            victim.closeIdle()
        }
    }

    /** Reads [sourceId]'s bundle, instantiates it and checks that its ABI version is one this host runs. */
    private suspend fun instantiate(sourceId: String): WasmModule {
        val bytes = bundleStore.readBundle(sourceId)
            ?: error("Source \"$sourceId\" is not installed.")
        return engine.instantiate(sourceId, bytes, host).also { module ->
            val version = module.callReturningI32(SourceAbi.Exports.ABI_VERSION)
            require(SourceAbi.isSupported(version)) {
                val why = if (version > SourceAbi.VERSION) "needs an app update" else "is outdated"
                "Source \"$sourceId\" targets ABI v$version and $why: the host runs " +
                    "v${SourceAbi.MIN_SUPPORTED}..v${SourceAbi.VERSION}."
            }
        }
    }

    /**
     * Retires every cached module whose source is no longer in [installed], closing each one so the
     * engine releases its instance rather than leaving it to answer calls for a bundle that is gone.
     *
     * A source mid-call is removed from the map immediately — so no later [pin] can find it — and its
     * idle instance closed, but an instance in use is closed by its own [giveBack] rather than here: on
     * iOS `close` frees the native instance, and freeing one while the guest is executing inside it is a
     * use-after-free rather than a leak.
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
                val pool = modules.remove(id) ?: return@forEach
                pool.retired = true
                pool.closeIdle()
            }
        }
    }

    /**
     * One source's instances: at most one idle, plus however many calls are running on others (bounded by
     * [permits]). Everything but [permits] is guarded by [modulesLock].
     */
    private class SourceModules {
        /** One per instance allowed at once; a call holds one from [take] to [giveBack]. */
        val permits = Semaphore(MAX_INSTANCES_PER_SOURCE)

        /** Instances no call is running on — at most one outside the moment of a hand-back. */
        val idle = ArrayDeque<WasmModule>()

        /** Calls running on *or waiting for* an instance. Non-zero means the pool must not be closed. */
        var borrowers: Int = 0

        /** Set when the source was uninstalled; every instance handed back after that is closed. */
        var retired: Boolean = false

        fun closeIdle() {
            while (idle.isNotEmpty()) idle.removeFirst().close()
        }
    }

    private companion object {
        /** Reused empty payload for no-argument exports (e.g. SOURCE_META). */
        val EMPTY = ByteArray(0)

        /** [Wire.AbiError.code] the SDK answers with for an export a source doesn't implement. */
        const val UNSUPPORTED = "unsupported"

        /**
         * How many sources the fetch path may keep instantiated — one idle instance each, so in steady
         * state also how many instances.
         *
         * Three, because the app's access pattern is overwhelmingly one source at a time: a reading
         * session never leaves its series' source, and the detail screen's parallel details+chapters
         * refresh is two calls to the *same* one. Two spare slots cover moving between a couple of
         * sources in browse without paying to re-instantiate on every hop, and everything past that is a
         * source the user has moved on from.
         */
        const val MAX_CACHED_MODULES = 3

        /**
         * How many calls to one source may run at once, each on an instance of its own.
         *
         * Two covers every overlap the app actually makes — the detail screen's details + chapters pair,
         * and one foreground call beside a background library run or download on the same source — for
         * one extra instantiation (a bundle of a couple of hundred KB) that is closed when its call
         * returns. More would multiply what a single source sees from us at once, which is the limit
         * that matters for its rate limits and its Cloudflare rules, for little gain.
         */
        const val MAX_INSTANCES_PER_SOURCE = 2
    }
}
