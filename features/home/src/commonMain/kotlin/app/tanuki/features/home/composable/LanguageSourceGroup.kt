package app.tanuki.features.home.composable

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import app.tanuki.assets.designSystem.component.TanukiListRowDefaults
import app.tanuki.assets.designSystem.component.TanukiRowDivider
import app.tanuki.assets.designSystem.component.TanukiSectionGroup
import app.tanuki.features.home.viewmodel.HomeSource

/**
 * One language bucket in the Sources view: a tappable header (the language, plus a chevron that rotates
 * as it opens) over the group holding that language's [SourceRow]s, divided by hairlines.
 *
 * Deliberately the Settings index's section look — a section-header-weight title over one
 * [TanukiSectionGroup] of [TanukiRowDivider]-separated rows — so a language bucket and a settings section
 * are the same object, and only the collapsing chevron distinguishes them. Collapsing animates the group
 * away rather than snapping, so the surrounding list doesn't jump.
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
            // `md` does double duty: it lifts the tappable header to the `tapMin` target, and leaves the
            // same gap above the group that a Settings section header leaves above its own.
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = spacing.md),
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
            TanukiSectionGroup {
                sources.forEachIndexed { index, source ->
                    SourceRow(
                        name = source.name,
                        iconUrl = source.iconUrl,
                        onClick = { onSourceClick(source.id) }
                    )
                    if (index < sources.lastIndex) {
                        TanukiRowDivider(startInset = RowDividerInset)
                    }
                }
            }
        }
    }
}

/** Half a turn — the chevron points down when collapsed, up when open. */
private const val CHEVRON_EXPANDED_DEGREES = 180f

/** A [SourceRow]'s leading slot is a [app.tanuki.assets.designSystem.component.SourceIcon]. */
private val SOURCE_ICON_SIZE = 34.dp

/** Runs the rule between two sources under their names rather than their icons. */
private val RowDividerInset = TanukiListRowDefaults.dividerInset(SOURCE_ICON_SIZE)
