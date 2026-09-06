package app.tanuki.core.domain.download.model

/**
 * Why the download queue is not advancing, when it isn't.
 *
 * Exists so there is exactly **one** answer to that question, shared by the thing that acts on it and
 * the thing that explains it. The engine stops on anything but [None]; the queue screen turns the same
 * value into a sentence. Two independent rules would eventually disagree, and the failure mode is the
 * worst one this feature has: a queue full of chapters, nothing happening, and nothing on screen saying
 * why.
 *
 * Ordered by precedence — see `ObserveDownloadHold`.
 */
enum class DownloadHold {
    /** Nothing is holding the queue; it runs. */
    None,

    /** The user paused it. Their instruction outranks any condition the app noticed by itself. */
    Paused,

    /**
     * "Only on Wi-Fi" is on and the connection is metered.
     *
     * Not a failure and not a pause: the queue is intact and will move the moment the device is on an
     * unmetered network. Nothing is marked failed, because nothing went wrong.
     */
    WaitingForWifi,
    ;

    /** Whether the queue may work. */
    val isRunnable: Boolean get() = this == None
}
