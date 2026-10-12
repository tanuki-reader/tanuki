package app.tanuki.core.domain.source

import app.tanuki.core.domain.source.model.InstallProgress
import app.tanuki.core.domain.source.model.RemoteSource
import app.tanuki.core.domain.source.model.RepoPreview
import app.tanuki.core.domain.source.model.SourceRepo
import kotlinx.coroutines.flow.Flow

/**
 * The sources the app can install and where they come from: the *supply* side of the source lifecycle,
 * opposite [SourceBundleStore], which owns what is currently installed.
 *
 * Supply is the source repos the user added, each a signed static index (see
 * `docs/design-remote-sources.md`). The app ships none. Keeping this separate from [SourceBundleStore]
 * is what lets an uninstall be **reversible**: the store forgets the bundle, and the catalog can still
 * hand it back.
 *
 * Ids are **source-native strings** (e.g. `"mangadex"`), the same ids [SourceBundleStore] and
 * [SourceLoader] use. Repo failures are thrown as [app.tanuki.core.domain.source.model.SourceRepoException].
 */
interface SourceCatalog {
    /** Every source the added repos offer, from their last verified index (so it works offline). */
    fun observeAvailable(): Flow<List<RemoteSource>>

    /** The added repos, in the order they were added. */
    fun observeRepos(): Flow<List<SourceRepo>>

    /** Fetches and verifies what adding [url] would pin, without storing anything. */
    suspend fun previewRepo(url: String): RepoPreview

    /** Adds the repo [preview] describes, pinning its key and storing the index it verified. */
    suspend fun addRepo(preview: RepoPreview)

    suspend fun removeRepo(baseUrl: String)

    /** Re-fetches every repo's index. A repo that fails keeps its last good index; the first failure is thrown. */
    suspend fun refresh()

    /**
     * Downloads, verifies and installs [sourceId]'s bundle into the [SourceBundleStore], replacing any
     * bundle already there, reporting each step to [onProgress].
     *
     * @throws IllegalArgumentException if no added repo offers a compatible [sourceId].
     * @throws app.tanuki.core.domain.source.model.SourceRepoException naming the step that failed.
     */
    suspend fun install(
        sourceId: String,
        onProgress: (InstallProgress) -> Unit = {}
    )
}
