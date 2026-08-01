package app.tanuki.core.domain.source

/**
 * The set of sources the app can install, and the ability to install one — the *supply* side of the
 * source lifecycle, opposite [SourceBundleStore], which owns what is currently installed.
 *
 * Deliberately narrow: it answers "what could be installed?" and "put this one on disk", nothing more.
 * Where the bytes come from is the implementation's business — today the `.wasm` bundles shipped inside
 * the app, later a remote source repo — and neither the domain nor the UI has to change when that
 * swaps. Keeping it separate from [SourceBundleStore] is what lets an uninstall be **reversible**: the
 * store forgets the bundle, and the catalog can still hand it back.
 *
 * Ids here are **source-native strings** (e.g. `"mangadex"`), the same ids [SourceBundleStore] and
 * [SourceLoader] use.
 */
interface SourceCatalog {
    /**
     * Source-native ids this catalog can install, whether or not they currently are. Empty when the
     * catalog has nothing to offer.
     */
    suspend fun availableIds(): List<String>

    /**
     * Installs [sourceId]'s bundle into the [SourceBundleStore], replacing any bundle already there.
     *
     * @throws IllegalArgumentException if [sourceId] is not in [availableIds].
     */
    suspend fun install(sourceId: String)
}
