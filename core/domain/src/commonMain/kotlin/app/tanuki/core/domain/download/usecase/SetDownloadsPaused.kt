package app.tanuki.core.domain.download.usecase

import app.tanuki.core.domain.download.service.DownloadScheduler
import app.tanuki.core.domain.settings.repository.SettingsRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

/**
 * Use case: pause or resume the download queue — the queue screen's one top-bar toggle.
 *
 * The two directions are not symmetric, which is the whole reason this is a use case rather than a
 * repository call:
 *
 *  - **Pausing** only writes the flag. The run in flight notices it on its own and stops after the page
 *    it is holding, leaving the chapter's `.part` directory to resume from — nothing here has to reach
 *    into the engine, and nothing is thrown away.
 *  - **Resuming** writes the flag *and* starts the engine, because the run that saw the pause has
 *    already ended. Without that call, resuming would clear the flag and leave the queue sitting there
 *    until something else happened to enqueue a chapter — which from the outside is indistinguishable
 *    from the button not working.
 *
 * The flag is written first in both directions, so a run started by [DownloadScheduler.ensureRunning]
 * can never read a stale `paused = true` and stop immediately.
 */
sealed interface SetDownloadsPaused {
    /** @param paused `true` to stop the queue after the page in flight; `false` to start it again. */
    suspend operator fun invoke(paused: Boolean)

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val settingsRepository: SettingsRepository,
        private val scheduler: DownloadScheduler,
    ) : SetDownloadsPaused {
        override suspend fun invoke(paused: Boolean) {
            settingsRepository.setDownloadsPaused(paused)
            if (!paused) scheduler.ensureRunning()
        }
    }
}