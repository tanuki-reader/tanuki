package app.tanuki.core.domain.source.model

import app.tanuki.core.domain.chapter.model.ChapterNumber
import kotlin.time.Instant

/**
 * A single chapter as a source lists it — the **pre-persistence** shape of a chapter.
 *
 * This is deliberately NOT [app.tanuki.core.domain.chapter.model.Chapter]: a listed chapter has no
 * local `Uuid`, no owning manga id, and no [app.tanuki.core.domain.chapter.model.ReadingProgress].
 * Those exist only once the chapter is persisted, at which point a sync use case matches remote
 * chapters to local rows by [chapterKey] so existing ids — and the user's reading progress — survive
 * the refresh. Mirrors [MangaListing]'s role for browse results.
 *
 * @property chapterKey Source-native chapter id; stable address of this chapter within its source and
 *   the key a local row is matched by on refresh. Passed to
 *   [app.tanuki.core.domain.source.SourceLoader.fetchChapterPages].
 * @property name Chapter title as the source provides it, or `null` when it provides none. Many
 *   sources give only a number, so this is optional — the persist step derives a display name from
 *   [number] when it is absent, rather than the source layer inventing one.
 * @property number Recognized chapter number (e.g. `4`, `4.5`), or `null` when the source reports
 *   none and it could not be parsed.
 * @property dateUpload When the source published the chapter, or `null` when it reports no date.
 * @property scanlator Translation group credited by the source, or `null` when none is given.
 */
data class ChapterListing(
    val chapterKey: String,
    val name: String? = null,
    val number: ChapterNumber? = null,
    val dateUpload: Instant? = null,
    val scanlator: String? = null
)
