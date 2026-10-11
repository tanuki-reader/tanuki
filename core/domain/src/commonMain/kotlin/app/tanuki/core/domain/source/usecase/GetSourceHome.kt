package app.tanuki.core.domain.source.usecase

import app.tanuki.core.domain.source.SourceLoader
import app.tanuki.core.domain.source.model.MangaListing
import app.tanuki.core.domain.source.model.SourceListing
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Use case: a source's home — the first page of each listing it declares, then its default catalog (the
 * "All series" preview).
 *
 * Uses the source's optional one-call home when it has one, and fills any declared listing that home left
 * out (or all of them, for the many sources without one) with one catalog fetch each, in parallel. Each
 * section succeeds or fails on its own, so one slow or broken listing doesn't blank the screen; a failed
 * one comes back with `entries = null` for the screen to offer a retry.
 */
interface GetSourceHome {
    suspend operator fun invoke(
        sourceId: String,
        listings: List<SourceListing>
    ): List<SourceHomeRail>

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val sourceLoader: SourceLoader,
        private val browseSource: BrowseSource
    ) : GetSourceHome {
        override suspend fun invoke(
            sourceId: String,
            listings: List<SourceListing>
        ): List<SourceHomeRail> =
            coroutineScope {
                // A home that fails as a whole (an older bundle without the export traps on the missing
                // symbol) is no worse than having none: every rail is fetched on its own below.
                val home = orNull { sourceLoader.fetchSourceHome(sourceId) }
                val fromHome = home?.sections.orEmpty().associate { it.listing to it.entries }
                (listings + null)
                    .map { listing ->
                        async {
                            val entries =
                                fromHome[listing]
                                    ?: orNull { browseSource(sourceId, listing).entries }
                            SourceHomeRail(listing, entries)
                        }
                    }.awaitAll()
            }
    }
}

/** [block]'s result, or `null` if it failed — cancellation still propagates. */
private inline fun <T> orNull(block: () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

/**
 * One rail of a source's home: [listing] (`null` = the default catalog) and its first page, or `null`
 * [entries] when that fetch failed.
 */
data class SourceHomeRail(
    val listing: SourceListing?,
    val entries: List<MangaListing>?
)
