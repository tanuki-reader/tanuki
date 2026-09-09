package app.tanuki.features.reader.composable.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins [PageWarmer]'s two rules: a page is handed to the loader at most once, and a warm stops being
 * cancellable exactly when it stops being in flight.
 *
 * These are the reader's network manners and they have no Compose in them, which is why the warmer is a
 * plain class — `:features:reader` has no Compose test infrastructure, so a rule living in the effect
 * body would be untestable.
 */
class PageWarmerTest {
    private class Warm : WarmHandle {
        var cancelled = false
            private set

        override fun cancel() {
            cancelled = true
        }
    }

    private class Loader {
        val started = mutableListOf<String>()
        val handles = mutableMapOf<String, Warm>()

        fun start(key: String): WarmHandle {
            started += key
            return Warm().also { handles[key] = it }
        }
    }

    @Test
    fun `a page is enqueued once however often it is in the window`() {
        val loader = Loader()
        val warmer = PageWarmer()

        repeat(3) { warmer.warm("a") { loader.start("a") } }

        assertEquals(listOf("a"), loader.started)
    }

    @Test
    fun `a warm outside the window is cancelled`() {
        val loader = Loader()
        val warmer = PageWarmer()
        warmer.warm("a") { loader.start("a") }
        warmer.warm("b") { loader.start("b") }

        warmer.cancelOutside(setOf("b"))

        assertTrue(loader.handles.getValue("a").cancelled)
        assertTrue(!loader.handles.getValue("b").cancelled)
    }

    @Test
    fun `a cancelled page is warmed again when focus comes back to it`() {
        val loader = Loader()
        val warmer = PageWarmer()
        warmer.warm("a") { loader.start("a") }
        warmer.cancelOutside(emptySet())

        warmer.warm("a") { loader.start("a") }

        assertEquals(listOf("a", "a"), loader.started)
    }

    @Test
    fun `a settled warm is neither cancellable nor enqueued again`() {
        val loader = Loader()
        val warmer = PageWarmer()
        warmer.warm("a") { loader.start("a") }
        warmer.settled("a")

        warmer.cancelOutside(emptySet())
        warmer.warm("a") { loader.start("a") }

        assertTrue(!loader.handles.getValue("a").cancelled)
        assertEquals(listOf("a"), loader.started)
    }

    @Test
    fun `a failed warm is retried the next time focus passes it`() {
        val loader = Loader()
        val warmer = PageWarmer()
        warmer.warm("a") { loader.start("a") }
        warmer.forget("a")

        warmer.warm("a") { loader.start("a") }

        assertEquals(listOf("a", "a"), loader.started)
    }

    @Test
    fun `a page that leaves the item list is forgotten and its warm cancelled`() {
        val loader = Loader()
        val warmer = PageWarmer()
        warmer.warm("a") { loader.start("a") }
        warmer.settled("a")
        warmer.warm("b") { loader.start("b") }

        warmer.retain(setOf("b"))

        assertTrue(loader.handles.getValue("b").cancelled.not())
        warmer.warm("a") { loader.start("a") }
        assertEquals(listOf("a", "b", "a"), loader.started)
    }

    @Test
    fun `leaving the composition cancels everything still in flight`() {
        val loader = Loader()
        val warmer = PageWarmer()
        warmer.warm("a") { loader.start("a") }
        warmer.warm("b") { loader.start("b") }
        warmer.settled("b")

        warmer.cancelAll()

        assertTrue(loader.handles.getValue("a").cancelled)
        assertTrue(!loader.handles.getValue("b").cancelled)
    }
}
