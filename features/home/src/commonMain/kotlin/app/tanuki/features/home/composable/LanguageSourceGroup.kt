package app.tanuki.features.home.composable

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.ChevronDownIcon
import app.tanuki.assets.designSystem.component.TanukiCard
import app.tanuki.features.home.viewmodel.HomeSource

/**
 * One language bucket in the Sources view: a tappable header (the language, plus a chevron that rotates
 * as it opens) over a card holding that language's [SourceRow]s, divided by hairlines.
 *
 * The whole group shares a single [TanukiCard] so a language reads as one grouped list — the design's
 * section look — instead of a stack of per-source cards. Collapsing animates the card away rather than
 * snapping, so the surrounding list doesn't jump.
 *
 * @param lang the language tag serving as the header label (source-reported content, not UI copy).
 * @param expanded whether this group's rows are shown.
 * @param sources the sources in this language.
 * @param onToggle the header was tapped — expand or collapse.
 * @param onSourceClick a row was tapped, carrying its source id.
 */
@Composable
internal fun LanguageSourceGroup(
    lang: String,
    expanded: Boolean,
    sources: List<HomeSource>,
    onToggle: () -> Unit,
    onSourceClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = TanukiTheme.spacing
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) CHEVRON_EXPANDED_DEGREES else 0f,
        label = "sourceGroupChevron"
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = lang,
                style = TanukiTheme.typography.headline,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            ChevronDownIcon(
                tint = TanukiTheme.colors.textFaint,
                modifier = Modifier.rotate(chevronRotation)
            )
        }

        AnimatedVisibility(visible = expanded) {
            TanukiCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    sources.forEachIndexed { index, source ->
                        SourceRow(
                            name = source.name,
                            iconUrl = source.iconUrl,
                            onClick = { onSourceClick(source.id) }
                        )
                        if (index < sources.lastIndex) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier.padding(start = DIVIDER_START_INSET)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Half a turn — the chevron points down when collapsed, up when open. */
private const val CHEVRON_EXPANDED_DEGREES = 180f

/**
 * Where a [SourceRow]'s name starts: the row's leading padding (`md`) + the source icon (34dp) + the
 * gap before the text (`md`). Insetting the divider by it aligns the rule under the names rather than
 * the card edge, matching [app.tanuki.assets.designSystem.component.TanukiCardGroup]'s convention.
 */
private val DIVIDER_START_INSET = 58.dp
