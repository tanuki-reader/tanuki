package app.tanuki.core.domain.settings.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * How often the library checks its sources for new chapters.
 *
 * A coarse, named set rather than a free duration: the choice is a policy ("about daily"), and the
 * platform schedulers this feeds are approximate anyway — neither Android's WorkManager nor iOS's
 * background refresh runs at a precise time, so offering minute-level precision would promise something
 * that can't be delivered.
 *
 * @property period How long between checks, or `null` for [OFF] — which is not a period at all, and is
 *   what makes "is an update due?" a total function over this enum.
 */
enum class LibraryUpdateInterval(val period: Duration?) {
    /** Never check automatically; the library only refreshes when the user opens a series. */
    OFF(null),

    EVERY_12_HOURS(12.hours),

    DAILY(1.days),

    EVERY_2_DAYS(2.days),

    WEEKLY(7.days);

    companion object {
        /**
         * The default for a user who has never chosen: daily. Frequent enough that a series updating
         * on a weekly schedule is caught the day it lands, infrequent enough not to hammer sources
         * (or a phone's battery) for a library that mostly doesn't change.
         */
        val Default: LibraryUpdateInterval = DAILY
    }
}