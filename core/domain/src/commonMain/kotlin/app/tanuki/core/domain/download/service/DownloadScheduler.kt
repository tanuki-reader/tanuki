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