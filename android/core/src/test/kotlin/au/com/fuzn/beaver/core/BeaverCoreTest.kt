package au.com.fuzn.beaver.core

import kotlin.test.Test
import kotlin.test.assertEquals

class BeaverCoreTest {
    @Test
    fun nameIsStable() {
        assertEquals("BeaverCore", BeaverCore.NAME)
    }
}
