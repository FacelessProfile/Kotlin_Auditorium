package com.example.kotlinroomdatabase

import com.example.kotlinroomdatabase.utils.TotpUtils
import org.junit.Assert.*
import org.junit.Test

class TotpUtilsTest {

    private val sampleBase32Secret = "JBSWY3DPEHPK3PXP" // Standard test secret

    @Test
    fun testGenerateCurrentCodeReturns6Digits() {
        val code = TotpUtils.generateCurrentCode(sampleBase32Secret, 30, 6)
        assertEquals(6, code.length)
        assertTrue("Code should contain only digits", code.all { it.isDigit() })
    }

    @Test
    fun testGetRemainingSecondsWithinWindow() {
        val remaining = TotpUtils.getRemainingSeconds(30)
        assertTrue("Remaining seconds should be >= 1", remaining >= 1)
        assertTrue("Remaining seconds should be <= 30", remaining <= 30)
    }
}
