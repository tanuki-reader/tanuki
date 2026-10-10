package app.tanuki.features.settings.downloads.viewmodel

import app.tanuki.core.domain.category.model.Category
import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.download.model.DownloadSettings
import app.tanuki.core.domain.settings.model.AutoDownloadMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** The picker rows and the "pick one" prompt are derived here, so neither UI checks set membership. */
class DownloadSettingsStateTest {
    private val manga = Category(Uuid.random(), Text("Manga"))
    private val webtoons = Category(Uuid.random(), Text("Webtoons"))

    private fun state(
        mode: AutoDownloadMode,
        selected: Set<Uuid>
    ) = DownloadSettingsState(
        settings = DownloadSettings(autoDownloadMode = mode, autoDownloadCategories = selected),
        categories = listOf(manga, webtoons)
    )

    @Test
    fun `each option says whether its category is selected`() {
        val options = state(AutoDownloadMode.SelectedCategories, setOf(webtoons.id)).categoryOptions

        assertEquals(listOf("Manga" to false, "Webtoons" to true), options.map { it.name to it.selected })
    }

    @Test
    fun `the pick prompt shows only for an empty selection in the per-category mode`() {
        assertTrue(state(AutoDownloadMode.SelectedCategories, emptySet()).needsCategoryPick)
        assertFalse(state(AutoDownloadMode.SelectedCategories, setOf(manga.id)).needsCategoryPick)
        assertFalse(state(AutoDownloadMode.Off, emptySet()).needsCategoryPick)
    }
}
