package app.tanuki.core.domain.source.usecase

import app.tanuki.core.domain.source.model.MangaListing
import app.tanuki.core.domain.source.model.MangaPage
import app.tanuki.core.domain.source.model.SourceListing
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/** A global search: each source answers on its own, and a bad one never sinks the rest. */
class SearchSourcesTest {
    @Test
    fun `results arrive as each source answers - fastest first`() = runTest {
        val browse = ScriptedBrowse(mapOf("slow" to 3.seconds, "fast" to 1.seconds))

        val results = SearchSources.Default(browse)("one piece", listOf("slow", "fast")).toList()

        assertEquals(listOf("fast", "slow"), results.map { it.first })
        assertEquals("one piece", (results.first().second as SourceSearch.Loaded).entries.single().title)
    }

    @Test
    fun `a failing or hanging source fails alone`() = runTest {
        val browse = ScriptedBrowse(mapOf("ok" to 1.seconds, "hangs" to 60.seconds), failing = setOf("broken"))

        val results = SearchSources.Default(browse)("q", listOf("ok", "broken", "hangs")).toList().toMap()

        assertEquals(SourceSearch.Failed, results["broken"])
        assertEquals(SourceSearch.Failed, results["hangs"])
        assertEquals(1, (results["ok"] as SourceSearch.Loaded).entries.size)
    }

    @Test
    fun `no more than the parallel limit run at once`() = runTest {
        val browse = ScriptedBrowse((1..10).associate { "s$it" to 1.seconds })

        SearchSources.Default(browse)("q", browse.delays.keys.toList()).toList()

        assertEquals(SearchSources.PARALLEL, browse.peak)
    }

    private class ScriptedBrowse(
        val delays: Map<String, kotlin.time.Duration>,
        private val failing: Set<String> = emptySet()
    ) : BrowseSource {
        private var running = 0
        var peak = 0

        override suspend fun invoke(
            sourceId: String,
            listing: SourceListing?,
            query: String?,
            page: Int
        ): MangaPage {
            if (sourceId in failing) error("$sourceId is down")
            running++
            peak = maxOf(peak, running)
            try {
                delay(delays.getValue(sourceId))
            } finally {
                running--
            }
            return MangaPage(listOf(MangaListing("$sourceId-1", title = query.orEmpty())))
        }
    }
}
