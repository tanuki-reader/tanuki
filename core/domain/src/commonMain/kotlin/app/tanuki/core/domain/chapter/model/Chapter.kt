package app.tanuki.core.domain.chapter.model

import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.common.model.Url
import app.tanuki.core.domain.common.service.uuidV7
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A single chapter of a manga, as fetched from a source and persisted locally.
 *
 * Carries two identities: the local [id] (our own key) and [url] (the source's natural key). On a
 * library refresh, remote chapters are matched back to their local rows by [url], so [id] — and the
 * user's [progress] — survive across syncs. Source-owned fields ([name], [chapterNumber],
 * [dateUpload]) are refreshed on each sync; [progress] is local-only and never overwritten.
 *
 * @property id Local persistence identity, minted once and never derived from the source. Used for
 *   foreign keys (history, downloads) and navigation.
 * @property mangaId Local id of the owning manga.
 * @property chapterNumber Recognized chapter number such as `4` or `4.5`; `null` when it could not
 *   be parsed from [name].
 * @property name Human-readable chapter name provided by the source; a validated non-blank [Text].
 * @property url The source's natural key for this chapter — unique within a manga and the key used
 *   to match a remote chapter to its local row on refresh.
 * @property dateUpload When the source published the chapter; `null` when the source omits it.
 * @property dateFetch When this chapter was first fetched into the local library.
 * @property progress The user's reading state (bookmark, last page read, read flag); `null` until
 *   the user interacts with the chapter. Owned locally and never overwritten by source syncs.
 *
 * @see ChapterNumber
 * @see ReadingProgress
 */
data class Chapter(
    val id: Uuid,
    val mangaId: Uuid,
    val chapterNumber: ChapterNumber?,
    val name: Text,
    val url: Url,
    val dateUpload: Instant?,
    val dateFetch: Instant,
    val progress: ReadingProgress?
) {
    companion object {
        /**
         * Creates a brand-new, never-persisted chapter from source data, minting a fresh time-ordered
         * [id] via [uuidV7]. This is the canonical entry point for the insert path: a synced/fetched
         * chapter that has no local row yet is built here, then handed to `ChapterRepository.addChapter`.
         *
         * Do **not** use this on a library refresh of an existing chapter — there the local row is
         * matched by ([mangaId], [url]) and its existing [id] is reused so reading [progress] survives.
         * New chapters carry no [progress] (the user has not interacted yet), so it is omitted.
         */
        fun new(
            mangaId: Uuid,
            chapterNumber: ChapterNumber?,
            name: Text,
            url: Url,
            dateUpload: Instant?,
            dateFetch: Instant
        ): Chapter =
            Chapter(
                id = uuidV7(),
                mangaId = mangaId,
                chapterNumber = chapterNumber,
                name = name,
                url = url,
                dateUpload = dateUpload,
                dateFetch = dateFetch,
                progress = null
            )
    }
}