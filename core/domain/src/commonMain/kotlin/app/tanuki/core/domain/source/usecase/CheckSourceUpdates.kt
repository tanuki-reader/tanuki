package app.tanuki.core.domain.source.usecase

import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.common.service.warn
import app.tanuki.core.domain.source.model.CatalogSource
import app.tanuki.core.domain.update.service.LibraryUpdateNotifier
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Use case: the background job's source-update check. Refreshes every repo's signed index, then notifies
 * about versions that **this refresh** made available.
 *
 * Only what is new in this run is announced, so a pending update is notified once, not every time the job
 * runs; it stays visible as the badge on Settings → Sources and in the Updates section. No "already
 * notified" state is kept: the launch refresh (which doesn't notify, the user is in the app) moves the
 * cached index forward too, so anything it found simply isn't new to the next background run.
 */
sealed interface CheckSourceUpdates {
    suspend operator fun invoke()

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val observeSourceCatalog: ObserveSourceCatalog,
        private val refreshSourceRepos: RefreshSourceRepos,
        private val notifier: LibraryUpdateNotifier,
        private val logger: Logger
    ) : CheckSourceUpdates {
        override suspend fun invoke() {
            val before = updates()
            try {
                refreshSourceRepos()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // A repo that failed keeps its last verified index; the others still refreshed.
                logger.warn(TAG, "Could not refresh every source repo", error)
            }
            val fresh = updates().values.filter { before[it.sourceId]?.updateVersion != it.updateVersion }
            notifier.notifySourceUpdates(fresh.map { it.name.value })
        }

        // ponytail: enumerates the installed bundles twice per run (each is instantiated once to read its
        // metadata); fine for a handful of sources, cache the metadata if this ever gets slow.
        private suspend fun updates(): Map<String, CatalogSource> =
            observeSourceCatalog().first().filter { it.updateVersion != null }.associateBy(CatalogSource::sourceId)

        private companion object {
            const val TAG = "CheckSourceUpdates"
        }
    }
}
