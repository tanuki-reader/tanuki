package app.tanuki.features.library.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.EmptyLibraryIcon
import app.tanuki.features.library.resources.Res
import app.tanuki.features.library.resources.library_empty_message
import app.tanuki.features.library.resources.library_empty_title
import org.jetbrains.compose.resources.stringResource

@Composable
fun EmptyView(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(TanukiTheme.spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.sm, Alignment.CenterVertically)
    ) {
        EmptyLibraryIcon(tint = TanukiTheme.colors.textFaint)
        Text(
            text = stringResource(Res.string.library_empty_title),
            style = TanukiTheme.typography.headline,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(Res.string.library_empty_message),
            style = TanukiTheme.typography.body,
            color = TanukiTheme.colors.textFaint,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
@Preview
private fun EmptyViewPreview() {
    EmptyView(
        modifier = Modifier.fillMaxSize()
    )
}