package app.tanuki.core.domain.settings.model

/**
 * What happens to a downloaded chapter once it has been read.
 *
 * The point of the middle options is that "read" is not the same as "finished with". Readers flick back
 * a chapter or two constantly — to re-check a panel, or because they lost the thread — and a setting
 * that deletes the moment the last page is turned makes that a re-download over mobile data. So the
 * useful settings are not "on" and "off" but *how much slack to keep*.
 *
 * Stored by [name]; the order of these entries is not a compatibility promise, the names are.
 *
 * @property keepReadChapters How many of the most recent **read** chapters of a series to keep on the
 *   device, or `null` when nothing is ever deleted automatically. `0` deletes a chapter as soon as it
 *   is read.
 */
enum class DeleteAfterReading(
    val keepReadChapters: Int?,
) {
    /** Downloads stay until the user removes them. The default — deleting a user's files is never it. */
    Off(null),

    /** Delete a chapter the moment it is read. */
    Immediately(0),

    /** Keep the last read chapter, so flicking back one still works offline. */
    KeepLastOne(1),

    /** Keep the last five read chapters — roughly a sitting's worth of going back. */
    KeepLastFive(5),
    ;

    /** Whether this setting deletes anything at all. */
    val isEnabled: Boolean get() = keepReadChapters != null
}
