package app.tanuki.assets.designSystem.component

import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
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
 * The switch itself is Material's, deliberately: its checked track takes `colorScheme.primary`, which is
 * already the Tanuki accent, and its unchecked colours come from `surfaceVariant`/`outline` — every one
 * of them a token this app's scheme sets. Unlike `DropdownMenu` (see [TanukiMenu]), there is no
 * tonal-elevation surface being computed behind the app's back, so there is nothing to override and a
 * wrapper would only be indirection.
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
                enabled = enabled
            )
        }
    )
}

@Composable
@Preview
private fun TanukiSwitchRowPreview() {
    TanukiTheme {
        TanukiSectionGroup {
            TanukiSwitchRow(
                title = "Only on Wi-Fi",
                subtitle = "Downloads wait until you're on an unmetered connection.",
                checked = true,
                onCheckedChange = {}
            )
        }
    }
}
