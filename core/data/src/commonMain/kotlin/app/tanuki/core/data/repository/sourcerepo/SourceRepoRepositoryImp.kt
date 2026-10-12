package app.tanuki.core.data.repository.sourcerepo

import app.tanuki.core.domain.source.repository.SourceRepoRepository
import app.tanuki.core.domain.source.repository.StoredSourceRepo
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Room-backed [SourceRepoRepository]; plain DAO delegation, so no IO hop of its own. */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class SourceRepoRepositoryImp(
    private val dao: SourceRepoDao
) : SourceRepoRepository {
    override fun observeRepos(): Flow<List<StoredSourceRepo>> = dao.observeRepos().map { rows -> rows.map(SourceRepoEntity::toDomain) }

    override suspend fun getRepos(): List<StoredSourceRepo> = dao.getRepos().map(SourceRepoEntity::toDomain)

    override suspend fun saveRepo(repo: StoredSourceRepo) = dao.upsert(repo.toEntity())

    override suspend fun removeRepo(baseUrl: String) = dao.delete(baseUrl)
}
