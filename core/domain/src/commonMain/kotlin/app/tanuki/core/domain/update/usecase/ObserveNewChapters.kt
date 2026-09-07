package app.tanuki.core.domain.update.usecase

import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.update.model.ChapterUpdate
import app.tanuki.core.domain.update.model.NewChapter
import app.tanuki.core.domain.update.repository.ChapterUpdateRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn

/**
 * Use case: the chapters that arrived in the user's library and are still waiting to be opened, newest
 * first — what Home's "New Chapters" list renders.
 *
 * A thin reactive wrapper over [ResolveChapterUpdates], which owns every rule about what still counts
 * as pending. Re-emits whenever the records change **or** the underlying manga/chapters do, so reading
 * a chapter (or hiding a scanlation group) drops it from the list without anything having to invalidate
 * it explicitly.
 *
 * ### How the second half of that is achieved
 *
 * The records are one subscription and the resolution is two more. `chapter_updates` says only *that* a
 * chapter was discovered; every rule that decides whether it is still pending lives in `manga`,
 * `library`, `chapters` or `reading_progress`, none of which a write to `chapter_updates` accompanies.
 * So [ResolveChapterUpdates.observe] holds live reads of the series and chapters a record set names, and
 * `flatMapLatest` rebuilds them when that set changes — the id sets are bound into those queries, so a
 * changed record list means the old subscriptions are for the wrong ids and must be torn down.
 *
 * **The gate is [distinctUntilChangedBy], not `distinctUntilChanged`, and that is load-bearing.**
 * [ChapterUpdate] carries `notified`, which [NotifyNewChapters] flips on every library update run — a
 * genuine change to the record list that the resolver cannot see, since it never reads that field. A
 * plain dedupe would let each of those writes tear down and rebuild both queries for a byte-identical
 * answer, at exactly the moment Home is most likely on screen. Keying on the chapter ids is exact rather
 * than approximate: a `chapter_updates` row is immutable apart from `notified` (`insertIgnoring` never
 * rewrites one), and the query orders by `discovered_at DESC`, so the id *sequence* also carries any
 * reordering.
 *
 * With that gate, resubscription happens exactly when the set of records changes — which is when a sync
 * discovers or prunes chapters, and is strictly rarer than the unconditional re-resolve this use case
 * used to do on the same signal. So there is deliberately **no** [ObserveDownloadQueue]-style memoized
 * `resolve`: that one memoizes because its upstream re-emits once per downloaded page, and this one has
 * no such upstream. Don't add one.
 *
 * **Cost:** Home holds those two queries open, one of them the six-table `MangaWithDetails` projection.
 * `HomeViewModel` is contributed to `AppScope` and ends its pipeline in `launchIn(viewModelScope)`
 * rather than `WhileSubscribed`, so today that means *for the life of the process*. The repository-level
 * `distinctUntilChanged` on both reads keeps the downstream cost at zero, but the queries still re-run;
 * routing screen state through `TanukiViewModel.asState` (efficiency backlog 3.1) is what actually gates
 * them.
 *
 * @see NewChapter
 */
sealed interface ObserveNewChapters {
    /**
     * @return A [Flow] of the pending updates, most-recently-discovered first. Empty until a sync finds
     *   a chapter the user doesn't already have.
     */
    operator fun invoke(): Flow<List<NewChapter>>

    @Inject
    @ContributesBinding(AppScope::class)
    @OptIn(ExperimentalCoroutinesApi::class)
    class Default(
        private val chapterUpdateRepository: ChapterUpdateRepository,
        private val resolveChapterUpdates: ResolveChapterUpdates,
        private val dispatchers: DispatcherProvider
    ) : ObserveNewChapters {
        override fun invoke(): Flow<List<NewChapter>> =
            chapterUpdateRepository
                .observeUpdates()
                .distinctUntilChangedBy { updates -> updates.map(ChapterUpdate::chapterId) }
                .flatMapLatest(resolveChapterUpdates::observe)
                .flowOn(dispatchers.io)
    }
}
