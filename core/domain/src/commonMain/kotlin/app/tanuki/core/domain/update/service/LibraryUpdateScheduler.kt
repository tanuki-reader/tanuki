package app.tanuki.core.domain.update.service

import app.tanuki.core.domain.settings.model.LibraryUpdateInterval

/**
 * Asks the platform to run a library update periodically — WorkManager on Android, `BGTaskScheduler` on
 * iOS.
 *
 * Both are **best-effort**: they promise "roughly this often, when convenient", and iOS in particular
 * may skip a rarely-opened app for days. Nothing in the app may assume a scheduled run happened, which
 * is why the launch-time catch-up in `App` exists alongside this rather than as a fallback for it.
 */
interface LibraryUpdateScheduler {
    /**
     * Makes the platform's schedule match [interval], replacing whatever was scheduled before.
     * [LibraryUpdateInterval.OFF] cancels it entirely.
     *
     * One method rather than a `schedule`/`cancel` pair on purpose: "off" is a value of the setting,
     * not a separate operation, and the caller then has no way to change the interval while forgetting
     * to handle the off case.
     *
     * Safe to call repeatedly with the same value — re-applying an unchanged schedule must not reset
     * its timer, or an app that is opened often would never reach the end of one period.
     */
    fun schedule(interval: LibraryUpdateInterval)
}