package app.tanuki.core.domain.download.service

/**
 * Gets the download queue moving — the seam between "a chapter was enqueued" and "something is
 * actually fetching it".
 *
 * Enqueueing writes a row; it deliberately does not start a coroutine, because *where* the work runs is
 * a platform question (an app-lifetime scope while the app is open, a WorkManager job, a
 * `BGProcessingTask`) and the domain has no business answering it. Every enqueue path therefore ends in
 * [ensureRunning], and the platform decides what that costs.
 *
 * @see app.tanuki.core.domain.download.usecase.DownloadManager for what actually runs.
 */
interface DownloadScheduler {
    /**
     * Whether work handed to [ensureRunning] keeps going once the user leaves the app.
     *
     * `true` on Android, where the queue is a WorkManager job with a `dataSync` foreground service.
     * `false` on iOS, which will not run a Ktor download loop in the background for minutes: there is a
     * short grace period and a `BGProcessingTask` the system may or may not grant, and neither is
     * something to promise a user (`docs/design-downloads.md` §7).
     *
     * Exposed so the queue screen can *say so* rather than leaving an iOS user to discover it by coming
     * back to a queue that has not moved. It lives on the scheduler because the scheduler is the thing
     * that decides where work runs — a separate capability port would be a second answer to a question
     * this interface already owns.
     */
    val runsInBackground: Boolean

    /**
     * Ensures the queue is being worked, starting a run if none is in flight.
     *
     * Idempotent by contract: it is called on every enqueue and at every app launch, and a call while a
     * run is already going must not put a second worker on the same rows. There is no matching `stop()`
     * — a run ends when the queue is empty, and the way to end one early is to take its work away
     * ([app.tanuki.core.domain.download.usecase.DownloadManager.abort] plus deleting the rows), which is
     * what cancelling and deleting already do.
     */
    fun ensureRunning()
}