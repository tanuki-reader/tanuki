package app.tanuki.core.data.repository

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [readInBatches] is what stands between an unbounded id list — a whole reading history, a full download
 * queue — and SQLite's cap on bound parameters. The split only happens on lists too big to see in a
 * fixture, so these pin it by counting the reads rather than by reading rows: that the common case still
 * costs exactly one query is as much the point as that the huge case is split at all.
 */
class BatchReadsTest {
    @Test
    fun `an empty id list reads nothing at all`() =
        runTest {
            val batches = mutableListOf<List<Int>>()
            val rows = emptyList<Int>().readInBatches { batch -> batches += batch; batch }

            assertEquals(emptyList(), rows)
            assertEquals(emptyList(), batches, "an empty read must not reach the database")
        }

    @Test
    fun `a list that fits is read in a single query`() =
        runTest {
            val ids = (1..900).toList()
            val batches = mutableListOf<List<Int>>()
            val rows = ids.readInBatches { batch -> batches += batch; batch }

            assertEquals(listOf(ids), batches)
            assertEquals(ids, rows)
        }

    @Test
    fun `a list past the bind limit is split and the results concatenated`() =
        runTest {
            val ids = (1..2_050).toList()
            val batches = mutableListOf<List<Int>>()
            val rows = ids.readInBatches { batch -> batches += batch; batch }

            assertEquals(listOf(900, 900, 250), batches.map { it.size })
            assertEquals(ids, rows, "every id must be read exactly once, in chunk order")
        }

    @Test
    fun `ids with no row simply do not come back`() =
        runTest {
            val stored = setOf(2, 4)
            val rows = listOf(1, 2, 3, 4).readInBatches { batch -> batch.filter { it in stored } }

            assertEquals(listOf(2, 4), rows)
        }
}
