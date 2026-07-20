package app.tanuki.core.navigation.type

import androidx.savedstate.savedState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/**
 * `SavedState` round-trips for [UuidNavType]. Kept out of `commonTest` because on the Android JVM host
 * `SavedState` is backed by a stubbed `android.os.Bundle` whose accessors throw "not mocked" without a
 * device or Robolectric. The native targets have a real `SavedState`, so the actual `put`/`get` code
 * path is exercised here; the platform-independent behaviour stays in [UuidNavTypeTest].
 */
class UuidNavTypeSavedStateTest {
    private val sample = Uuid.parse("0191d1f0-7a2b-7c3d-8e4f-a1b2c3d4e5f6")

    @Test
    fun `put then get round-trips a value through SavedState`() {
        val state = savedState()
        UuidNavType.put(state, "id", sample)
        assertEquals(sample, UuidNavType[state, "id"])
    }

    @Test
    fun `get returns null when the key is absent`() {
        assertNull(UuidNavType[savedState(), "missing"])
    }
}