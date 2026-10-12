package app.tanuki.core.data.repository.sourcerepo

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Which repo an installed source came from. See [app.tanuki.core.domain.source.repository.SourceInstallationRepository]. */
@Entity(tableName = "source_installations")
data class SourceInstallationEntity(
    @PrimaryKey @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "repo_url") val repoUrl: String,
)
