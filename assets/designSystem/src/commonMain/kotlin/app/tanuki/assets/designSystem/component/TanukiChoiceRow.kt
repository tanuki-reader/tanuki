package app.tanuki.assets.designSystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.tanuki.assets.designSystem.TanukiTheme

/** The tick's size, and the width the slot reserves whether or not it is showing one. */
private val CheckSize = 20.dp

/**
 * One option in a pick-exactly-one list — a [TanukiListRow] that marks itself when chosen.
 *
 * The alternative for a small, fixed set is [TanukiSegmentedControl], and the choice between them is
 * about the labels, not the count: a segmented control needs every label to survive being a third of the
 * screen wide. "Off / Light / Dark" does; "Keep the last five read chapters" does not. When an option
 * needs a sentence to explain it — which is most settings worth having — it needs a row.
 *
 * The tick's slot is reserved on every row, selected or not, so choosing a different option does not
 * shift the text of the whole list sideways under the user's finger.
 *
 * Marked with `Role.RadioButton` and a `selected` state, which is what makes a screen reader announce
 * these as one group of alternatives rather than as a stack of unrelated buttons.
 *
 * @param title the option's name.
 * @param selected whether this is the current choice.
 * @param onClick this option was chosen. Called even when it is already selected — re-picking is
 *   harmless and swallowing it would make the row feel dead.
 * @param subtitle a second line saying what choosing it does. The reason this component exists.
 */
@Composable
fun TanukiChoiceRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null
) {
    TanukiListRow(
        title = title,
        subtitle = subtitle,
        modifier =
            modifier.semantics {
                role = Role.RadioButton
                this.selected = selected
            },
        onClick = onClick,
        // A chevron would promise a sub-screen; the tick is the whole answer.
        showChevron = false,
        trailing = {
            Box(
                modifier = Modifier.size(CheckSize),
                contentAlignment = Alignment.Center
            ) {
                if (selected) {
                    CheckIcon(tint = TanukiTheme.colors.accent, size = CheckSize)
                }
            }
        }
    )
}

@Composable
@Preview
private fun TanukiChoiceRowPreview() {
    TanukiTheme {
        TanukiSectionGroup {
            TanukiChoiceRow(
                title = "Keep the last five",
                subtitle = "Older read chapters are removed from this device.",
                selected = true,
                onClick = {}
            )
            TanukiRowDivider(startInset = TanukiListRowDefaults.dividerInset(0.dp))
            TanukiChoiceRow(title = "Never", selected = false, onClick = {})
        }
    }
}