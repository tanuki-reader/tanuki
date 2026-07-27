package app.tanuki.features.reader.composable.components

import androidx.compose.runtime.Composable
import app.tanuki.core.domain.chapter.model.ChapterNumber
import app.tanuki.features.reader.resources.Res
import app.tanuki.features.reader.resources.reader_chapter_number
import org.jetbrains.compose.resources.stringResource

/** The current chapter's display title: its source name, or the localized "Chapter N" fallback. */
@Composable
internal fun chapterTitle(
    number: ChapterNumber?,
    name: String?
): String = name ?: stringResource(Res.string.reader_chapter_number, number?.let(::formatChapterNumber) ?: "")

/** Formats a chapter number without a trailing `.0` — `4.0` → "4", `4.5` → "4.5". */
private fun formatChapterNumber(number: ChapterNumber): String {
    val value = number.value
    return if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
}