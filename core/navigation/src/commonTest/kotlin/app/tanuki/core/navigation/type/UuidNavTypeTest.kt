package app.tanuki.core.navigation.type

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.uuid.Uuid

class UuidNavTypeTest {
    private val sample = Uuid.parse("0191d1f0-7a2b-7c3d-8e4f-a1b2c3d4e5f6")

    @Test
    fun `serializeAsValue produces the canonical uuid string`() {
        assertEquals("0191d1f0-7a2b-7c3d-8e4f-a1b2c3d4e5f6", UuidNavType.serializeAsValue(sample))
    }

    @Test
    fun `parseValue reads a canonical uuid string`() {
        assertEquals(sample, UuidNavType.parseValue("0191d1f0-7a2b-7c3d-8e4f-a1b2c3d4e5f6"))
    }

    @Test
    fun `parseValue is the inverse of serializeAsValue`() {
        val id = Uuid.random()
        assertEquals(id, UuidNavType.parseValue(UuidNavType.serializeAsValue(id)))
    }

    @Test
    fun `name is Uuid`() {
        assertEquals(UUID_NAV_TYPE_NAME, UuidNavType.name)
    }

    @Test
    fun `the type is not nullable`() {
        assertFalse(UuidNavType.isNullableAllowed)
    }
}