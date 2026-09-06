package app.tanuki.features.settings.downloads

import androidx.compose.runtime.Composable
import app.tanuki.core.domain.settings.model.DeleteAfterReading
import app.tanuki.features.settings.resources.Res
import app.tanuki.features.settings.resources.settings_bytes
import app.tanuki.features.settings.resources.settings_delete_immediately
import app.tanuki.features.settings.resources.settings_delete_immediately_hint
import app.tanuki.features.settings.resources.settings_delete_keep_five
import app.tanuki.features.settings.resources.settings_delete_keep_five_hint
import app.tanuki.features.settings.resources.settings_delete_keep_one
import app.tanuki.features.settings.resources.settings_delete_keep_one_hint
import app.tanuki.features.settings.resources.settings_delete_never
import app.tanuki.features.settings.resources.settings_delete_never_hint
import app.tanuki.features.settings.resources.settings_gigabytes
import app.tanuki.features.settings.resources.settings_kilobytes
import app.tanuki.features.settings.resources.settings_megabytes
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

/**
 * The copy for each [DeleteAfterReading] option.
 *
 * A `when` over the enum rather than a label on the enum itself: the model lives in `:core:domain`,
 * which has no Compose Resources and must not hold display copy (CLAUDE.md → Localization). Exhaustive,
 * so a new option cannot ship without someone writing words for it.
 */
internal fun DeleteAfterReading.labelRes(): StringResource =
    when (this) {
        DeleteAfterReading.Off -> Res.string.settings_delete_never
        DeleteAfterReading.Immediately -> Res.string.settings_delete_immediately
        DeleteAfterReading.KeepLastOne -> Res.string.settings_delete_keep_one
        DeleteAfterReading.KeepLastFive -> Res.string.settings_delete_keep_five
    }

/** What choosing the option actually does — see the screen's KDoc on why every choice carries one. */
internal fun DeleteAfterReading.hintRes(): StringResource =
    when (this) {
        DeleteAfterReading.Off -> Res.string.settings_delete_never_hint
        DeleteAfterReading.Immediately -> Res.string.settings_delete_immediately_hint
        DeleteAfterReading.KeepLastOne -> Res.string.settings_delete_keep_one_hint
        DeleteAfterReading.KeepLastFive -> Res.string.settings_delete_keep_five_hint
    }

/** Below this many bytes, a size is shown in the smaller unit. 1000, not 1024 — see [asFileSize]. */
private const val UNIT_STEP = 1000L

/**
 * A byte count as the user would read it: `847 KB`, `1.4 GB`.
 *
 * **Decimal units (1000), not binary (1024).** Both Android's and iOS's own storage screens report
 * decimal, so a library that reads "2.1 GB" here and "2.1 GB" in system settings is telling the same
 * story — which is the entire job of this number. Being 7% off the binary figure matters far less than
 * disagreeing with the OS the user is about to go and check.
 *
 * One decimal place from KB up, and none for raw bytes: "1.4 GB" is a size, "1.43 GB" is a measurement,
 * and nobody makes a different decision because of the second digit.
 */
@Composable
internal fun Long.asFileSize(): String {
    val bytes = abs(this)
    return when {
        bytes < UNIT_STEP -> stringResource(Res.string.settings_bytes, this)
        bytes < UNIT_STEP * UNIT_STEP ->
            stringResource(Res.string.settings_kilobytes, (this / UNIT_STEP.toDouble()).oneDecimal())
        bytes < UNIT_STEP * UNIT_STEP * UNIT_STEP ->
            stringResource(
                Res.string.settings_megabytes,
                (this / (UNIT_STEP * UNIT_STEP).toDouble()).oneDecimal(),
            )
        else ->
            stringResource(
                Res.string.settings_gigabytes,
                (this / (UNIT_STEP * UNIT_STEP * UNIT_STEP).toDouble()).oneDecimal(),
            )
    }
}

/**
 * One decimal place, with the `.0` dropped.
 *
 * Hand-rolled because Kotlin/Native has no `String.format`, and pulling a formatting library in for one
 * number would be a dependency to serve four call sites. The rounding is deliberate: `847.96` becomes
 * `848`, not `847.9`, so a size never appears to shrink when it grows past a rounding boundary.
 */
private fun Double.oneDecimal(): String {
    val rounded = kotlin.math.round(this * 10) / 10
    val whole = rounded.toLong()
    val tenths = kotlin.math.round(abs(rounded - whole) * 10).toLong()
    return if (tenths == 0L) whole.toString() else "$whole.$tenths"
}