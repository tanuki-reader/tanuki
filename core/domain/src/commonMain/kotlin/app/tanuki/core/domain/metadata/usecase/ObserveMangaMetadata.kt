package app.tanuki.core.domain.metadata.usecase

import app.tanuki.core.domain.metadata.MetadataRepository
import app.tanuki.core.domain.metadata.model.SeriesMetadata
import app.tanuki.core.domain.metadata.model.TrackerLink
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlin.uuid.Uuid

/** A series' link and, once fetched, its metadata — `null` link until enrichment first ran. */
data class MangaMetadata(
    val link: TrackerLink?,
    val metadata: SeriesMetadata?
)

/** Use case: [MangaMetadata] for one series, live — it updates when enrichment lands or the user relinks. */
interface ObserveMangaMetadata {
    operator fun invoke(mangaId: Uuid): Flow<MangaMetadata>

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val metadataRepository: MetadataRepository
    ) : ObserveMangaMetadata {
        @OptIn(ExperimentalCoroutinesApi::class)
        override fun invoke(mangaId: Uuid): Flow<MangaMetadata> =
            metadataRepository.observeLink(mangaId).flatMapLatest { link ->
                val anilistId = link?.anilistId
                if (anilistId == null) {
                    flowOf(MangaMetadata(link, null))
                } else {
                    metadataRepository.observeMetadata(anilistId).map { MangaMetadata(link, it) }
                }
            }
    }
}
