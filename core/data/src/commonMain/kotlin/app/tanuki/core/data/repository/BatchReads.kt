package app.tanuki.core.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

/**
 * How many ids a single `WHERE id IN (:ids)` read binds at most.
 *
 * SQLite caps the number of bound parameters per statement (`SQLITE_MAX_VARIABLE_NUMBER`), and a batch
 * read is the one place in this layer where the caller — not the schema — decides how many parameters a
 * statement gets: an unbounded history or download list would otherwise choose it. 900 sits under the
 * lowest limit any build we ship on uses, and is far above the batch sizes real screens produce, so the
 * split below effectively never happens in practice.
 */
private const val MAX_BOUND_IDS = 900

/**
 * Runs [read] over [ids] in chunks small enough to bind, concatenating the results.
 *
 * Order is only preserved between chunks, not defined within one — every caller keys the result by id
 * rather than by position, which is also what makes splitting the read safe: the chunks are independent
 * lookups of disjoint id sets, so a split answer and a whole one differ only in the order rows arrive.
 *
 * Not transactional: a write landing between two chunks is visible to the later one. Callers are
 * snapshot readers projecting a list that is itself already a snapshot, so the worst case is a row that
 * is one write newer than its neighbours — the same staleness a single query would have against the
 * next write.
 */
internal suspend fun <Id, Row> Collection<Id>.readInBatches(read: suspend (List<Id>) -> List<Row>): List<Row> =
    when {
        isEmpty() -> emptyList()
        size <= MAX_BOUND_IDS -> read(toList())
        else -> chunked(MAX_BOUND_IDS).flatMap { read(it) }
    }

/**
 * The observed counterpart of [readInBatches]: runs [observe] over [ids] in chunks small enough to bind,
 * emitting the concatenated rows and re-emitting whenever any chunk's query is invalidated.
 *
 * The empty case returns a flow that *emits* an empty list rather than one that never emits. That is not
 * only an optimization: [combine] over zero flows never produces a value, so a collector of an empty id
 * set would hang instead of being told there is nothing — a subscriber waiting forever is a worse answer
 * than no rows.
 *
 * A split read is eventually consistent rather than atomic, which is the continuous form of the trade
 * [readInBatches] already documents: the first emission waits for every chunk, but afterwards a write
 * touching one chunk re-emits that chunk against its neighbours' last-seen rows. Callers key the result
 * by id, so the worst case is again a row one write newer than the rest.
 */
internal fun <Id, Row> Collection<Id>.observeInBatches(observe: (List<Id>) -> Flow<List<Row>>): Flow<List<Row>> =
    when {
        isEmpty() -> flowOf(emptyList())
        size <= MAX_BOUND_IDS -> observe(toList())
        else -> combine(chunked(MAX_BOUND_IDS).map(observe)) { chunks -> chunks.flatMap(List<Row>::asIterable) }
    }
