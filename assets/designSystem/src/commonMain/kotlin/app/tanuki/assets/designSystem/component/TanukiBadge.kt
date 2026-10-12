package app.tanuki.assets.designSystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tanuki.assets.designSystem.TanukiTheme

/**
 * The design system's `Badge`: an update or unread count, mono, on an `accent` pill. Hidden at 0 rather
 * than showing "0", and capped at "99+".
 */
@Composable
fun TanukiBadge(
    count: Int,
    modifier: Modifier = Modifier
) {
    if (count <= 0) return
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .sizeIn(minWidth = 20.dp, minHeight = 20.dp)
                .background(TanukiTheme.colors.accent, CircleShape)
                .padding(horizontal = 6.dp)
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            style = TanukiTheme.typography.numeric,
            color = TanukiTheme.colors.onAccent
        )
    }
}
