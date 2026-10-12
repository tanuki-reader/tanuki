package app.tanuki.core.source.abi

import app.tanuki.core.source.fixture.WIRE_FIXTURES
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The host half of the wire contract check (the guest half is `native/source-api/tests/fixtures.rs`).
 * Both read the same golden JSON, so `Wire.kt` and `wire.rs` can't drift apart unnoticed.
 *
 * Each fixture must decode **strictly** (an unknown key means a field one side doesn't have), and a
 * `full` fixture, which sets every field to a non-default value, must re-encode to exactly itself
 * (a field missing from the fixture means one `Wire.kt` has that the guest doesn't write).
 */
class WireFixtureTest {
    private val strict = Json(SourceCodec.json) { ignoreUnknownKeys = false }

    /** Fixture name prefix (before the first `.`) → the `Wire` type it describes. */
    private val types: Map<String, KSerializer<*>> =
        mapOf(
            "envelope" to Wire.Envelope.serializer(Wire.MangaPageWire.serializer()),
            "source_meta" to Wire.SourceMetaWire.serializer(),
            "manga" to Wire.MangaWire.serializer(),
            "manga_page" to Wire.MangaPageWire.serializer(),
            "source_home" to Wire.SourceHomeWire.serializer(),
            "chapter" to Wire.ChapterWire.serializer(),
            "page" to Wire.PageWire.serializer(),
            "http_request" to Wire.HttpRequestWire.serializer(),
            "source_home_request" to Wire.SourceHomeRequest.serializer(),
            "manga_list_request" to Wire.MangaListRequest.serializer(),
            "manga_details_request" to Wire.MangaDetailsRequest.serializer(),
            "chapter_list_request" to Wire.ChapterListRequest.serializer(),
            "chapter_pages_request" to Wire.ChapterPagesRequest.serializer(),
        )

    private val fixtures = WIRE_FIXTURES.filterKeys { it.startsWith("v${SourceAbi.VERSION}/") }

    @Test
    fun `every fixture for this ABI decodes strictly into its Wire type`() {
        assertTrue(fixtures.isNotEmpty(), "no fixtures for ABI v${SourceAbi.VERSION}")
        fixtures.forEach { (name, json) ->
            runCatching { strict.decodeFromString(serializerFor(name), json) }
                .onFailure { fail("$name: ${it.message}") }
        }
    }

    @Test
    fun `every full fixture re-encodes to itself`() {
        fixtures.filterKeys { it.endsWith(".full") }.forEach { (name, json) ->
            @Suppress("UNCHECKED_CAST")
            val serializer = serializerFor(name) as KSerializer<Any?>
            val reencoded = SourceCodec.json.encodeToJsonElement(serializer, strict.decodeFromString(serializer, json))
            assertEquals(SourceCodec.json.parseToJsonElement(json), reencoded, name)
        }
    }

    private fun serializerFor(name: String): KSerializer<*> {
        val prefix = name.substringAfter('/').substringBefore('.')
        return types[prefix] ?: fail("$name: no Wire type mapped for '$prefix'")
    }
}
