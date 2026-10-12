package app.tanuki.core.domain.source.usecase

import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.common.model.Url
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.common.service.warn
import app.tanuki.core.domain.source.SourceCatalog
import app.tanuki.core.domain.source.model.CatalogSource
import app.tanuki.core.domain.source.model.RemoteSource
import app.tanuki.core.domain.source.model.SourceCompatibility
import app.tanuki.core.domain.source.model.SourceMeta
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine

/**
 * Use case: observe every source the user can manage — those installed, plus those the added repos
 * offer — as one list of [CatalogSource]s, re-emitting whenever either side moves.
 *
 * This is what the source-management screen renders. An installed source is described by its bundle's
 * metadata, an installable one by its repo's verified index; a source that is both shows once, as
 * installed, with the repo that offers it.
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
        private val catalog: SourceCatalog,
        private val logger: Logger
    ) : ObserveSourceCatalog {
        override fun invoke(): Flow<List<CatalogSource>> =
            combine(getInstalledSources(), available(), origins(), ::merge)

        // A failing catalog degrades to "nothing to install": the installed half of the screen is still
        // correct and still uninstallable.
        private fun available(): Flow<List<RemoteSource>> =
            catalog.observeAvailable().catch { error ->
                logger.warn(TAG, "Could not read the source catalog", error)
                emit(emptyList())
            }

        private fun origins(): Flow<Map<String, String>> = catalog.observeOrigins().catch { emit(emptyMap()) }

        private fun merge(
            installed: List<SourceMeta>,
            available: List<RemoteSource>,
            origins: Map<String, String>
        ): List<CatalogSource> {
            // Two repos may offer the same id; prefer one this app can run.
            val offered =
                available
                    .sortedBy { it.compatibility != SourceCompatibility.Compatible }
                    .distinctBy(RemoteSource::id)
                    .associateBy(RemoteSource::id)
            val installedIds = installed.mapTo(mutableSetOf(), SourceMeta::id)

            val installedEntries =
                installed.map { meta ->
                    // Updates come only from the repo it was installed from (none if that repo was removed);
                    // with no origin recorded, from the offer the catalog would install (the first runnable).
                    val origin = origins[meta.id]
                    val source = if (origin != null) available.firstOrNull { it.id == meta.id && it.repoUrl == origin } else offered[meta.id]
                    val update =
                        source?.takeIf {
                            it.compatibility == SourceCompatibility.Compatible && meta.version != null && it.version > meta.version
                        }
                    CatalogSource(
                        sourceId = meta.id,
                        name = meta.name,
                        lang = meta.lang,
                        iconUrl = meta.iconUrl,
                        installed = true,
                        nsfw = meta.nsfw,
                        version = meta.version,
                        repoName = offered[meta.id]?.repoName,
                        updateVersion = update?.version
                    )
                }
            val availableEntries =
                (offered - installedIds).values.map { remote ->
                    CatalogSource(
                        sourceId = remote.id,
                        name = Text(remote.name),
                        lang = Text(remote.lang),
                        iconUrl = remote.iconUrl?.let(::Url),
                        installed = false,
                        nsfw = remote.nsfw,
                        version = remote.version,
                        repoName = remote.repoName,
                        compatibility = remote.compatibility
                    )
                }

            // By name, then language, so a source's language variants sit together.
            return (installedEntries + availableEntries).sortedWith(compareBy({ it.name.value.lowercase() }, { it.lang?.value }))
        }

        private companion object {
            const val TAG = "ObserveSourceCatalog"
        }
    }
}
