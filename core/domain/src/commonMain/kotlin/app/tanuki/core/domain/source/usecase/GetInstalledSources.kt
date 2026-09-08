package app.tanuki.core.domain.source.usecase

import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.common.service.warn
import app.tanuki.core.domain.source.SourceLoader
import app.tanuki.core.domain.source.model.SourceMeta
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch

/**
 * Use case: observe the installed sources' metadata, for the Home screen's source list and for
 * resolving one source's tabs/title before browsing it.
 *
 * Observable rather than one-shot so a screen showing sources follows the installed set instead of
 * holding a list that goes stale the moment a source is installed or uninstalled underneath it. Each
 * emission re-reads every bundle's metadata, which is comparatively expensive, so collect it once per
 * screen rather than per row.
 *
 * Owns what a failed enumeration *means*: reading the installed bundles is best-effort, so a failure
 * yields an empty list rather than propagating. The loader already skips an individual bad bundle; this
 * additionally absorbs an unexpected failure of the whole stream, so no caller has to decide whether
 * "couldn't read the bundles" is an error state or an empty one — it is always the latter. Such a
 * failure also **ends** the stream: since the loader is already resilient per bundle, anything reaching
 * here is the enumeration itself breaking, which is terminal rather than transient.
 *
 * @see GetSourceMeta for the single-source lookup.
 */
interface GetInstalledSources {
    operator fun invoke(): Flow<List<SourceMeta>>

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val sourceLoader: SourceLoader,
        private val logger: Logger
    ) : GetInstalledSources {
        override fun invoke(): Flow<List<SourceMeta>> =
            sourceLoader
                .observeInstalledSources()
                .catch { error ->
                    logger.warn(TAG, "Failed to enumerate installed sources", error)
                    emit(emptyList())
                }

        private companion object {
            const val TAG = "GetInstalledSources"
        }
    }
}