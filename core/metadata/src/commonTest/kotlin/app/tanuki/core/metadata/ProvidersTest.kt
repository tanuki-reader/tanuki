package app.tanuki.core.metadata

import app.tanuki.core.domain.manga.model.PublishStatus
import app.tanuki.core.domain.metadata.model.CoverOrigin
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProvidersTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun `an AniList media maps to series metadata`() = runTest {
        var sent = ""
        val client =
            HttpClient(
                MockEngine { request ->
                    sent = request.body.toByteArray().decodeToString()
                    respond(MEDIA, HttpStatusCode.OK, json)
                }
            )

        val metadata = AniListProvider(client).byMalId(13)!!

        assertTrue("\"idMal\":13" in sent, "variables were $sent")
        assertEquals(30013L, metadata.anilistId)
        assertEquals("One Piece", metadata.title)
        assertEquals("Gol D. Roger was known as the Pirate King.\n\nThe greatest pirate.", metadata.synopsis)
        assertEquals(88, metadata.score)
        assertEquals(PublishStatus.ONGOING, metadata.status)
        assertEquals(listOf("Action", "Adventure"), metadata.genres)
        assertEquals(true, metadata.tags.single { it.name == "Time Skip" }.spoiler)
        assertEquals("Monkey D. Luffy", metadata.characters.single().name)
        assertEquals("Great.", metadata.reviews.single().summary)
        assertEquals("Bleach", metadata.recommendations.single().title)
    }

    @Test
    fun `an unknown AniList id is null - not an error`() = runTest {
        val client = HttpClient(MockEngine { respond("""{"data":{"Media":null},"errors":[{"status":404}]}""", HttpStatusCode.NotFound, json) })

        assertNull(AniListProvider(client).byAniListId(1))
    }

    @Test
    fun `MangaDex volume covers become cover options`() = runTest {
        val client = HttpClient(MockEngine { respond(COVERS, HttpStatusCode.OK, json) })

        val covers = MangaDexProvider(client).volumeCovers("op-id")

        assertEquals(
            listOf("https://uploads.mangadex.org/covers/op-id/a.jpg.512.jpg", "https://uploads.mangadex.org/covers/op-id/b.png.512.jpg"),
            covers.map { it.url }
        )
        assertEquals(listOf("1", "2"), covers.map { it.label })
        assertTrue(covers.all { it.origin == CoverOrigin.MangaDex })
    }

    @Test
    fun `an AniList search result carries every title - native and synonyms too`() = runTest {
        val client = HttpClient(MockEngine { respond(SEARCH, HttpStatusCode.OK, json) })

        val result = AniListProvider(client).search("sextudy group").single()

        assertEquals("Sex Study Group", result.title)
        assertEquals(listOf("Sex Study Group", "Sextudy Group", "섹터디그룹", "深度交流會"), result.titles)
    }

    @Test
    fun `a MangaDex title search asks for every rating and maps titles and links`() = runTest {
        var params = io.ktor.http.Parameters.Empty
        val client =
            HttpClient(
                MockEngine { request ->
                    params = request.url.parameters
                    respond(TITLE_SEARCH, HttpStatusCode.OK, json)
                }
            )

        val candidates = MangaDexProvider(client).byTitle("Sextudy Group")

        assertEquals("Sextudy Group", params["title"])
        assertEquals(listOf("safe", "suggestive", "erotica", "pornographic"), params.getAll("contentRating[]"))
        assertEquals(listOf("Sextudy Group", "Sex Study Group"), candidates[0].titles)
        assertEquals(Triple(145493L, 152218L, "sg-id"), Triple(candidates[0].anilistId, candidates[0].malId, candidates[0].mangaDexId))
        assertEquals(null to null, candidates[1].anilistId to candidates[1].malId)
    }
}

private val SEARCH =
    """
    {"data":{"Page":{"media":[{"id":145493,"format":"MANGA",
      "title":{"english":"Sex Study Group","romaji":"Sextudy Group","native":"섹터디그룹"},
      "synonyms":["深度交流會",""],"coverImage":{"large":"https://img/sg.jpg"},"startDate":{"year":2022}}]}}}
    """.trimIndent()

private val TITLE_SEARCH =
    """
    {"result":"ok","data":[
      {"id":"sg-id","attributes":{"title":{"en":"Sextudy Group"},"altTitles":[{"en":"Sex Study Group"}],
        "links":{"al":"145493","mal":"152218"}}},
      {"id":"other","attributes":{"title":{"en":"Other"},"altTitles":[],"links":[]}}
    ]}
    """.trimIndent()

private val MEDIA =
    """
    {"data":{"Media":{
      "id":30013,"idMal":13,"siteUrl":"https://anilist.co/manga/30013","status":"RELEASING","format":"MANGA",
      "title":{"english":"One Piece","romaji":"ONE PIECE"},
      "description":"Gol D. Roger was known as the <i>Pirate King</i>.<br><br><br>The greatest pirate.",
      "coverImage":{"extraLarge":"https://img/xl.jpg","large":"https://img/l.jpg"},
      "bannerImage":null,"averageScore":88,"popularity":300000,"genres":["Action","Adventure"],
      "tags":[{"name":"Pirates","rank":95,"isMediaSpoiler":false},{"name":"Time Skip","rank":80,"isMediaSpoiler":true}],
      "startDate":{"year":1997},
      "characters":{"edges":[{"role":"MAIN","node":{"name":{"full":"Monkey D. Luffy"},"image":{"medium":"https://img/luffy.jpg"}}}]},
      "reviews":{"nodes":[{"summary":"Great.","score":95,"siteUrl":"https://anilist.co/review/1"}]},
      "recommendations":{"nodes":[{"mediaRecommendation":{"id":30012,"idMal":12,"siteUrl":"https://anilist.co/manga/30012",
        "title":{"english":"Bleach","romaji":"BLEACH"},"coverImage":{"large":"https://img/bleach.jpg"}}}]}
    }}}
    """.trimIndent()

private val COVERS =
    """
    {"result":"ok","data":[
      {"id":"c1","attributes":{"fileName":"a.jpg","volume":"1"}},
      {"id":"c2","attributes":{"fileName":"b.png","volume":"2"}},
      {"id":"c3","attributes":{"volume":"3"}}
    ]}
    """.trimIndent()
