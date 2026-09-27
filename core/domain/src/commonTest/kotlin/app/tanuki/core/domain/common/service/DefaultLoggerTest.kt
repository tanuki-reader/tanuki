package app.tanuki.core.domain.common.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests [DefaultLogger] — specifically the two contracts that are invisible at call sites and would rot
 * silently: the debug-only rule (a [LogPriority.DEBUG] event must never escape a release build) and what
 * reaches the [CrashReporter], which decides what leaves the device.
 */
class DefaultLoggerTest {
    @Test
    fun `drops debug events in release builds before any sink sees them`() {
        val reporter = RecordingCrashReporter()
        val sink = RecordingReceiver()
        val logger = DefaultLogger(FixedBuildEnvironment(isDebug = false), reporter)
        logger.addReceiver(sink)

        logger.log(LogPriority.DEBUG, "tag", "trace", null, analyticsEvent = null, addToBreadcrumbs = true)

        assertTrue(sink.events.isEmpty())
        assertTrue(reporter.breadcrumbs.isEmpty())
        assertTrue(reporter.errors.isEmpty())
    }

    @Test
    fun `keeps debug events in debuggable builds`() {
        val sink = RecordingReceiver()
        val logger = DefaultLogger(FixedBuildEnvironment(isDebug = true), RecordingCrashReporter())
        logger.addReceiver(sink)

        logger.log(LogPriority.DEBUG, "tag", "trace", null, analyticsEvent = null, addToBreadcrumbs = false)

        assertEquals(1, sink.events.size)
    }

    @Test
    fun `reports errors and forwards the analytics event as the label`() {
        val reporter = RecordingCrashReporter()
        val boom = IllegalStateException("boom")
        val logger = DefaultLogger(FixedBuildEnvironment(isDebug = true), reporter)

        logger.error("reader", "page failed", boom, analyticsEvent = "page_decode_failed")

        val error = reporter.errors.single()
        assertEquals("reader", error.tag)
        assertEquals("page failed", error.message)
        assertEquals(boom, error.throwable)
        assertEquals("page_decode_failed", error.eventName)
    }

    @Test
    fun `records an error's own breadcrumb before reporting it`() {
        val reporter = RecordingCrashReporter()
        val logger = DefaultLogger(FixedBuildEnvironment(isDebug = true), reporter)

        logger.error("reader", "page failed")

        // The trail accompanying a report has to contain the event itself, which only holds if the
        // breadcrumb is recorded first.
        assertEquals(listOf("breadcrumb", "error"), reporter.calls)
    }

    @Test
    fun `does not report non-error events`() {
        val reporter = RecordingCrashReporter()
        val logger = DefaultLogger(FixedBuildEnvironment(isDebug = true), reporter)

        logger.info("library", "refreshed")
        logger.warn("library", "refresh was partial")

        assertTrue(reporter.errors.isEmpty())
    }

    @Test
    fun `info and warn and error helpers all leave a breadcrumb`() {
        val reporter = RecordingCrashReporter()
        val logger = DefaultLogger(FixedBuildEnvironment(isDebug = true), reporter)

        logger.info("library", "refreshed")
        logger.warn("library", "refresh was partial")
        logger.error("library", "refresh failed")

        assertEquals(
            listOf(LogPriority.INFO, LogPriority.WARN, LogPriority.ERROR),
            reporter.breadcrumbs.map { it.priority },
        )
    }

    @Test
    fun `a warning is a breadcrumb but not a report`() {
        val reporter = RecordingCrashReporter()
        val logger = DefaultLogger(FixedBuildEnvironment(isDebug = true), reporter)

        logger.warn("library", "refresh was partial", IllegalStateException("partial"))

        assertEquals("refresh was partial", reporter.breadcrumbs.single().message)
        assertTrue(reporter.errors.isEmpty())
    }

    @Test
    fun `only breadcrumbs events that asked for it`() {
        val reporter = RecordingCrashReporter()
        val logger = DefaultLogger(FixedBuildEnvironment(isDebug = true), reporter)

        logger.log(LogPriority.WARN, "source", "guest text", null, analyticsEvent = null, addToBreadcrumbs = false)

        assertTrue(reporter.breadcrumbs.isEmpty())
    }

    @Test
    fun `debug helper produces neither a breadcrumb nor a report in a release build`() {
        val reporter = RecordingCrashReporter()
        val logger = DefaultLogger(FixedBuildEnvironment(isDebug = false), reporter)

        logger.debug("downloads", "holding on chapter")

        assertTrue(reporter.breadcrumbs.isEmpty())
        assertTrue(reporter.errors.isEmpty())
    }

    @Test
    fun `a removed receiver stops seeing events`() {
        val sink = RecordingReceiver()
        val logger = DefaultLogger(FixedBuildEnvironment(isDebug = true), RecordingCrashReporter())
        logger.addReceiver(sink)
        logger.info("tag", "first")

        logger.removeReceiver(sink)
        logger.info("tag", "second")

        assertEquals(listOf("first"), sink.events.map { it.message })
    }

    @Test
    fun `registering the same receiver twice does not duplicate its events`() {
        val sink = RecordingReceiver()
        val logger = DefaultLogger(FixedBuildEnvironment(isDebug = true), RecordingCrashReporter())
        logger.addReceiver(sink)
        logger.addReceiver(sink)

        logger.info("tag", "once")

        assertEquals(1, sink.events.size)
    }
}

private class FixedBuildEnvironment(
    override val isDebug: Boolean,
    override val platform: AppPlatform = AppPlatform.Android,
    override val buildNumber: String = "1"
) : BuildEnvironment

private class RecordingReceiver : Logger.LogReceiver {
    data class Event(
        val priority: LogPriority,
        val tag: String?,
        val message: String?,
        val throwable: Throwable?
    )

    val events = mutableListOf<Event>()

    override fun onLog(
        priority: LogPriority,
        tag: String?,
        message: String?,
        throwable: Throwable?
    ) {
        events += Event(priority, tag, message, throwable)
    }
}

private class RecordingCrashReporter : CrashReporter {
    data class Breadcrumb(
        val priority: LogPriority,
        val tag: String?,
        val message: String?
    )

    data class Error(
        val tag: String?,
        val message: String?,
        val throwable: Throwable?,
        val eventName: String?
    )

    val breadcrumbs = mutableListOf<Breadcrumb>()
    val errors = mutableListOf<Error>()

    /** Call order, so a test can assert the breadcrumb lands before the report it belongs to. */
    val calls = mutableListOf<String>()

    var enabled: Boolean? = null

    override fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    override fun recordBreadcrumb(
        priority: LogPriority,
        tag: String?,
        message: String?
    ) {
        breadcrumbs += Breadcrumb(priority, tag, message)
        calls += "breadcrumb"
    }

    override fun reportError(
        tag: String?,
        message: String?,
        throwable: Throwable?,
        eventName: String?
    ) {
        errors += Error(tag, message, throwable, eventName)
        calls += "error"
    }
}