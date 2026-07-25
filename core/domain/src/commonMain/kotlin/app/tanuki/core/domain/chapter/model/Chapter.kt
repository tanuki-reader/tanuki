package app.tanuki.core.domain.chapter.model

import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.common.model.Url
import app.tanuki.core.domain.common.service.uuidV7
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A single chapter of a manga, as fetched from a source and persisted locally.
 *
 * Carries two identities: the local [id] (our own key) and [sourceChapterKey] (the source's natural
 * key). On a refresh, remote chapters are matched back to their local rows by [sourceChapterKey], so
 * [id] — and the user's [progress] — survive across syncs. Source-owned fields ([name],
 * [chapterNumber], [dateUpload]) are refreshed on each sync; [progress] is local-only and never
 * overwritten.
 *
 * [url] is **not** the identity, for the same reason it isn't on
 * [app.tanuki.core.domain.manga.model.Manga]: sources aren't required to publish a per-chapter
 * permalink (MangaDex addresses chapters by id), so url-less chapters fall back to the series URL and
 * every chapter of a manga would collapse onto one value.
 *
 * @property id Local persistence identity, minted once and never derived from the source. Used for
 *   foreign keys (history, downloads) and navigation.
 * @property mangaId Local id of the owning manga.
 * @property chapterNumber Recognized chapter number such as `4` or `4.5`; `null` when it could not
 *   be parsed from [name].
 * @property name Chapter title as the source provides it, or `null` when it provides none — the norm
 *   on sources that only number their chapters. The UI renders the localized "Chapter N" from
 *   [chapterNumber] in that case; a display name is deliberately **not** invented here, since domain
 *   code is Compose-free and cannot resolve a localized string.
 * @property scanlator Translation group credited by the source, or `null` when it credits none. Not
 *   decoration: on an aggregator like MangaDex several groups publish the same chapter number as
 *   separate chapters, so without this the list shows what reads as duplicate rows. They are kept
 *   as distinct chapters (each has its own pages and quality, and the user's progress is bound to
 *   one of them) and told apart by this field, rather than being deduplicated on the way in.
 * @property sourceChapterKey The source's natural key for this chapter — stable within its source,
 *   unique within a manga, and what a refresh matches a remote chapter to its local row by. `null`
 *   only on rows written before the key was persisted.
 * @property url Permalink to the chapter on the source's site, for display and "open in browser".
 *   Falls back to the series URL when the source publishes no per-chapter link.
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
    val name: Text?,
    val scanlator: Text?,
    val sourceChapterKey: Text?,
    val url: Url,
    val dateUpload: Instant?,
    val dateFetch: Instant,
    val progress: ReadingProgress?
) {
    companion object {
        /**
         * Creates a brand-new, never-persisted chapter from source data, minting a fresh time-ordered
         * [id] via [uuidV7]. This is the canonical entry point for the insert path: a synced/fetched
         * chapter that has no local row yet is built here, then handed to
         * [app.tanuki.core.domain.chapter.repository.ChapterRepository.upsertChapters].
         *
         * Do **not** use this on a refresh of an existing chapter — there the local row is matched by
         * ([mangaId], [sourceChapterKey]) and its existing [id] is reused so reading [progress]
         * survives. New chapters carry no [progress] (the user has not interacted yet), so it is
         * omitted.
         */
        fun new(
            mangaId: Uuid,
            chapterNumber: ChapterNumber?,
            name: Text?,
            scanlator: Text?,
            sourceChapterKey: Text?,
            url: Url,
            dateUpload: Instant?,
            dateFetch: Instant
        ): Chapter =
            Chapter(
                id = uuidV7(),
                mangaId = mangaId,
                chapterNumber = chapterNumber,
                name = name,
                scanlator = scanlator,
                sourceChapterKey = sourceChapterKey,
                url = url,
                dateUpload = dateUpload,
                dateFetch = dateFetch,
                progress = null
            )
    }
}