package app.tanuki.core.domain.download.usecase

import app.tanuki.core.domain.chapter.usecase.GetChapterPages
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.domain.common.service.info
import app.tanuki.core.domain.common.service.warn
import app.tanuki.core.domain.download.model.ChapterDownload
import app.tanuki.core.domain.download.model.DownloadError
import app.tanuki.core.domain.download.model.DownloadState
import app.tanuki.core.domain.download.model.DownloadHold
import app.tanuki.core.domain.download.model.DownloadedPage
import app.tanuki.core.domain.download.repository.DownloadRepository
import app.tanuki.core.domain.download.service.ChapterDownloadPart
import app.tanuki.core.domain.download.service.ChapterDownloadStore
import app.tanuki.core.domain.download.service.FetchedPage
import app.tanuki.core.domain.download.service.PageFetchException
import app.tanuki.core.domain.download.service.PageFetcher
import app.tanuki.core.domain.download.usecase.DownloadRunResult.Completed
import app.tanuki.core.domain.download.usecase.DownloadRunResult.Skipped
import app.tanuki.core.domain.source.model.Page
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * The download engine: works the queue until it is empty, one chapter at a time.
 *
 * It lives in `:core:domain` despite being an *engine* because it is pure orchestration over ports —
 * [PageFetcher] for the bytes, [ChapterDownloadStore] for the disk, [DownloadRepository] for the queue —
 * exactly as [app.tanuki.core.domain.update.usecase.UpdateLibrary] orchestrates a sync. No HTTP and no
 * filesystem type appears here.
 *
 * ### One run at a time
 *
 * A second [run] while one is in flight returns [Skipped] rather than queueing, so every enqueue can
 * call [app.tanuki.core.domain.download.service.DownloadScheduler.ensureRunning] unconditionally. The
 * run in flight will reach whatever was just enqueued — it re-reads the queue after every chapter
 * rather than working from a snapshot.
 *
 * ### Strictly in order, one chapter at a time
 *
 * Unlike a library update, which spreads across sources for throughput, a queue is something the user
 * *watches*: they expect the first chapter to finish first. Working three at once would also triple the
 * page bytes held in memory and make "downloading 2 of 12" a lie. Parallelism goes **inside** a chapter
 * instead — a few pages at a time, enough to hide latency without bursting a host into rate-limiting
 * the very source the queue depends on.
 *
 * ### The page list is fetched per chapter, at download time
 *
 * Never reused from the reader or resolved up front for the whole queue: several sources hand out
 * short-lived image URLs (MangaDex's `@Home` links expire in minutes), so a fifty-chapter queue that
 * resolved everything before starting would 403 halfway through.
 *
 * ### A failure is one chapter
 *
 * Each page gets [MAX_PAGE_ATTEMPTS] tries with exponential backoff; a page that still fails fails its
 * chapter, which is marked [DownloadState.Failed] with a reason. Its `.part` directory **stays**, so a
 * retry re-fetches only what is missing, and the queue moves on to the next chapter.
 * [CancellationException] is always rethrown — that is the app being torn down, not a chapter going
 * wrong.
 *
 * ### A hold stops the run, not the chapter
 *
 * [ObserveDownloadHold] is re-read before every chapter and before every page, and answers one question
 * for both reasons a queue may legitimately stop: the user paused it, or "only on Wi-Fi" is on and the
 * connection is metered. When it says stop, the engine stops taking pages, puts the chapter it was
 * working on back to [DownloadState.Queued] and ends the run — nothing is failed and nothing is deleted,
 * so resuming picks the same chapter back up from its `.part` directory.
 *
 * Checking *inside* a chapter as well as between chapters is what makes it feel immediate: a forty-page
 * chapter on mobile data is a long time to keep downloading after being told to stop, and — for the
 * Wi-Fi case — a long time to keep spending data the user asked the app not to.
 *
 * Progress is written to the database per page, so the UI, a process death and a resume all agree
 * without any in-memory state being authoritative.
 */
sealed interface DownloadManager {
    /**
     * Works the queue until nothing is left to do.
     *
     * @return What the run did, or [Skipped] when another run already had the queue.
     */
    suspend fun run(): DownloadRunResult

    /**
     * Abandons in-flight work for [chapterIds] — what cancelling or deleting a download calls before it
     * removes the rows and the files underneath the engine.
     *
     * Not a coroutine cancellation: a page already in flight is allowed to arrive and is then dropped,
     * so abandonment costs at most one page's latency and never leaves a half-written file. A chapter
     * that has not started yet is skipped when its turn comes.
     */
    fun abort(chapterIds: Collection<Uuid>)

    @Inject
    @SingleIn(AppScope::class)
    @ContributesBinding(AppScope::class)
    class Default(
        private val downloadRepository: DownloadRepository,
        private val store: ChapterDownloadStore,
        private val pageFetcher: PageFetcher,
        private val getChapterPages: GetChapterPages,
        private val observeDownloadHold: ObserveDownloadHold,
        private val dispatchers: DispatcherProvider,
        private val logger: Logger,
    ) : DownloadManager {
        /** Held for the length of a run. Never *waited* on — a second caller is told the queue is busy. */
        private val running = Mutex()

        /**
         * Chapters whose work has been abandoned. A [MutableStateFlow] rather than a plain set because
         * [abort] is called from wherever the user tapped, off the run's thread, and its updates have to
         * be visible to the run without a lock either side could hold.
         */
        private val aborted = MutableStateFlow<Set<Uuid>>(emptySet())

        override fun abort(chapterIds: Collection<Uuid>) {
            if (chapterIds.isEmpty()) return
            aborted.update { it + chapterIds }
        }

        override suspend fun run(): DownloadRunResult {
            if (!running.tryLock()) return Skipped
            return try {
                withContext(dispatchers.io) { drain() }
            } finally {
                running.unlock()
            }
        }

        private suspend fun drain(): DownloadRunResult {
            // This run owns the queue, so nothing may legitimately still be `Downloading`: any row that
            // is was left there by a process that died mid-chapter.
            downloadRepository
                .requeueInterrupted()
                .takeIf { it > 0 }
                ?.let { logger.info(TAG, "Requeued $it download(s) an earlier run left in flight") }

            var downloaded = 0
            var failed = 0
            val handled = mutableSetOf<Uuid>()

            while (true) {
                val hold = hold()
                if (!hold.isRunnable) {
                    logger.info(TAG, "The queue is held ($hold); ending the run")
                    break
                }
                val next = downloadRepository.nextQueued() ?: break
                // A chapter that is still `Queued` after its turn would be picked again forever. That
                // shouldn't happen — every path below leaves a terminal state or deletes the row — so it
                // is a bug rather than a case, and spinning on it is much worse than stopping.
                if (!handled.add(next.chapterId)) {
                    logger.warn(TAG, "Chapter ${next.chapterId} is still queued after its turn; ending the run")
                    break
                }
                when (download(next)) {
                    Outcome.Downloaded -> downloaded++
                    Outcome.Failed -> failed++
                    Outcome.Abandoned -> Unit
                    // The chapter is back in the queue exactly where it was; nothing is left to do until
                    // the user resumes, and `handled` would refuse to pick it again anyway.
                    Outcome.Held -> break
                }
            }

            return Completed(downloaded = downloaded, failed = failed)
                .also { logger.info(TAG, "Download run finished: $it") }
        }

        /** Downloads one chapter end to end. Never throws except to propagate cancellation. */
        private suspend fun download(record: ChapterDownload): Outcome {
            val chapterId = record.chapterId
            if (chapterId in aborted.value) return abandon(chapterId)

            downloadRepository.update(record.copy(state = DownloadState.Downloading, error = null))

            // Resolved now rather than when the chapter was queued: image URLs expire (see the KDoc).
            // Every failure here is the source's — missing, uninstalled, or unable to list the pages.
            val pages =
                try {
                    getChapterPages(chapterId)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    return fail(record, DownloadError.SourceUnavailable, "Could not list pages", error)
                }

            if (pages.isEmpty()) {
                return fail(record, DownloadError.SourceUnavailable, "The source serves no pages")
            }
            downloadRepository.update(
                record.copy(state = DownloadState.Downloading, pageCount = pages.size, error = null),
            )

            return try {
                fetchPages(record, pages)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: PageFetchException) {
                fail(record, error.error, "A page could not be fetched", error)
            } catch (error: Throwable) {
                fail(record, DownloadError.Unknown, "The download failed", error)
            } finally {
                // Whatever happened, this chapter is no longer in flight; a later enqueue of it is a new
                // request and must not inherit an abort aimed at the attempt that just ended.
                aborted.update { it - chapterId }
            }
        }

        /** Fetches everything missing from the chapter's part directory, then commits it. */
        private suspend fun fetchPages(
            record: ChapterDownload,
            pages: List<Page>,
        ): Outcome {
            val chapterId = record.chapterId
            val part = openUsablePart(record, pageCount = pages.size)

            val held = part.existingPages()
            val done = held.associateBy(DownloadedPage::index)
            val progress = ChapterProgress(held)
            downloadRepository.setProgress(chapterId, progress.pages(), progress.bytes())

            coroutineScope {
                val permits = Semaphore(MAX_CONCURRENT_PAGES)
                pages
                    .withIndex()
                    .filter { (index, _) -> index !in done }
                    .map { (index, page) ->
                        async { permits.withPermit { fetchInto(part, chapterId, index, page, progress) } }
                    }.awaitAll()
            }

            if (chapterId in aborted.value) return abandon(chapterId, part)
            // Checked before the commit, not instead of it: a chapter that finished every page while the
            // user was reaching for pause is committed rather than thrown back in the queue, for the same
            // reason cancelling a chapter that just landed keeps its files.
            if (progress.pages() < pages.size && !hold().isRunnable) return holdChapter(record, pages.size, progress)

            val manifest = store.commit(part, expectedPageCount = pages.size)
            downloadRepository.markDownloaded(
                chapterId = chapterId,
                pageCount = manifest.pageCount,
                bytes = manifest.totalBytes,
                completedAt = manifest.downloadedAt,
            )
            return Outcome.Downloaded
        }

        /**
         * Opens the chapter's part directory, discarding one an earlier attempt left that no longer
         * lines up with the source's current page list.
         *
         * Pages are addressed by their **position in the source's list**, not by [Page.index]: the list
         * is already in reading order, and a manifest requires contiguous indices from `0`, so a source
         * that numbers from `1` or leaves a gap must not produce a chapter that can never commit. The
         * cost of that choice is here — a part written when the chapter had more pages is no longer
         * positionally comparable, and keeping it would interleave two different versions of the chapter.
         */
        private suspend fun openUsablePart(
            record: ChapterDownload,
            pageCount: Int,
        ): ChapterDownloadPart {
            val part = store.openPart(record.mangaId, record.chapterId)
            if (part.existingPages().none { it.index >= pageCount }) return part

            logger.warn(TAG, "Discarding a stale part for ${record.chapterId}: the page list changed")
            part.discard()
            // Reopened rather than reused: a discarded part is gone from disk, and the handle to it with
            // it — writing through the old one would put pages somewhere nothing ever commits from.
            return store.openPart(record.mangaId, record.chapterId)
        }

        /** Fetches one page (with retries) and writes it, recording progress as it lands. */
        private suspend fun fetchInto(
            part: ChapterDownloadPart,
            chapterId: Uuid,
            index: Int,
            page: Page,
            progress: ChapterProgress,
        ) {
            if (chapterId in aborted.value || !hold().isRunnable) return
            val fetched = fetch(page)
            // Checked again on the way out: the fetch is the slow part, so this is where an abort most
            // often lands. Dropping the bytes is cheaper than writing a file the next line deletes.
            if (chapterId in aborted.value) return

            val written = part.writePage(index, fetched.extension, fetched.bytes)
            val (pages, bytes) = progress.record(written)
            downloadRepository.setProgress(chapterId, pages, bytes)
        }

        /**
         * Fetches one page, retrying transient failures with exponential backoff.
         *
         * [DownloadError.NeedsVerification] is exempt: a Cloudflare challenge does not clear by asking
         * again, so retrying it only spends three attempts' worth of time before failing anyway.
         */
        private suspend fun fetch(page: Page): FetchedPage {
            var attempt = 1
            while (true) {
                try {
                    return pageFetcher.fetch(page)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: PageFetchException) {
                    if (attempt == MAX_PAGE_ATTEMPTS || error.error == DownloadError.NeedsVerification) {
                        throw error
                    }
                    logger.warn(TAG, "Page ${page.index} failed (attempt $attempt); retrying", error)
                    delay(RETRY_BACKOFF * (1 shl (attempt - 1)))
                    attempt++
                }
            }
        }

        /**
         * Gives up on a chapter, recording why. Whatever it managed to fetch stays in its part directory
         * so a retry resumes rather than starting over.
         */
        private suspend fun fail(
            record: ChapterDownload,
            error: DownloadError,
            message: String,
            cause: Throwable? = null,
        ): Outcome {
            logger.warn(TAG, "$message for chapter ${record.chapterId} ($error)", cause)
            downloadRepository.markFailed(record.chapterId, error)
            return Outcome.Failed
        }

        /**
         * Drops a chapter the user cancelled. Nothing is written to its row — by the time this runs the
         * row is normally already gone, and re-marking a chapter that no longer exists would either do
         * nothing or resurrect it.
         */
        private suspend fun abandon(
            chapterId: Uuid,
            part: ChapterDownloadPart? = null,
        ): Outcome {
            logger.info(TAG, "Abandoning chapter $chapterId")
            part?.discard()
            return Outcome.Abandoned
        }

        /**
         * Whether anything is holding the queue, and what.
         *
         * Read at the point of use rather than mirrored into a field a collector keeps current. A mirror
         * looks cheaper and is worse: whether it is up to date depends on a collector coroutine being
         * scheduled between two pages, so how promptly the queue stops would come down to a race. This
         * is a cached preference read and an in-memory boolean against an HTTP image download — the cost
         * is not measurable, and the answer is never stale.
         */
        private suspend fun hold(): DownloadHold = observeDownloadHold().first()

        /**
         * Puts a chapter the queue stopped on back where it was.
         *
         * Written back to [DownloadState.Queued] rather than left [DownloadState.Downloading]: that state
         * means "an engine is fetching this", and a row left in it would be requeued at the next startup
         * as debris — reported in the log as a crash that never happened, and, worse, shown in the queue
         * screen as active while nothing is running. Its part directory stays, so resuming re-fetches
         * only the pages that are missing.
         */
        private suspend fun holdChapter(
            record: ChapterDownload,
            pageCount: Int,
            progress: ChapterProgress,
        ): Outcome {
            logger.info(TAG, "Holding on chapter ${record.chapterId}")
            // The counters come from the run, not from [record], which is the snapshot this turn started
            // with: `update` writes the whole row, so reusing the snapshot's totals would roll the row
            // back to where the chapter was before any of this turn's pages landed.
            downloadRepository.update(
                record.copy(
                    state = DownloadState.Queued,
                    error = null,
                    pageCount = pageCount,
                    downloadedPages = progress.pages(),
                    bytes = progress.bytes(),
                ),
            )
            return Outcome.Held
        }

        /** What became of one chapter's turn. */
        private enum class Outcome { Downloaded, Failed, Abandoned, Held }

        /**
         * A chapter's running page/byte totals, seeded from whatever an interrupted attempt left behind.
         *
         * Guarded because pages are fetched in parallel: without it two pages landing together would
         * read the same count and one of them would never be reported, leaving a chapter stuck at
         * "11 of 12" until it completed.
         */
        private class ChapterProgress(
            existing: List<DownloadedPage>,
        ) {
            private val guard = Mutex()
            private var pages = existing.size
            private var bytes = existing.sumOf(DownloadedPage::bytes)

            suspend fun pages(): Int = guard.withLock { pages }

            suspend fun bytes(): Long = guard.withLock { bytes }

            /** Counts a page that just landed. @return the totals to report, as one consistent pair. */
            suspend fun record(page: DownloadedPage): Pair<Int, Long> =
                guard.withLock {
                    pages++
                    bytes += page.bytes
                    pages to bytes
                }
        }

        private companion object {
            const val TAG = "DownloadManager"

            /**
             * How many pages of one chapter are fetched at once. Small deliberately: the point is to
             * hide per-request latency, not to saturate the link. A larger window reads like a scraper
             * to the very host the rest of the queue still has to ask.
             */
            const val MAX_CONCURRENT_PAGES = 3

            /** Tries per page before its chapter fails. Covers a blip; not a flaky host. */
            const val MAX_PAGE_ATTEMPTS = 3

            /** First retry wait, doubling per attempt. */
            val RETRY_BACKOFF: Duration = 1.seconds
        }
    }
}

/** The outcome of a [DownloadManager] run. */
sealed interface DownloadRunResult {
    /** Another run already had the queue; there was nothing for this caller to do. */
    data object Skipped : DownloadRunResult

    /**
     * The queue was worked until empty.
     *
     * @property downloaded How many chapters were committed to disk.
     * @property failed How many stopped with an error. Cancelled chapters count as neither — the user
     *   asked for them to not happen, which is not a failure.
     */
    data class Completed(
        val downloaded: Int,
        val failed: Int,
    ) : DownloadRunResult
}