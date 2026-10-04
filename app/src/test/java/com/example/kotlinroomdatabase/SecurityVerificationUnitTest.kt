package com.example.kotlinroomdatabase

import com.example.kotlinroomdatabase.util.AppErrorLogger
import com.example.kotlinroomdatabase.util.SafeNdefManager
import org.junit.Assert.*
import org.junit.Test

class SecurityVerificationUnitTest {

    @Test
    fun testSanitizeLogText_redactsBearerJwt() {
        val raw = "Header: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0"
        val sanitized = AppErrorLogger.sanitizeLogText(raw)
        assertFalse("Raw token must not appear in sanitized output", sanitized.contains("dozjgNryP4J3jVmNHl0w5N_XgL0"))
        assertTrue("Must contain redacted placeholder", sanitized.contains("[REDACTED_JWT]"))
    }

    @Test
    fun testSanitizeLogText_redactsStandaloneJwtWithoutBearer() {
        val raw = "Got token eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1c2VySWQiOjQyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c in raw response"
        val sanitized = AppErrorLogger.sanitizeLogText(raw)
        assertFalse("Standalone JWT must be stripped", sanitized.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"))
        assertTrue("Must contain [REDACTED_JWT]", sanitized.contains("[REDACTED_JWT]"))
    }

    @Test
    fun testSanitizeLogText_redactsUnquotedPassword() {
        val raw = "POST /login body password=SYNTHETIC_PASSWORD&username=teacher"
        val sanitized = AppErrorLogger.sanitizeLogText(raw)
        assertFalse("Unquoted password must be masked", sanitized.contains("SYNTHETIC_PASSWORD"))
        assertTrue("Must contain password=[REDACTED]", sanitized.contains("password=[REDACTED]"))
    }

    @Test
    fun testSanitizeLogText_redactsJsonPasswordAndTokens() {
        val raw = """{"login":"admin","password":"MySecretPassword123","token":"jwt_abc_123","refresh_token":"rt_xyz_987"}"""
        val sanitized = AppErrorLogger.sanitizeLogText(raw)
        assertFalse("Password must be hidden", sanitized.contains("MySecretPassword123"))
        assertFalse("Token must be hidden", sanitized.contains("jwt_abc_123"))
        assertFalse("Refresh token must be hidden", sanitized.contains("rt_xyz_987"))
    }

    @Test
    fun testSanitizeLogText_redactsTotpSecret() {
        val raw = "totp_secret=JBSWY3DPEHPK3PXP"
        val sanitized = AppErrorLogger.sanitizeLogText(raw)
        assertFalse("TOTP secret must be redacted", sanitized.contains("JBSWY3DPEHPK3PXP"))
    }

    @Test
    fun testSanitizeLogText_redactsFcmRegistrationToken() {
        val fcmToken = "fcm_token=eK9xZ_dummy_device_registration_token_with_length_greater_than_forty_characters_1234567890_abcdefghij"
        val sanitized = AppErrorLogger.sanitizeLogText(fcmToken)
        assertFalse("FCM token must be redacted", sanitized.contains("eK9xZ_dummy_device_registration_token"))
    }

    @Test
    fun testNdefPasswordDerivationIsDeterministic() {
        val uid1 = "04:A2:3B:5C:8D:1E:80"
        val (pwd1, pack1) = SafeNdefManager.generateTagPassword(uid1)
        val (pwd2, pack2) = SafeNdefManager.generateTagPassword("04a23b5c8d1e80")

        assertEquals(4, pwd1.size)
        assertEquals(2, pack1.size)
        assertArrayEquals("Case and colon formatting must yield identical PWD", pwd1, pwd2)
        assertArrayEquals("Case and colon formatting must yield identical PACK", pack1, pack2)
    }
}
