package app.tanuki.core.domain.source.usecase

import app.tanuki.core.domain.manga.repository.MangaRepository
import app.tanuki.core.domain.source.SourceLoader
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * Use case: the source-native keys of every library series from one source, live — what a browse
 * screen checks a listing's [app.tanuki.core.domain.source.model.MangaListing.sourceMangaKey] against to
 * mark it "In library".
 */
interface ObserveLibraryKeys {
    operator fun invoke(sourceId: String): Flow<Set<String>>

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val sourceLoader: SourceLoader,
        private val mangaRepository: MangaRepository
    ) : ObserveLibraryKeys {
        override fun invoke(sourceId: String): Flow<Set<String>> =
            flow {
                // Library rows reference the source's local id; resolving it installs on first use, as
                // every other source-native entry point does.
                emitAll(mangaRepository.observeLibraryKeys(sourceLoader.loadSource(sourceId).id))
            }
    }
}
