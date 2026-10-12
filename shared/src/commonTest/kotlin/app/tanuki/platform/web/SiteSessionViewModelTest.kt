package app.tanuki.platform.web

import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.common.service.LogPriority
import app.tanuki.core.domain.common.service.Logger
import app.tanuki.core.source.net.session.ChallengeType
import app.tanuki.core.source.net.session.SILENT_SESSION_CEILING
import app.tanuki.core.source.net.session.SiteSessionCompletion
import app.tanuki.core.source.net.session.SiteSessionCoordinator
import app.tanuki.core.source.net.session.SiteSessionReason
import app.tanuki.core.source.net.session.SiteSessionRequest
import app.tanuki.core.source.net.session.SiteSessionResult
import app.tanuki.core.source.net.session.silentBudget
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The reveal rules moved out of the Compose layer; these pin them for both platforms' layers. */
@OptIn(ExperimentalCoroutinesApi::class)
class SiteSessionViewModelTest {
    private val mainDispatcher = StandardTestDispatcher()
    private val coordinator = SiteSessionCoordinator()
    private val viewModel = SiteSessionViewModel(coordinator, SessionTestDispatchers, SessionTestLogger)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterTest
    fun tearDown() {
        viewModel.detach()
        Dispatchers.resetMain()
    }

    @Test
    fun `a login is revealed at once and finishes on Done`() = runTest {
        viewModel.attach()
        val result = async { coordinator.request(login) }
        runCurrent()

        val state = active()
        assertTrue(state.revealed)
        assertTrue(state.needsConfirm)
        assertFalse(state.isChallenge)

        val snapshot = SiteSessionResult(mapOf("session" to "x"), "UA")
        viewModel.onSnapshot(snapshot)
        viewModel.onDone()
        assertEquals(snapshot, result.await())
        runCurrent()
        assertEquals(SiteSessionState.Idle, viewModel.uiState.value)
    }

    @Test
    fun `a challenge solves silently when the cookie changes`() = runTest {
        viewModel.attach()
        val result = async { coordinator.request(challenge) }
        runCurrent()
        assertFalse(active().revealed)

        viewModel.onSnapshot(onChallenge(cookie = "stale"))
        advanceTimeBy(2.seconds)
        val cleared = SiteSessionResult(mapOf(CF to "fresh"), "UA", onChallengePage = false)
        viewModel.onSnapshot(cleared)

        assertEquals(cleared, result.await())
        assertFalse(viewModel.uiState.value.let { it is SiteSessionState.Active && it.revealed })
    }

    @Test
    fun `a stale cookie in the baseline does not end the session`() = runTest {
        viewModel.attach()
        val result = async { coordinator.request(challenge) }
        runCurrent()

        viewModel.onSnapshot(onChallenge(cookie = "stale"))
        viewModel.onSnapshot(SiteSessionResult(mapOf(CF to "stale"), "UA", onChallengePage = false))
        runCurrent()

        assertFalse(result.isCompleted)
        viewModel.onDismiss()
        assertNull(result.await())
    }

    @Test
    fun `a challenge still on screen is revealed once its budget is spent`() = runTest {
        viewModel.attach()
        async { coordinator.request(challenge) }
        runCurrent()
        viewModel.onSnapshot(onChallenge(type = ChallengeType.Managed))

        advanceTimeBy(ChallengeType.Managed.silentBudget - 1.seconds)
        assertFalse(active().revealed)
        advanceTimeBy(1.5.seconds)
        assertTrue(active().revealed)
        viewModel.onDismiss()
    }

    @Test
    fun `a page that left the challenge waits for the ceiling`() = runTest {
        viewModel.attach()
        async { coordinator.request(challenge) }
        runCurrent()
        viewModel.onSnapshot(SiteSessionResult(emptyMap(), "UA", onChallengePage = false))

        advanceTimeBy(SILENT_SESSION_CEILING - 1.seconds)
        assertFalse(active().revealed)
        assertTrue(active().covered)
        advanceTimeBy(1.5.seconds)
        assertTrue(active().revealed)
        viewModel.onDismiss()
    }

    @Test
    fun `an interactive challenge is revealed right away`() = runTest {
        viewModel.attach()
        async { coordinator.request(challenge) }
        runCurrent()
        viewModel.onSnapshot(onChallenge(type = ChallengeType.Interactive))

        advanceTimeBy(0.5.seconds)
        assertTrue(active().revealed)
        assertFalse(active().covered)
        viewModel.onDismiss()
    }

    @Test
    fun `detaching cancels the session and stops new ones`() = runTest {
        viewModel.attach()
        val result = async { coordinator.request(challenge) }
        runCurrent()

        viewModel.detach()
        assertNull(result.await())
        assertEquals(SiteSessionState.Idle, viewModel.uiState.value)
        assertNull(coordinator.request(challenge))
    }

    private fun TestScope.active(): SiteSessionState.Active {
        runCurrent()
        return viewModel.uiState.value as SiteSessionState.Active
    }

    private fun onChallenge(
        cookie: String? = null,
        type: ChallengeType = ChallengeType.Unknown,
    ) = SiteSessionResult(
        cookies = cookie?.let { mapOf(CF to it) }.orEmpty(),
        userAgent = "UA",
        onChallengePage = true,
        challengeType = type,
    )

    private companion object {
        const val CF = "cf_clearance"
        val challenge =
            SiteSessionRequest(
                url = "https://example.com",
                host = "example.com",
                completion = SiteSessionCompletion.WhenCookieSet(CF),
                reason = SiteSessionReason.CLOUDFLARE_CHALLENGE,
            )
        val login =
            SiteSessionRequest(
                url = "https://example.com/login",
                host = "example.com",
                completion = SiteSessionCompletion.WhenUserConfirms,
                reason = SiteSessionReason.LOGIN,
            )
    }
}

private object SessionTestDispatchers : DispatcherProvider {
    override val default: CoroutineDispatcher get() = Dispatchers.Main
    override val io: CoroutineDispatcher get() = Dispatchers.Main
    override val main: CoroutineDispatcher get() = Dispatchers.Main
}

private object SessionTestLogger : Logger {
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
