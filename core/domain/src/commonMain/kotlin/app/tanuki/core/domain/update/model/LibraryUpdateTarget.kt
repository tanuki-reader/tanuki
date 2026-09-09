package app.tanuki.core.domain.update.model

import app.tanuki.core.domain.common.model.Text
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * One library series as a library update run sees it: whether it is due, which source to queue it
 * behind, and what to name it if the refresh fails.
 *
 * That really is the whole input to
 * [app.tanuki.core.domain.update.usecase.UpdateLibrary] — it hands [id] to the chapter sync and never
 * looks at the series again, since the sync re-reads and re-stamps the row itself. Reading it as four
 * scalars rather than as a full manga is what lets the run's query skip the `history` join and both
 * correlated sub-queries that the library grid's projection needs: a run over 500 titles costs one flat
 * scan of `library ⋈ manga` instead of 500 unread-and-download counts nobody reads.
 *
 * @property source the manga's local source id — what the run groups by, to bound how many sources are
 *   polled at once.
 * @property title for logging a failure; a run never displays it.
 * @property nextUpdateAt when this series should next be checked, or `null` if it has never been
 *   scheduled — which reads as "check it now".
 */
data class LibraryUpdateTarget(
    val id: Uuid,
    val source: Uuid,
    val title: Text,
    val nextUpdateAt: Instant?
)
