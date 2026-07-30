package app.tanuki.core.source

import app.tanuki.core.domain.common.service.LogPriority
import app.tanuki.core.domain.common.service.Logger

/**
 * A [Logger] that discards everything, for the tests where logging is a collaborator rather than the
 * thing under test. `core:domain`'s `RecordingLogger` isn't reachable from here — its `commonTest` isn't
 * published as a fixture — and nothing in this module asserts on log output, so a sink is enough.
 */
object TestLogger : Logger {
    override fun log(
        priority: LogPriority,
        tag: String?,
        message: String?,
        throwable: Throwable?,
        analyticsEvent: String?,
        addToBreadcrumbs: Boolean,
    ) = Unit

    override fun addReceiver(receiver: Logger.LogReceiver) = Unit

    override fun removeReceiver(receiver: Logger.LogReceiver) = Unit
}