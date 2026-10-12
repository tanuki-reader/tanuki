package app.tanuki.core.domain.source.repository

import kotlinx.coroutines.flow.Flow

/**
 * Which repo each installed source came from, so its updates come only from that repo: a second repo
 * offering the same id must not be able to replace another author's code. A source with no record (left
 * from before repos) is adopted by the first repo it is installed or updated from.
 */
interface SourceInstallationRepository {
    /** Source id → the base URL of the repo it was installed from. */
    fun observeOrigins(): Flow<Map<String, String>>

    suspend fun getOrigin(sourceId: String): String?

    suspend fun setOrigin(
        sourceId: String,
        repoUrl: String
    )
}
