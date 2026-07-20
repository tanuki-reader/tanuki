package app.tanuki.core.domain.chapter.service

import app.tanuki.core.domain.chapter.model.Chapter
import app.tanuki.core.domain.chapter.model.ChapterNumber

/**
 * Counts missing chapter numbers in a list of known chapter numbers.
 *
 * "Missing" means there is a gap in the integer sequence (e.g. chapters 1, 3 → 1 missing).
 * Subchapter precision (16.5) is intentionally collapsed to integers for this check, since we
 * cannot know whether a `16.5` exists between `16` and `17`.
 */
fun List<ChapterNumber>.missingChaptersCount(): UInt {
    if (isEmpty()) return 0u

    // IntArray + in-place sort avoids boxing the whole-chapter indices into a List<Int>.
    val sorted = IntArray(size) { this[it].wholeNumber }
    sorted.sort()

    var missing = 0u
    var previous = sorted[0]

    for (i in 1 until sorted.size) {
        val current = sorted[i]
        if (current > previous + 1) {
            missing += (current - previous - 1).toUInt()
        }
        previous = current
    }
    return missing
}

/**
 * Counts missing chapters between [higherChapter] and [lowerChapter].
 * Returns 0 if either argument is null or has no recognized number.
 */
fun calculateChapterGap(
    higherChapter: Chapter?,
    lowerChapter: Chapter?
): UInt {
    if (higherChapter == null || lowerChapter == null) return 0u
    val high = higherChapter.chapterNumber ?: return 0u
    val low = lowerChapter.chapterNumber ?: return 0u
    return calculateChapterGap(high, low)
}

/**
 * Counts missing chapters between two recognized chapter numbers, collapsing subchapters to
 * integers. Returns 0 when [higher] is not actually above [lower].
 */
fun calculateChapterGap(
    higher: ChapterNumber,
    lower: ChapterNumber
): UInt {
    val gap = higher.wholeNumber - lower.wholeNumber - 1
    return gap.coerceAtLeast(0).toUInt()
}