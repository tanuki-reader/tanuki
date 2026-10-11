package app.tanuki.core.domain.source.usecase

import app.tanuki.core.domain.source.model.MangaListing
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * Use case: one query across several sources — the global search. Emits each source's result as it
 * answers, so a fast source shows before a slow one, then completes.
 *
 * Each source is on its own: a failure or a source that takes longer than [TIMEOUT] is that source's
 * [SourceSearch.Failed], never the search's. At most [PARALLEL] run at once, because every engine call
 * holds an IO thread for its whole round trip.
 */
interface SearchSources {
    operator fun invoke(
        query: String,
        sourceIds: List<String>
    ): Flow<Pair<String, SourceSearch>>

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val browseSource: BrowseSource
    ) : SearchSources {
        override fun invoke(
            query: String,
            sourceIds: List<String>
        ): Flow<Pair<String, SourceSearch>> =
            // Completes when every source's child has sent.
            channelFlow {
                val gate = Semaphore(PARALLEL)
                sourceIds.forEach { sourceId ->
                    launch {
                        val entries = gate.withPermit { search(sourceId, query) }
                        send(sourceId to (entries?.let(SourceSearch::Loaded) ?: SourceSearch.Failed))
                    }
                }
            }

        private suspend fun search(
            sourceId: String,
            query: String
        ): List<MangaListing>? =
            try {
                withTimeoutOrNull(TIMEOUT) { browseSource(sourceId, query = query).entries }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
    }

    companion object {
        const val PARALLEL = 4
        val TIMEOUT = 20.seconds
    }
}

/** One source's part of a global search. */
sealed interface SourceSearch {
    data object Loading : SourceSearch

    data class Loaded(
        val entries: List<MangaListing>
    ) : SourceSearch

    data object Failed : SourceSearch
}
