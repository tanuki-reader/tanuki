package app.tanuki.core.navigation

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class AppNavigatorTest {
    @Test
    fun `selectTab emits a SelectTab command for that tab`() = runTest {
        val navigator = DefaultAppNavigator()
        navigator.selectTab(TopLevelDestination.Browser)
        assertEquals(NavCommand.SelectTab(TopLevelDestination.Browser), navigator.commands.first())
    }

    @Test
    fun `deepLink emits a DeepLink command carrying the destination`() = runTest {
        val navigator = DefaultAppNavigator()
        val destination = MangaDetail(Uuid.random())
        navigator.deepLink(destination)
        assertEquals(NavCommand.DeepLink(destination), navigator.commands.first())
    }

    @Test
    fun `commands emitted before collection are buffered and preserve order`() = runTest {
        val navigator = DefaultAppNavigator()
        val reader = Reader(mangaId = Uuid.random(), chapterId = Uuid.random())

        // Nothing is collecting yet — the buffer must hold these until the host subscribes.
        navigator.selectTab(TopLevelDestination.Library)
        navigator.deepLink(reader)

        assertEquals(
            listOf(
                NavCommand.SelectTab(TopLevelDestination.Library),
                NavCommand.DeepLink(reader)
            ),
            navigator.commands.take(2).toList()
        )
    }

    @Test
    fun `a command is delivered once and never replayed`() = runTest {
        val navigator = DefaultAppNavigator()
        navigator.selectTab(TopLevelDestination.Settings)

        // First collector consumes it...
        assertEquals(NavCommand.SelectTab(TopLevelDestination.Settings), navigator.commands.first())
        // ...and there is nothing left to replay to a later collector.
        assertNull(withTimeoutOrNull(1_000) { navigator.commands.first() })
    }
}
