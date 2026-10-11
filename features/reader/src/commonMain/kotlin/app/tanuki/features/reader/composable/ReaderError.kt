package app.tanuki.features.reader.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.TanukiButton
import app.tanuki.assets.designSystem.component.TanukiButtonVariant
import app.tanuki.features.reader.LocalReaderInk
import app.tanuki.features.reader.resources.Res
import app.tanuki.features.reader.resources.reader_error_message
import app.tanuki.features.reader.resources.reader_retry
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ReaderError(
    modifier: Modifier = Modifier,
    onRetry: () -> Unit
) {
    Column(
        modifier = modifier.padding(TanukiTheme.spacing.gutter),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.lg)
    ) {
        Text(
            text = stringResource(Res.string.reader_error_message),
            style = TanukiTheme.typography.body,
            color = LocalReaderInk.current,
            textAlign = TextAlign.Center
        )
        TanukiButton(
            text = stringResource(Res.string.reader_retry),
            onClick = onRetry,
            variant = TanukiButtonVariant.Filled
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ReaderErrorPreview() {
    ReaderError(
        modifier = Modifier.fillMaxSize(),
    ) {

    }
}