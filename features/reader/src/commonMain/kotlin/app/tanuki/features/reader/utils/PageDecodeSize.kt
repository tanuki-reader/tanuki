package app.tanuki.features.reader.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalWindowInfo
import app.tanuki.features.reader.composable.components.PagePrefetch
import coil3.size.Size

/**
 * The pixel size page bitmaps are decoded at — the single definition both the rendered page and
 * [PagePrefetch] size their requests from, so a warmed page and the page later drawn resolve to the *same*
 * decoded bitmap instead of two.
 *
 * Without a size Coil decodes at the source's native resolution: `AsyncImagePainter` falls back to
 * `SizeResolver.ORIGINAL` when a request defines no size, and so does `ImageRequest.Defaults`. The full-size
 * bitmap is then rescaled to the viewport by the GPU on every drawn frame, and — far more expensively — a
 * handful of them exhausts the page loader's memory budget, so items the reader recycles have to be decoded
 * again each time they scroll back in. Decoding to a bounded size instead is what keeps the cache from
 * thrashing while scrolling.
 *
 * Sizing is deliberately **the same in both reading modes**. The strip and the pager fit pages differently,
 * but a shared size means switching reading mode re-uses the decoded pages rather than re-decoding the whole
 * chapter — and it matches [app.tanuki.features.reader.composable.components.PageAspectRatios], which is
 * likewise kept across a mode switch.
 *
 * @return the target, or [Size.ORIGINAL] before the window has reported a size (nothing is known to bound
 *   against yet, so this degrades to Coil's own default rather than guessing a width).
 */
@Composable
internal fun rememberPageDecodeSize(): Size {
    val containerWidth = LocalWindowInfo.current.containerSize.width
    return remember(containerWidth) {
        if (containerWidth <= 0) {
            Size.ORIGINAL
        } else {
            Size(containerWidth * PAGE_DECODE_ZOOM_HEADROOM, MAX_PAGE_DECODE_HEIGHT)
        }
    }
}

/**
 * How many times the viewport width a page is decoded at, so zooming reveals real pixels rather than a
 * magnified bitmap. Matches Telephoto's default `maxZoomFactor` of 2, so a page in the pager stays sharp
 * to the zoom limit; the strip allows 4x (`MAX_SCALE`) and so runs at half resolution when fully zoomed,
 * which is the accepted trade for not holding every page at native resolution.
 */
private const val PAGE_DECODE_ZOOM_HEADROOM = 2

/**
 * Height ceiling for a decoded page. Set to Coil's own default `maxBitmapSize` so long webtoon strips stay
 * clamped exactly as they already were — stating it here makes the bound explicit rather than incidental,
 * and stops a width-only target from silently lifting it (an undefined height resolves to `Int.MAX_VALUE`,
 * which Coil then declines to clamp, and a 10000px strip would decode in full).
 */
private const val MAX_PAGE_DECODE_HEIGHT = 4096