package app.tanuki.core.domain.download.usecase

import app.tanuki.core.domain.settings.model.AutoDownloadMode
import app.tanuki.core.domain.settings.repository.SettingsRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlin.uuid.Uuid

/**
 * Use case: set the auto-download rule — the mode and the categories it applies to, together.
 *
 * One use case for two fields because they are one decision. Writing them separately would leave a
 * window in which the mode says "selected categories" and the selection is still the old one, and the
 * thing reading it is a background sync that will happily act on whatever it sees.
 */
sealed interface SetAutoDownload {
    /**
     * @param mode Which new chapters to download automatically.
     * @param categories The categories [AutoDownloadMode.SelectedCategories] applies to. Passed even
     *   when the mode ignores them, so the selection survives being switched off and on again.
     */
    suspend operator fun invoke(
        mode: AutoDownloadMode,
        categories: Set<Uuid>,
    )

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val settingsRepository: SettingsRepository,
    ) : SetAutoDownload {
        override suspend fun invoke(
            mode: AutoDownloadMode,
            categories: Set<Uuid>,
        ) = settingsRepository.setAutoDownload(mode, categories)
    }
}
