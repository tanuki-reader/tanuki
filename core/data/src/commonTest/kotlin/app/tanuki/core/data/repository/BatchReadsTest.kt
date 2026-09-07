package app.tanuki.core.data.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [readInBatches] is what stands between an unbounded id list — a whole reading history, a full download
 * queue — and SQLite's cap on bound parameters. The split only happens on lists too big to see in a
 * fixture, so these pin it by counting the reads rather than by reading rows: that the common case still
 * costs exactly one query is as much the point as that the huge case is split at all.
 *
 * [observeInBatches] gets the same treatment one level up, counting *subscriptions* instead of reads —
 * plus the two properties only the live form has: an empty id set must still emit (a `combine` over no
 * flows never would, which would hang the collector rather than tell it there is nothing), and a change
 * to one chunk must re-emit the whole concatenation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
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

    @Test
    fun `an empty id list is observed without subscribing to anything`() =
        runTest {
            var subscriptions = 0
            val rows =
                emptyList<Int>()
                    .observeInBatches<Int, Int> { subscriptions++; flowOf(emptyList()) }
                    .first()

            assertEquals(emptyList(), rows, "an empty read must still answer, not hang")
            assertEquals(0, subscriptions, "an empty read must not reach the database")
        }

    @Test
    fun `a list that fits is observed through a single subscription`() =
        runTest {
            val ids = (1..900).toList()
            val batches = mutableListOf<List<Int>>()
            val rows = ids.observeInBatches { batch -> batches += batch; flowOf(batch) }.first()

            assertEquals(listOf(ids), batches)
            assertEquals(ids, rows)
        }

    @Test
    fun `a list past the bind limit is observed in chunks and concatenated`() =
        runTest {
            val ids = (1..2_050).toList()
            val batches = mutableListOf<List<Int>>()
            val rows = ids.observeInBatches { batch -> batches += batch; flowOf(batch) }.first()

            assertEquals(listOf(900, 900, 250), batches.map { it.size })
            assertEquals(ids, rows, "every id must be observed exactly once, in chunk order")
        }

    @Test
    fun `a change to one chunk re-emits the whole list`() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val ids = (1..2_050).toList()
            val chunks = mutableListOf<MutableStateFlow<List<Int>>>()
            val emissions = mutableListOf<List<Int>>()

            backgroundScope.launch(dispatcher) {
                ids
                    .observeInBatches { batch -> MutableStateFlow(batch).also { chunks += it } }
                    .collect { emissions += it }
            }
            advanceUntilIdle()

            // Changing one chunk must carry the other two chunks' latest rows along with it — that
            // concatenation is what `combine` is here for, and what a per-chunk flow alone would lose.
            chunks[1].value = chunks[1].value.map { -it }
            advanceUntilIdle()

            assertEquals(2, emissions.size)
            assertEquals(ids, emissions.first())
            assertEquals(ids.map { if (it in 901..1_800) -it else it }, emissions.last())
        }
}
