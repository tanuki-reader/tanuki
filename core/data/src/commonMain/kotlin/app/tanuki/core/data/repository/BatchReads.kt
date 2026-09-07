package app.tanuki.core.data.repository

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
