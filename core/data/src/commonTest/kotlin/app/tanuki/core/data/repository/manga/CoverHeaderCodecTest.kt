package app.tanuki.core.data.repository.manga

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The `manga.cover_headers` column is a joined string, and getting it wrong is invisible until a whole
 * source's covers turn into grey rectangles — a Hipercool cover 403s the moment its `Referer` is
 * dropped or mangled. These pin the edges: no headers round-trips to `NULL`, and a header value
 * containing the `:` that separates name from value (every value here is a URL, so all of them do)
 * survives whole.
 */
class CoverHeaderCodecTest {
    @Test
    fun `no headers encodes to null rather than an empty string`() {
        assertNull(encodeHeaders(emptyMap()))
    }

    @Test
    fun `null and blank columns both decode to no headers`() {
        assertEquals(emptyMap(), decodeHeaders(null))
        assertEquals(emptyMap(), decodeHeaders(""))
        assertEquals(emptyMap(), decodeHeaders("\n\n"))
    }

    @Test
    fun `a referer url survives a round trip despite containing the separator`() {
        val headers = mapOf("Referer" to "https://lerhentais.com/")
        assertEquals(headers, decodeHeaders(encodeHeaders(headers)))
    }

    @Test
    fun `several headers round trip`() {
        val headers =
            mapOf(
                "Referer" to "https://lerhentais.com/",
                "User-Agent" to "Tanuki (+https://tanuki.app)",
            )
        assertEquals(headers, decodeHeaders(encodeHeaders(headers)))
    }
}