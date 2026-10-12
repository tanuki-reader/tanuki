package app.tanuki.core.data.repository.sourcerepo

import app.tanuki.core.domain.source.repository.SourceInstallationRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Room-backed [SourceInstallationRepository]; plain DAO delegation. */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class SourceInstallationRepositoryImp(
    private val dao: SourceInstallationDao
) : SourceInstallationRepository {
    override fun observeOrigins(): Flow<Map<String, String>> = dao.observeAll().map { rows -> rows.associate { it.sourceId to it.repoUrl } }

    override suspend fun getOrigin(sourceId: String): String? = dao.getRepoUrl(sourceId)

    override suspend fun setOrigin(
        sourceId: String,
        repoUrl: String
    ) = dao.upsert(SourceInstallationEntity(sourceId, repoUrl))
}
