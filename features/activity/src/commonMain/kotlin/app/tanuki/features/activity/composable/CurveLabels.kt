package app.tanuki.features.activity.composable

import androidx.compose.runtime.Composable
import app.tanuki.core.domain.history.model.ReadingActivityPoint
import app.tanuki.core.domain.history.model.TimePeriod
import app.tanuki.features.activity.resources.Res
import app.tanuki.features.activity.resources.activity_month_apr
import app.tanuki.features.activity.resources.activity_month_aug
import app.tanuki.features.activity.resources.activity_month_dec
import app.tanuki.features.activity.resources.activity_month_feb
import app.tanuki.features.activity.resources.activity_month_jan
import app.tanuki.features.activity.resources.activity_month_jul
import app.tanuki.features.activity.resources.activity_month_jun
import app.tanuki.features.activity.resources.activity_month_mar
import app.tanuki.features.activity.resources.activity_month_may
import app.tanuki.features.activity.resources.activity_month_nov
import app.tanuki.features.activity.resources.activity_month_oct
import app.tanuki.features.activity.resources.activity_month_sep
import app.tanuki.features.activity.resources.activity_weekday_fri
import app.tanuki.features.activity.resources.activity_weekday_mon
import app.tanuki.features.activity.resources.activity_weekday_sat
import app.tanuki.features.activity.resources.activity_weekday_sun
import app.tanuki.features.activity.resources.activity_weekday_thu
import app.tanuki.features.activity.resources.activity_weekday_tue
import app.tanuki.features.activity.resources.activity_weekday_wed
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource

/** Day-of-month ticks to label on the month curve — roughly one per week, so a viewer can place "week 1". */
private val MONTH_TICK_DAYS = setOf(1, 8, 15, 22, 29)

/**
 * Localized x-axis tick labels for the reading curve, one entry per point (`null` = no tick), chosen to
 * suit the [period]:
 *  - **week** → a weekday initial under every day,
 *  - **month** → the day-of-month number at ~weekly ticks (so the start of each week is readable),
 *  - **year** → a month abbreviation under every month.
 *
 * Weekday/month names are localized copy (resolved here); day numbers are plain content.
 */
@Composable
internal fun curveAxisLabels(
    period: TimePeriod,
    points: List<ReadingActivityPoint>
): List<String?> {
    // Indexed by enum ordinal — DayOfWeek is MONDAY(0)..SUNDAY(6), Month is JANUARY(0)..DECEMBER(11).
    val weekdays =
        listOf(
            stringResource(Res.string.activity_weekday_mon),
            stringResource(Res.string.activity_weekday_tue),
            stringResource(Res.string.activity_weekday_wed),
            stringResource(Res.string.activity_weekday_thu),
            stringResource(Res.string.activity_weekday_fri),
            stringResource(Res.string.activity_weekday_sat),
            stringResource(Res.string.activity_weekday_sun)
        )
    val months =
        listOf(
            stringResource(Res.string.activity_month_jan),
            stringResource(Res.string.activity_month_feb),
            stringResource(Res.string.activity_month_mar),
            stringResource(Res.string.activity_month_apr),
            stringResource(Res.string.activity_month_may),
            stringResource(Res.string.activity_month_jun),
            stringResource(Res.string.activity_month_jul),
            stringResource(Res.string.activity_month_aug),
            stringResource(Res.string.activity_month_sep),
            stringResource(Res.string.activity_month_oct),
            stringResource(Res.string.activity_month_nov),
            stringResource(Res.string.activity_month_dec)
        )
    return when (period) {
        TimePeriod.WEEK -> {
            points.map { weekdays[it.date.dayOfWeek.ordinal] }
        }

        TimePeriod.YEAR -> {
            points.map { months[it.date.month.ordinal] }
        }

        TimePeriod.MONTH -> {
            points.map { point ->
                point.date.day
                    .takeIf { it in MONTH_TICK_DAYS }
                    ?.toString()
            }
        }
    }
}