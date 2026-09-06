package app.tanuki.core.domain.download.service

import app.tanuki.core.domain.download.model.DownloadManifest
import app.tanuki.core.domain.download.model.DownloadedPage
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

/**
 * On-device storage for downloaded chapter pages — the bytes the reader shows when there is no network.
 *
 * The port the domain owns and the `data` layer implements over a real filesystem, mirroring
 * [app.tanuki.core.domain.source.SourceBundleStore]. Nothing platform- or filesystem-shaped crosses it:
 * callers pass ids and bytes and get back manifests and opaque path strings.
 *
 * ### Layout
 * ```
 * <downloads root>/
 *   <mangaId>/
 *     <chapterId>/           ← committed; contains manifest.json + the pages
 *     <chapterId>.part/      ← in progress; resumable, and never read by the reader
 * ```
 * Directories are named by **local [Uuid]s, not titles**: titles need sanitizing (`/`, `:`, emoji, RTL),
 * collide across sources, and change whenever a source edits its metadata — each of which silently
 * orphans a folder. The cost is a tree that isn't human-browsable; making it browsable is an *export*
 * feature (a CBZ writer over this layout), not a reason to make the live layout fragile.
 *
 * ### Completion
 * A chapter is downloaded **iff its committed directory holds a `manifest.json`**. Pages accumulate in
 * `<chapterId>.part`, and [commit] writes the manifest and atomically moves the whole directory into
 * place — so an interrupted download leaves a resumable part, never a half-chapter that reads as
 * complete. Nothing here consults the database; the row is an index over this, and [storedChapters] is
 * what lets a reconciliation pass compare the two.
 *
 * Implementations do their own IO-dispatcher hopping; every member is safe to call from any context.
 */
interface ChapterDownloadStore {
    /**
     * The committed manifest for a chapter, or `null` if it isn't fully downloaded — including when a
     * `.part` directory exists, which is *not* a download.
     */
    suspend fun manifest(
        mangaId: Uuid,
        chapterId: Uuid
    ): DownloadManifest?

    /**
     * Absolute paths of a downloaded chapter's pages, in reading order, or `null` if it isn't fully
     * downloaded.
     *
     * The offline read path's whole surface: it hands these straight to the image loader, so no caller
     * outside this interface has to know how a chapter is laid out or how paths are joined.
     */
    suspend fun pagePaths(
        mangaId: Uuid,
        chapterId: Uuid
    ): List<String>?

    /**
     * Opens (or reopens) the in-progress directory for a chapter, creating it if needed.
     *
     * Reopening is the resume path: pages already written by an interrupted attempt are still there and
     * are reported by [ChapterDownloadPart.existingPages], so a retry re-fetches only what is missing.
     */
    suspend fun openPart(
        mangaId: Uuid,
        chapterId: Uuid
    ): ChapterDownloadPart

    /**
     * Finishes a chapter: writes its `manifest.json`, then atomically moves the part directory into
     * place, replacing any previous copy.
     *
     * @param part The part to commit; unusable afterwards.
     * @param expectedPageCount How many pages the source said the chapter has. Checked, not trusted —
     *   committing a short chapter would mark it readable offline and then fail in the reader, which is
     *   the one failure this design exists to prevent.
     * @throws IllegalStateException if the part does not hold exactly [expectedPageCount] pages at
     *   contiguous indices from `0`.
     * @return The manifest as committed.
     */
    suspend fun commit(
        part: ChapterDownloadPart,
        expectedPageCount: Int
    ): DownloadManifest

    /** Deletes a chapter's pages — both its committed directory and any part. A no-op if absent. */
    suspend fun delete(
        mangaId: Uuid,
        chapterId: Uuid
    )

    /**
     * Deletes every downloaded chapter of a series. What removing a series from the library has to call:
     * the database's foreign keys cascade the *rows* away and never touch a byte on disk.
     */
    suspend fun deleteManga(mangaId: Uuid)

    /** Empties the downloads root. Behind a confirmation in settings. */
    suspend fun deleteAll()

    /** Total bytes held by all downloads, for the storage figure in settings. */
    /**
     * Emits whenever the bytes on disk have changed — a chapter committed, or anything deleted.
     *
     * The store is the **authority** on what downloads cost ([usedBytes] measures the files rather than
     * trusting the records), so it has to be the thing that says when that answer has moved. Watching the
     * records instead is subtly wrong and was: deleting downloads removes the rows first and the files
     * second, so a reader triggered by the rows re-measures while the files are still there and then
     * never hears about the delete that followed.
     *
     * It also catches the changes no record can describe — an orphaned directory swept up, or a `.part`
     * discarded — which is what the phase-8 reconciliation pass will do.
     *
     * Deliberately **not** emitted per written page. A download in progress grows the on-disk total
     * without saying so, and the commit at the end of the chapter corrects it; emitting per page would
     * send every observer walking the whole downloads tree between two images.
     */
    fun observeChanges(): Flow<Unit>

    suspend fun usedBytes(): Long

    /** Bytes held by one series' downloads, for the per-series breakdown. */
    suspend fun usedBytes(mangaId: Uuid): Long

    /**
     * Every chapter directory present on disk, complete or not.
     *
     * The input to reconciliation. Deliberately reports what *is there* rather than taking a set of
     * known ids and answering "which of these are orphans": deciding what counts as an orphan is a
     * domain rule (a pruned chapter, a series the user removed, a part left by a cancelled download),
     * and the store has no business knowing it.
     */
    suspend fun storedChapters(): List<StoredChapter>
}

/**
 * A chapter's in-progress directory: somewhere to put pages that is not yet a download.
 *
 * Obtained from [ChapterDownloadStore.openPart] and finished by [ChapterDownloadStore.commit] (or
 * thrown away by [discard]). Until it is committed the reader cannot see any of it — which is the point,
 * and why a cancelled or crashed download costs nothing but disk that the next sweep reclaims.
 */
interface ChapterDownloadPart {
    /** The series this part belongs to. */
    val mangaId: Uuid

    /** The chapter being downloaded. */
    val chapterId: Uuid

    /**
     * Pages an earlier attempt already wrote, in index order. Empty for a fresh download.
     *
     * A resume skips exactly these and re-fetches the rest, which matters most for the case that
     * produced the part in the first place: a long chapter on a flaky connection.
     */
    suspend fun existingPages(): List<DownloadedPage>

    /**
     * Writes one page, replacing any earlier copy at that index.
     *
     * @param index Zero-based position in the chapter.
     * @param extension File extension for the bytes (`jpg`, `png`, `webp` — no leading dot), derived by
     *   the caller from the response's content type. Sanitized by the implementation; an unusable one
     *   falls back to a generic extension rather than failing the page.
     * @param bytes The image.
     * @return The page as recorded on disk.
     */
    suspend fun writePage(
        index: Int,
        extension: String,
        bytes: ByteArray
    ): DownloadedPage

    /** Deletes the part directory. What cancelling a download does; a no-op if it is already gone. */
    suspend fun discard()
}

/**
 * A chapter directory found on disk.
 *
 * @property mangaId Series the directory sits under.
 * @property chapterId Chapter it holds.
 * @property isComplete Whether it is a committed download (`true`) or a leftover part (`false`).
 */
data class StoredChapter(
    val mangaId: Uuid,
    val chapterId: Uuid,
    val isComplete: Boolean,
)
