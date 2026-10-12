package app.tanuki.assets.designSystem

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight

@OptIn(ExperimentalTextApi::class)
internal actual fun displayFontFamily(
    weight: FontWeight,
    opticalSize: Float,
): FontFamily =
    FontFamily(
        Font(
            R.font.bricolage_grotesque,
            weight = weight,
            variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight), FontVariation.Setting("opsz", opticalSize)),
        ),
    )
