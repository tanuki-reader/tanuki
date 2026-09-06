package app.tanuki.assets.designSystem.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.tanuki.assets.designSystem.TanukiTheme

/**
 * A settings row whose value is a boolean — a [TanukiListRow] with a switch, where **the whole row is
 * the target**.
 *
 * That last part is the reason this exists rather than each screen assembling the two. A switch is
 * roughly 52×32dp; a row is 56dp tall and the full width of the screen. Wiring the row's `onClick` as
 * well turns a marginal tap target into an unmissable one, and it is exactly the line a caller forgets —
 * leaving a control that is technically compliant and annoying to hit. Encoding it here means no screen
 * can get it wrong.
 *
 * The switch is Material's, but its **off** colours are not: they are overridden by [tanukiSwitchColors]
 * because the defaults are invisible in this app. See that function for the numbers.
 *
 * @param title the setting's name.
 * @param checked its current value.
 * @param onCheckedChange the row or the switch was activated.
 * @param subtitle a second line explaining what turning it on does. Worth writing for anything whose
 *   effect is not obvious from the title — a switch with no explanation is a guess.
 * @param enabled `false` renders the row inert. Use it for a setting that is genuinely unavailable, not
 *   as a way to hide one.
 */
@Composable
fun TanukiSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true
) {
    TanukiListRow(
        title = title,
        subtitle = subtitle,
        modifier =
            modifier.semantics {
                // Announced as a switch rather than as a button, so a screen reader says "on"/"off"
                // instead of leaving the state to be inferred from the trailing control.
                role = Role.Switch
            },
        onClick = if (enabled) ({ onCheckedChange(!checked) }) else null,
        // The switch already shows where the row leads; a chevron would promise a sub-screen.
        showChevron = false,
        trailing = {
            Switch(
                checked = checked,
                // Also wired, rather than left to the row: dragging the thumb is how many users expect
                // a switch to work, and a switch that only responds to taps feels broken.
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                colors = tanukiSwitchColors()
            )
        }
    )
}

/**
 * Switch colours that survive being drawn on a [TanukiSectionGroup].
 *
 * Material's defaults do not, and the failure is total rather than marginal. Its unchecked track is
 * `surfaceVariant` — the exact token a section group paints its rows with — so the track is a **1.00:1**
 * match with the surface behind it, and its unchecked thumb is `outline`, which lands at **1.42:1** in
 * the dark dialect and **1.37:1** in the light one. Against a 3:1 floor for a graphical control, an
 * "off" switch simply was not there in either theme.
 *
 * The repair keeps Material's structure and moves the tokens:
 *
 * | | thumb on track | thumb on the row |
 * |---|---|---|
 * | dark | 6.2:1 | 7.2:1 |
 * | light | 4.3:1 | 4.9:1 |
 *
 * The **thumb** is what carries it, and that is the right place for the contrast: the state of a switch
 * is the knob's position, so the knob has to be the thing you can find. The track only has to read as a
 * well around it, which `outlineVariant` does — no token in either palette clears 3:1 against
 * `surfaceVariant`, and a track that shouted would compete with the accent of the *on* state.
 *
 * In the dark dialect this also lands the conventional arrangement — a light knob in a dark well. The
 * light dialect gets a mid-grey knob rather than the white one the platform would use, because there is
 * no token that is white on paper and light-grey on near-black; keeping one token pair for both beats
 * branching a design-system component on the theme.
 *
 * The checked colours are the Material defaults restated rather than changed (the orange track is
 * already 5.5:1 against the row in dark, 3.2:1 in light). They are named here so the whole set lives in
 * one place and half of it cannot drift.
 */
@Composable
private fun tanukiSwitchColors(): SwitchColors =
    SwitchDefaults.colors(
        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
        checkedTrackColor = MaterialTheme.colorScheme.primary,
        checkedBorderColor = MaterialTheme.colorScheme.primary,
        uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
        uncheckedTrackColor = MaterialTheme.colorScheme.outlineVariant,
        uncheckedBorderColor = MaterialTheme.colorScheme.outline,
        // Derived from the enabled colours rather than from Material's own disabled set, which is built
        // on the same `outline`/`surfaceVariant` pair and would put the control straight back where it
        // started. Fading what is legible keeps a disabled switch readable *as* a switch.
        disabledCheckedThumbColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = DISABLED_ALPHA),
        disabledCheckedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = DISABLED_ALPHA),
        disabledCheckedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = DISABLED_ALPHA),
        disabledUncheckedThumbColor =
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DISABLED_ALPHA),
        disabledUncheckedTrackColor =
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = DISABLED_ALPHA),
        disabledUncheckedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = DISABLED_ALPHA)
    )

/** Material's own disabled opacity. Legible enough to read as "not now", not as missing. */
private const val DISABLED_ALPHA = 0.38f

@Composable
@Preview
private fun TanukiSwitchRowPreview() {
    TanukiTheme {
        TanukiSectionGroup {
            // Off first, deliberately: it is the state that was invisible, so it is the one a preview
            // has to show.
            TanukiSwitchRow(
                title = "Only on Wi-Fi",
                subtitle = "Downloads wait until you're on an unmetered connection.",
                checked = false,
                onCheckedChange = {}
            )
            TanukiRowDivider(startInset = TanukiListRowDefaults.dividerInset(0.dp))
            TanukiSwitchRow(title = "On", checked = true, onCheckedChange = {})
            TanukiRowDivider(startInset = TanukiListRowDefaults.dividerInset(0.dp))
            TanukiSwitchRow(title = "Unavailable", checked = false, onCheckedChange = {}, enabled = false)
        }
    }
}
