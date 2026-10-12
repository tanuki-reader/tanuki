package app.tanuki.assets.designSystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.tanuki.assets.designSystem.component.MangaCover
import app.tanuki.assets.designSystem.component.RatingLabel
import app.tanuki.assets.designSystem.component.SectionHeader
import app.tanuki.assets.designSystem.component.Segment
import app.tanuki.assets.designSystem.component.SourceAvatar
import app.tanuki.assets.designSystem.component.TanukiCard
import app.tanuki.assets.designSystem.component.TanukiCardGroup
import app.tanuki.assets.designSystem.component.TanukiFilterChip
import app.tanuki.assets.designSystem.component.TanukiSearchHeader
import app.tanuki.assets.designSystem.component.TanukiSegmentedControl
import app.tanuki.assets.designSystem.component.TanukiTag

/**
 * A dev showcase of the Tanuki Compose design system — every primitive rendered on real device chrome,
 * so the tokens and components can be eyeballed on both Android and iOS. This is a scaffolding artifact,
 * not shipping UI: it stands in for `App()` until the real feature screens and navigation land (Phase 4/5),
 * at which point its literal copy is replaced by localized string resources. Components themselves take
 * caller-supplied strings and never inline UI copy.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DesignSystemShowcase(modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .safeContentPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(TanukiTheme.spacing.gutter),
            verticalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.xxl)
        ) {
            Text(
                text = "Tanuki Design System",
                style = TanukiTheme.typography.hero,
                color = MaterialTheme.colorScheme.onBackground
            )

            ShowcaseSection("Manga cover") {
                Row(horizontalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.gridGap)) {
                    MangaCover(seed = "one", modifier = Modifier.width(104.dp), unread = 12u)
                    MangaCover(seed = "two", modifier = Modifier.width(104.dp), downloaded = true)
                    MangaCover(seed = "three", modifier = Modifier.width(104.dp), unread = 3u, downloaded = true)
                }
            }

            ShowcaseSection("Search header") {
                var query by remember { mutableStateOf("") }
                var filterActive by remember { mutableStateOf(false) }
                TanukiSearchHeader(
                    query = query,
                    onQueryChange = { query = it },
                    onFilterClick = { filterActive = !filterActive },
                    placeholder = "Search library…",
                    filterActive = filterActive
                )
            }

            ShowcaseSection("Tags") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.sm)) {
                    listOf("Action", "Romance", "Seinen", "Slice of Life").forEach { TanukiTag(it) }
                }
            }

            ShowcaseSection("Filter chips") {
                var selected by remember { mutableStateOf(setOf("Action")) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.sm)) {
                    listOf("Action" to 128, "Romance" to 74, "Horror" to 0, "Comedy" to 51).forEach { (name, n) ->
                        TanukiFilterChip(
                            text = name,
                            selected = name in selected,
                            count = n,
                            dimmed = n == 0,
                            onClick = {
                                selected = if (name in selected) selected - name else selected + name
                            }
                        )
                    }
                }
            }

            ShowcaseSection("Rating & sources") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.lg),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    RatingLabel("4.7")
                    SourceAvatar("MangaDex", TanukiTheme.colors.info)
                    SourceAvatar("Weeb Central", TanukiTheme.colors.accent)
                    SourceAvatar("ComicK", TanukiTheme.colors.success)
                }
            }

            ShowcaseSection("Segmented control") {
                var mode by remember { mutableStateOf("any") }
                TanukiSegmentedControl(
                    segments =
                        listOf(
                            Segment("any", "Match any"),
                            Segment("all", "Match all"),
                            Segment("exclude", "Exclude")
                        ),
                    selected = mode,
                    onSelect = { mode = it }
                )
            }

            ShowcaseSection("Card & group") {
                Column(verticalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.md)) {
                    TanukiCard {
                        Text(
                            text = "A flat Material surface with a hairline outline.",
                            style = TanukiTheme.typography.body,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(TanukiTheme.spacing.lg)
                        )
                    }
                    TanukiCardGroup(items = listOf("MangaDex", "Weeb Central", "ComicK")) { source ->
                        Row(
                            modifier =
                                Modifier
                                    .padding(horizontal = TanukiTheme.spacing.lg, vertical = TanukiTheme.spacing.md),
                            horizontalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.md),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            SourceAvatar(source, TanukiTheme.colors.accent)
                            Text(
                                text = source,
                                style = TanukiTheme.typography.headline,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShowcaseSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.md)) {
        SectionHeader(title = title)
        content()
    }
}

@Composable
@Preview
private fun ShowCasePreview() {
    TanukiTheme(darkTheme = true) {
        DesignSystemShowcase()
    }
}

@Composable
@Preview
private fun ShowCasePreviewLight() {
    TanukiTheme(darkTheme = false) {
        DesignSystemShowcase()
    }
}