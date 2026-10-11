package app.tanuki.core.domain.source.usecase

import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.common.service.warn
import app.tanuki.core.domain.source.SourceCatalog
import app.tanuki.core.domain.source.model.CatalogSource
import app.tanuki.core.domain.source.model.Source
import app.tanuki.core.domain.source.model.SourceMeta
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Use case: observe every source the user can manage — those installed, plus those the [SourceCatalog]
 * could install — as one list of [CatalogSource]s, re-emitting whenever the installed set moves.
 *
 * This is what the source-management screen renders. It exists because neither existing source stream
 * can answer the question on its own: [GetInstalledSources] reads each bundle's own metadata, so it
 * cannot describe a source whose bundle is gone, and [ObserveSources] only knows sources that have
 * actually been loaded at least once. Joining them gives an uninstalled source a real name and icon —
 * the snapshot taken when it *was* installed — instead of a bare slug.
 *
 * Entries are sorted by display name so the list is stable across emissions; splitting them by
 * [CatalogSource.installed] is the caller's business, since that is a presentation choice.
 */
sealed interface ObserveSourceCatalog {
    operator fun invoke(): Flow<List<CatalogSource>>

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val getInstalledSources: GetInstalledSources,
        private val observeSources: ObserveSources,
        private val catalog: SourceCatalog,
        private val logger: Logger
    ) : ObserveSourceCatalog {
        override fun invoke(): Flow<List<CatalogSource>> =
            combine(getInstalledSources(), observeSources(), ::Pair)
                .map { (installed, known) -> merge(installed, known) }

        private suspend fun merge(
            installed: List<SourceMeta>,
            known: List<Source>
        ): List<CatalogSource> {
            // Re-read per emission rather than once: the catalog is the app's bundled list today, but a
            // remote repo will change under the screen, and a list that only grows at startup would go
            // stale exactly when it matters. A failing catalog degrades to "nothing to install" — the
            // installed half of the screen is still correct and still uninstallable.
            val available =
                runCatching { catalog.availableIds() }
                    .onFailure { error -> logger.warn(TAG, "Could not read the source catalog", error) }
                    .getOrDefault(emptyList())
            val installedIds = installed.mapTo(mutableSetOf(), SourceMeta::id)
            val knownBySourceId = known.associateBy(Source::sourceId)

            val installedEntries =
                installed.map { meta ->
                    CatalogSource(
                        sourceId = meta.id,
                        name = meta.name,
                        lang = meta.lang,
                        iconUrl = meta.iconUrl,
                        installed = true,
                        nsfw = meta.nsfw
                    )
                }
            val availableEntries =
                (available - installedIds).map { sourceId ->
                    // The persisted row is the only description left once a bundle is gone. A source
                    // that has never been loaded on this device has none, and falls back to its id.
                    val persisted = knownBySourceId[sourceId]
                    CatalogSource(
                        sourceId = sourceId,
                        name = persisted?.name ?: Text(sourceId),
                        lang = persisted?.lang,
                        iconUrl = persisted?.iconUrl,
                        installed = false
                    )
                }

            return (installedEntries + availableEntries).sortedBy { it.name.value.lowercase() }
        }

        private companion object {
            const val TAG = "ObserveSourceCatalog"
        }
    }
}
