package app.tanuki.core.data.repository.manga

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The `manga.filtered_scanlators` column is a joined string, so the codec is the one place a hidden
 * group can be silently lost (or a phantom one invented). These pin the edges: nothing hidden must
 * round-trip to `NULL` rather than `""`, and real group names — which contain commas, slashes and
 * brackets — must survive intact.
 */
class ScanlatorCodecTest {
    @Test
    fun `an empty selection encodes to null rather than an empty string`() {
        assertNull(encodeScanlators(emptySet()))
    }

    @Test
    fun `null and blank columns both decode to nothing hidden`() {
        assertEquals(emptySet(), decodeScanlators(null))
        assertEquals(emptySet(), decodeScanlators(""))
        assertEquals(emptySet(), decodeScanlators("\n\n"))
    }

    @Test
    fun `group names survive a round trip`() {
        // Punctuation a comma- or bracket-separated encoding would have had to escape.
        val groups = setOf("TCB Scans", "Team A / Team B", "[Oneshot] Scans", "Mangá, Br")
        assertEquals(groups, decodeScanlators(encodeScanlators(groups)))
    }

    @Test
    fun `a single group round trips`() {
        assertEquals(setOf("TCB Scans"), decodeScanlators(encodeScanlators(setOf("TCB Scans"))))
    }
}