package app.tanuki.core.domain.source.repository

import kotlinx.coroutines.flow.Flow

/**
 * Persistence for added source repos, including each one's pinned key and last verified index (so the
 * catalog still lists sources offline). Holds raw, already-verified data; parsing and trust decisions
 * are the catalog's.
 */
interface SourceRepoRepository {
    fun observeRepos(): Flow<List<StoredSourceRepo>>

    suspend fun getRepos(): List<StoredSourceRepo>

    suspend fun saveRepo(repo: StoredSourceRepo)

    suspend fun removeRepo(baseUrl: String)
}

/**
 * @property publicKey base64 ed25519 key pinned when the repo was added.
 * @property indexJson the last index whose signature verified against [publicKey].
 * @property fetchedAt epoch milliseconds of that verification.
 */
data class StoredSourceRepo(
    val baseUrl: String,
    val name: String,
    val publicKey: String,
    val indexJson: String,
    val fetchedAt: Long
)
