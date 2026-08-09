package com.atnip.seizuretracker.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HouseholdCodeTest {

    @Test
    fun `normalize trims and uppercases`() {
        assertEquals("ABC123", HouseholdCode.normalize("  abc123  "))
    }

    @Test
    fun `normalize is idempotent`() {
        val once = HouseholdCode.normalize(" abc123 ")
        assertEquals(once, HouseholdCode.normalize(once))
    }

    @Test
    fun `generate produces the requested length`() {
        assertEquals(6, HouseholdCode.generate().length)
        assertEquals(10, HouseholdCode.generate(10).length)
    }

    @Test
    fun `generate never contains visually ambiguous characters`() {
        val ambiguous = setOf('0', 'O', '1', 'I')
        repeat(200) {
            val code = HouseholdCode.generate(20)
            assertTrue(
                "code \"$code\" contained an ambiguous character",
                code.none { it in ambiguous }
            )
        }
    }

    @Test
    fun `generate only uses uppercase alphanumeric characters`() {
        val code = HouseholdCode.generate(50)
        assertTrue(code.all { it.isUpperCase() || it.isDigit() })
        assertFalse(code.any { it.isLowerCase() })
    }
}
