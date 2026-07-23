package app.tanuki.core.domain.source.usecase

import app.tanuki.core.domain.source.SourceLoader
import app.tanuki.core.domain.source.model.SourceMeta
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

/**
 * Use case: enumerate the installed sources' metadata, for the Home screen's source list and for
 * resolving one source's tabs/title before browsing it.
 *
 * Owns what a failed enumeration *means*: reading the installed bundles is best-effort, so a failure
 * yields an empty list rather than propagating. The loader already skips an individual bad bundle; this
 * additionally absorbs an unexpected failure of the whole call, so no caller has to decide whether
 * "couldn't read the bundles" is an error state or an empty one — it is always the latter.
 *
 * Not observable: the installed set only changes on install/uninstall, and reading each bundle's
 * metadata is comparatively expensive, so callers pull it once rather than subscribing.
 *
 * @see GetSourceMeta for the single-source lookup.
 */
sealed interface GetInstalledSources {
    suspend operator fun invoke(): List<SourceMeta>

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val sourceLoader: SourceLoader
    ) : GetInstalledSources {
        override suspend fun invoke(): List<SourceMeta> =
            runCatching { sourceLoader.listSources() }.getOrDefault(emptyList())
    }
}