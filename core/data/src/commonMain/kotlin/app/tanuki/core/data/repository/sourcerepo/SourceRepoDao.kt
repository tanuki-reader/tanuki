package app.tanuki.core.data.repository.sourcerepo

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Room DAO for [SourceRepoEntity]. */
@Dao
interface SourceRepoDao {
    @Query("SELECT * FROM source_repos ORDER BY rowid")
    fun observeRepos(): Flow<List<SourceRepoEntity>>

    @Query("SELECT * FROM source_repos ORDER BY rowid")
    suspend fun getRepos(): List<SourceRepoEntity>

    @Upsert
    suspend fun upsert(repo: SourceRepoEntity)

    @Query("DELETE FROM source_repos WHERE base_url = :baseUrl")
    suspend fun delete(baseUrl: String)
}
