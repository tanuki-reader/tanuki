package app.tanuki.core.domain.library.usecase

import app.tanuki.core.domain.category.repository.CategoryRepository
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.library.model.CategoryMatchMode.All
import app.tanuki.core.domain.library.model.CategoryMatchMode.Any
import app.tanuki.core.domain.library.model.LibraryFilter
import app.tanuki.core.domain.library.model.LibrarySort
import app.tanuki.core.domain.library.model.LibrarySort.Direction.Descending
import app.tanuki.core.domain.library.model.LibrarySort.Mode.Alphabetical
import app.tanuki.core.domain.library.model.LibrarySort.Mode.DateAdded
import app.tanuki.core.domain.library.model.LibrarySort.Mode.LastRead
import app.tanuki.core.domain.manga.model.Manga
import app.tanuki.core.domain.manga.model.PublishStatus.COMPLETED
import app.tanuki.core.domain.manga.repository.MangaRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlin.collections.orEmpty
import kotlin.uuid.Uuid

/**
 * Use case: observe the user's library, filtered, searched, and sorted.
 *
 * Owns **all** the library's domain rules — inclusion (filter), text matching (search), and ordering
 * (sort) — so they live in one shared, testable place rather than in a platform ViewModel. This keeps
 * the ViewModel a pure state machine: it holds the selected knobs and maps the emitted list into UI
 * state, with no list logic of its own. The returned [Flow] re-emits whenever the underlying library
 * changes **or** any of the caller's [searchQuery]/[sort]/[filter] selections do.
 *
 * The knobs are passed as [Flow]s, not values, on purpose: the library is a single [Room] query, and
 * filter/search/sort are pure projections over the rows it already produced. Feeding them as flows lets
 * this use case subscribe to the repository **once** and re-derive the view in memory when a knob turns
 * — instead of re-invoking (and so re-subscribing/re-querying) on every keystroke or toggle.
 *
 * @see LibrarySort
 * @see LibraryFilter
 */
sealed interface ObserveLibrary {
    /**
     * @param searchQuery A [Flow] of the live text query; re-emits re-match titles in memory. Blank
     *   (or all-whitespace) matches everything.
     * @param sort A [Flow] of how to order the result; re-emits re-order the view in memory.
     * @param filter A [Flow] of which manga to include; re-emits re-filter the view in memory.
     * @return A [Flow] of the favorite manga matching the latest [filter] and [searchQuery], ordered by
     *   the latest [sort].
     */
    operator fun invoke(
        searchQuery: Flow<String>,
        sort: Flow<LibrarySort>,
        filter: Flow<LibraryFilter>
    ): Flow<List<Manga>>

    @Inject
    @ContributesBinding(AppScope::class)
    class Default(
        private val mangaRepository: MangaRepository,
        private val categoryRepository: CategoryRepository,
        private val dispatchers: DispatcherProvider
    ) : ObserveLibrary {
        override fun invoke(
            searchQuery: Flow<String>,
            sort: Flow<LibrarySort>,
            filter: Flow<LibraryFilter>
        ): Flow<List<Manga>> =
            combine(
                mangaRepository.observeLibrary(),
                categoryRepository.observeLibraryCategories(),
                filter,
                searchQuery,
                sort
            ) { library, memberships, filter, query, sort ->
                library
                    .applyFilter(filter, memberships)
                    .applySearch(query)
                    .applySort(sort)
            }.flowOn(dispatchers.default)

        private fun List<Manga>.applyFilter(
            filter: LibraryFilter,
            memberships: Map<Uuid, Set<Uuid>>
        ): List<Manga> =
            filter { manga ->
                (filter.readingStatus == null || manga.readingStatus == filter.readingStatus) &&
                    filter.publicationCompleted.matches(manga.publishStatus == COMPLETED) &&
                    manga.matchesCategories(filter, memberships)
            }

        private fun Manga.matchesCategories(
            filter: LibraryFilter,
            memberships: Map<Uuid, Set<Uuid>>
        ): Boolean {
            if (filter.categories.isEmpty()) return true
            val assigned = memberships[id].orEmpty()
            return when (filter.categoryMatchMode) {
                Any -> filter.categories.any { it.id in assigned }
                All -> filter.categories.all { it.id in assigned }
            }
        }

        private fun List<Manga>.applySort(sort: LibrarySort): List<Manga> {
            val comparator: Comparator<Manga> =
                when (sort.mode) {
                    Alphabetical -> compareBy { it.title.value.lowercase() }
                    DateAdded -> compareBy(nullsFirst(), Manga::dateAdded)
                    LastRead -> compareBy(nullsFirst(), Manga::lastRead)
                }
            val sorted = sortedWith(comparator)
            return if (sort.direction == Descending) sorted.asReversed() else sorted
        }

        private fun List<Manga>.applySearch(query: String): List<Manga> {
            val q = query.trim()
            if (q.isEmpty()) return this
            return filter { it.title.value.contains(q, ignoreCase = true) }
        }
    }
}