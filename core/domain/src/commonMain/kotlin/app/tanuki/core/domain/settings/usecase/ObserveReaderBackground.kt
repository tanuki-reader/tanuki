package app.tanuki.core.domain.settings.usecase

import app.tanuki.core.domain.settings.model.ReaderBackground
import app.tanuki.core.domain.settings.repository.SettingsRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow

interface ObserveReaderBackground {
    operator fun invoke(): Flow<ReaderBackground>

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val settingsRepository: SettingsRepository
    ) : ObserveReaderBackground {
        override fun invoke(): Flow<ReaderBackground> = settingsRepository.observeReaderBackground()
    }
}
