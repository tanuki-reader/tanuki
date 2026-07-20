package app.tanuki.core.domain.source.usecase

import app.tanuki.core.domain.source.model.Source
import app.tanuki.core.domain.source.repository.SourceRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow

/**
 * Use case: observe every locally persisted [Source], re-emitting on any change. The manga detail
 * screen uses it to resolve the source a manga belongs to (matching [Source.id] against
 * [app.tanuki.core.domain.manga.model.Manga.source]).
 */
sealed interface ObserveSources {
    operator fun invoke(): Flow<List<Source>>

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val sourceRepository: SourceRepository
    ) : ObserveSources {
        override fun invoke(): Flow<List<Source>> = sourceRepository.observeSources()
    }
}
