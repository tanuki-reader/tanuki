package app.tanuki.core.source.abi

import kotlin.test.Test
import kotlin.test.assertEquals

class HtmlEntitiesTest {
    @Test
    fun `numeric and named references are decoded`() {
        assertEquals("Murim's sky", "Murim&#039;s sky".decodeHtmlEntities())
        assertEquals("Tom & \"Jerry\" <3", "Tom &amp; &quot;Jerry&quot; &lt;3".decodeHtmlEntities())
        assertEquals("it's — fine", "it&#x27;s &mdash; fine".decodeHtmlEntities())
        assertEquals("😀", "&#128512;".decodeHtmlEntities())
    }

    @Test
    fun `unknown or malformed references are left alone`() {
        assertEquals("AT&T &foo; &#; & done", "AT&T &foo; &#; & done".decodeHtmlEntities())
    }

    @Test
    fun `a source's description is decoded when mapped`() {
        val listing = Wire.MangaWire(key = "k", title = "A &amp; B", description = "Murim&#039;s").toDomain()
        assertEquals("A & B", listing.title)
        assertEquals("Murim's", listing.description)
    }
}
