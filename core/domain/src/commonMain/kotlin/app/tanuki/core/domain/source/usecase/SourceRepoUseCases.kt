package app.tanuki.core.domain.source.usecase

import app.tanuki.core.domain.source.SourceCatalog
import app.tanuki.core.domain.source.model.RepoPreview
import app.tanuki.core.domain.source.model.SourceRepo
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow

// The source-repo use cases: thin, because the trust decisions (signature, pinned key, hashes) all live
// in the SourceCatalog implementation. Failures throw SourceRepoException; the screen maps its error.

/** Use case: observe the added source repos. */
sealed interface ObserveSourceRepos {
    operator fun invoke(): Flow<List<SourceRepo>>

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(private val catalog: SourceCatalog) : ObserveSourceRepos {
        override fun invoke() = catalog.observeRepos()
    }
}

/** Use case: fetch what adding a repo URL would pin (name, key fingerprint), storing nothing. */
sealed interface PreviewSourceRepo {
    suspend operator fun invoke(url: String): RepoPreview

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(private val catalog: SourceCatalog) : PreviewSourceRepo {
        override suspend fun invoke(url: String) = catalog.previewRepo(url)
    }
}

/** Use case: add a previewed repo, pinning its key. */
sealed interface AddSourceRepo {
    suspend operator fun invoke(preview: RepoPreview)

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(private val catalog: SourceCatalog) : AddSourceRepo {
        override suspend fun invoke(preview: RepoPreview) = catalog.addRepo(preview)
    }
}

/**
 * Use case: remove a repo. Installed sources stay installed — the bundle is on disk — they just stop
 * being offered from it.
 */
sealed interface RemoveSourceRepo {
    suspend operator fun invoke(baseUrl: String)

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(private val catalog: SourceCatalog) : RemoveSourceRepo {
        override suspend fun invoke(baseUrl: String) = catalog.removeRepo(baseUrl)
    }
}

/** Use case: re-fetch every repo's index. */
sealed interface RefreshSourceRepos {
    suspend operator fun invoke()

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(private val catalog: SourceCatalog) : RefreshSourceRepos {
        override suspend fun invoke() = catalog.refresh()
    }
}
