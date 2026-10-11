package app.tanuki.core.domain.settings.usecase

import app.tanuki.core.domain.settings.model.LibraryGridSize
import app.tanuki.core.domain.settings.repository.SettingsRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

interface SetLibraryGridSize {
    suspend operator fun invoke(size: LibraryGridSize)

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val settingsRepository: SettingsRepository
    ) : SetLibraryGridSize {
        override suspend fun invoke(size: LibraryGridSize) = settingsRepository.setLibraryGridSize(size)
    }
}
