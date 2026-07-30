package app.tanuki.core.domain.update.model

import kotlin.uuid.Uuid

/**
 * One series' worth of news, as an update notification announces it: "this title gained this many
 * chapters".
 *
 * Deliberately carries no rendered copy. A notification's wording ("3 new chapters") is localized, and
 * domain code is Compose-free and cannot resolve a string resource — so this hands the platform
 * notifier the *facts* and lets it phrase them. [title] is the exception only because it is content
 * from a source, not copy, exactly like it is everywhere else.
 *
 * @property mangaId The series, so a notification tap can eventually deep-link to it.
 * @property title The series title, for display.
 * @property newChapterCount How many chapters arrived — always at least one, or there would be nothing
 *   to announce.
 */
data class MangaUpdateSummary(
    val mangaId: Uuid,
    val title: String,
    val newChapterCount: Int
)