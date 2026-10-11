package app.tanuki.features.activity.composable

import androidx.compose.runtime.Composable
import app.tanuki.features.activity.viewmodel.ActivityBar
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

/** Day-of-month ticks to label on the month chart — roughly one per week ("Oct 1, 8, 15, 22, 29"). */
private val MONTH_TICK_DAYS = setOf(1, 8, 15, 22, 29)

/**
 * Localized x-axis labels for the bar chart, one entry per bar (`null` = no label), chosen to suit the
 * [period]: a weekday initial under every day of a week, the day number at ~weekly ticks of a month, a
 * month abbreviation under every month of a year.
 */
@Composable
internal fun barAxisLabels(
    period: TimePeriod,
    bars: List<ActivityBar>
): List<String?> {
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
    val months = monthAbbreviations()
    return when (period) {
        TimePeriod.WEEK -> bars.map { weekdays[LocalDate(it.year, it.month, it.day).dayOfWeek.ordinal] }
        TimePeriod.YEAR -> bars.map { months[it.month - 1] }
        TimePeriod.MONTH -> bars.map { bar -> bar.day.takeIf { it in MONTH_TICK_DAYS }?.toString() }
    }
}

/** Jan–Dec, localized. */
@Composable
internal fun monthAbbreviations(): List<String> =
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
