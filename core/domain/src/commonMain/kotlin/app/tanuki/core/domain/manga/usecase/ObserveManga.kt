package app.tanuki.core.domain.manga.usecase

import app.tanuki.core.domain.manga.model.Manga
import app.tanuki.core.domain.manga.repository.MangaRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

interface ObserveManga {
    operator fun invoke(mangaId: Uuid): Flow<Manga?>

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val mangaRepository: MangaRepository
    ) : ObserveManga {
        override fun invoke(mangaId: Uuid): Flow<Manga?> = mangaRepository.observeManga(mangaId)
    }
}