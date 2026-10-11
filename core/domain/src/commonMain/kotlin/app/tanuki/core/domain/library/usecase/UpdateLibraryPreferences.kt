package app.tanuki.core.domain.library.usecase

import app.tanuki.core.domain.library.model.LibraryPreferences
import app.tanuki.core.domain.settings.repository.SettingsRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

/**
 * Changes the library's remembered filter and sort. A [transform] of the stored value rather than a
 * value to write, so two quick taps on different controls each apply to what the other left behind.
 */
interface UpdateLibraryPreferences {
    suspend operator fun invoke(transform: (LibraryPreferences) -> LibraryPreferences)

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val settingsRepository: SettingsRepository
    ) : UpdateLibraryPreferences {
        override suspend fun invoke(transform: (LibraryPreferences) -> LibraryPreferences) =
            settingsRepository.updateLibraryPreferences(transform)
    }
}
