package app.tanuki.features.reader

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import app.tanuki.assets.designSystem.ReaderColors
import app.tanuki.core.domain.settings.model.ReaderBackground

/** The page ground for [ReaderBackground]. */
internal fun ReaderBackground?.ground(): Color =
    when (this) {
        ReaderBackground.Paper -> ReaderColors.Paper
        ReaderBackground.Sepia -> ReaderColors.Sepia
        ReaderBackground.Black, null -> ReaderColors.Black
    }

/**
 * Text and glyphs drawn straight on the reader's ground (transition slides, the end slide, the error):
 * white on black, `reader-sepia-ink` on sepia and on paper. The chrome sits on its own surface and doesn't
 * use it.
 */
internal fun ReaderBackground?.ink(): Color = if (this == ReaderBackground.Black || this == null) Color.White else ReaderColors.SepiaInk

internal val LocalReaderInk = staticCompositionLocalOf { Color.White }
