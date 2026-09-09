package app.tanuki.core.domain.chapter.usecase

import app.tanuki.core.domain.chapter.model.PageImage
import app.tanuki.core.domain.chapter.model.ReaderPage
import app.tanuki.core.domain.download.usecase.GetLocalChapterPages
import app.tanuki.core.domain.source.model.Page
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlin.uuid.Uuid

/**
 * Use case: the pages to show for a chapter — from the device when it has been downloaded, from its
 * source otherwise. **This is the only place that decides**, so nothing downstream of it has any notion
 * of a chapter being local.
 *
 * ### Why this is a layer above [GetChapterPages] rather than a branch inside it
 *
 * The obvious shape — teach `GetChapterPages` to prefer the local copy — is wrong, because the
 * downloader is one of its callers and must *always* reach the source: a download-aware
 * `GetChapterPages` would have the engine fetch a chapter from the copy it is in the middle of writing.
 * The two callers genuinely want different things, so they ask different questions:
 * [GetChapterPages] is "what does the source serve", this is "what should the reader show".
 *
 * ### The disk is asked first, and its answer is final
 *
 * "Downloaded" here means a committed manifest, not a `chapter_downloads` row — the row can outlive the
 * files (the user clears app storage, a restore drops them) and the manifest cannot. A chapter whose
 * files are gone therefore reads as *not downloaded* and goes to the source like any other, which is the
 * right recovery and costs nothing.
 *
 * Once the manifest answers, though, that is the answer: every page is on disk, so there is nothing a
 * source round-trip could add, and the one moment offline reading has to work is the moment asking the
 * source cannot. Retrying remotely on a suspicion would turn a working offline chapter into a spinner.
 *
 * A downloaded chapter therefore never touches the image loader's disk cache either, which is what
 * keeps a download from costing its bytes twice.
 *
 * Failure is surfaced by throwing, exactly as [GetChapterPages] does: an empty list is a valid answer
 * (a chapter a source genuinely serves no pages for) and must stay distinguishable from "we could not
 * ask".
 */
interface GetReaderPages {
    /**
     * @param chapterId Local id of the chapter to read.
     * @throws IllegalStateException when a *remote* chapter cannot be resolved to its source (see
     *   [GetChapterPages]). A downloaded chapter never reaches that path.
     */
    suspend operator fun invoke(chapterId: Uuid): List<ReaderPage>

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val getLocalChapterPages: GetLocalChapterPages,
        private val getChapterPages: GetChapterPages,
    ) : GetReaderPages {
        override suspend fun invoke(chapterId: Uuid): List<ReaderPage> {
            getLocalChapterPages(chapterId)?.let { paths ->
                return paths.map { path -> ReaderPage(PageImage.Local(path)) }
            }
            return getChapterPages(chapterId).mapNotNull(Page::toReaderPage)
        }
    }
}

/**
 * Projects a source page onto the reader's view of it, or `null` when the source published neither an
 * image URL nor a page URL — a page with nothing to fetch is not a page, and dropping it here is what
 * keeps every layer below this from carrying a "might have no URL" case.
 *
 * The `imageUrl ?: url` order is the source contract: some sources hand back a direct image, others only
 * a page to resolve. It is the same order the reader used before this type existed, so nothing about
 * which URL a source is fetched from changes here.
 */
private fun Page.toReaderPage(): ReaderPage? =
    (imageUrl ?: url)?.let { url -> ReaderPage(PageImage.Remote(url, headers)) }
