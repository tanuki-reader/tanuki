package app.tanuki.features.reader.composable.components

import androidx.compose.runtime.Composable
import app.tanuki.features.reader.resources.Res
import app.tanuki.features.reader.resources.reader_chapter_number
import org.jetbrains.compose.resources.stringResource

/** The current chapter's display title: its source name, or the localized "Chapter N" fallback. */
@Composable
internal fun chapterTitle(
    numberLabel: String?,
    name: String?
): String = name ?: stringResource(Res.string.reader_chapter_number, numberLabel ?: "")