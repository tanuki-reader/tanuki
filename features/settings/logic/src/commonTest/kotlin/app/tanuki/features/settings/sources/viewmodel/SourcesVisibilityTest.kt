package app.tanuki.features.settings.sources.viewmodel

import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.source.model.CatalogSource
import kotlin.test.Test
import kotlin.test.assertEquals

class SourcesVisibilityTest {
    private val catalog =
        listOf(
            CatalogSource("mangadex", Text("MangaDex"), installed = true),
            CatalogSource("hipercool", Text("Hipercool"), installed = true, nsfw = true)
        )

    @Test
    fun `adult-only sources are hidden while sensitive content is off`() {
        assertEquals(listOf("mangadex"), catalog.visibleWith(showSensitive = false).map { it.sourceId })
    }

    @Test
    fun `every source shows once sensitive content is allowed`() {
        assertEquals(listOf("mangadex", "hipercool"), catalog.visibleWith(showSensitive = true).map { it.sourceId })
    }
}
