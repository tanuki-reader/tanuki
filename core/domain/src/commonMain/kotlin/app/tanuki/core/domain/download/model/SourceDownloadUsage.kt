package app.tanuki.core.domain.download.model

/**
 * What one source's downloads cost, for the offer made when it is uninstalled.
 *
 * Both halves are needed to make that offer answerable: a count says how much reading is at stake, a
 * size says how much room is at stake, and a user deciding whether to keep files for a source they are
 * removing is weighing exactly those two against each other.
 *
 * @property chapters How many of the source's chapters are fully downloaded. Zero means there is
 *   nothing to offer, and the offer is not shown at all.
 * @property bytes What they occupy on disk, measured from the files rather than summed from the
 *   records — the same authority the storage figure in settings uses, for the same reason.
 */
data class SourceDownloadUsage(
    val chapters: Int = 0,
    val bytes: Long = 0L,
) {
    /** Whether there is anything to offer to delete. */
    val isEmpty: Boolean get() = chapters == 0
}
