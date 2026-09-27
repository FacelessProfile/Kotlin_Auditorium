package com.example.kotlinroomdatabase

import com.example.kotlinroomdatabase.model.*
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeNoException
import org.junit.Test
import java.util.concurrent.TimeUnit

class ApiIntegrationUnitTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private fun getOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    @Test
    fun testSemesterInfoDeserialization() {
        val jsonString = """
            {
                "semester_id": 2,
                "name": "2025/2026, весенний семестр",
                "status": "closed",
                "starts_at": "2026-02-01T00:00:00+07:00",
                "ends_at": "2026-06-30T23:59:59+07:00",
                "is_current": false
            }
        """.trimIndent()

        val semester = json.decodeFromString<SemesterInfo>(jsonString)
        assertEquals(2, semester.id)
        assertEquals("2025/2026, весенний семестр", semester.name)
        assertEquals("closed", semester.status)
    }

    @Test
    fun testUserNotificationDeserialization() {
        val jsonString = """
            {
                "notification_id": 105,
                "user_id": 2,
                "title": "Новая оценка",
                "message": "Выставлена оценка за тест",
                "is_read": false,
                "created_at": "2026-08-20T10:00:00Z"
            }
        """.trimIndent()

        val notification = json.decodeFromString<UserNotification>(jsonString)
        assertEquals(105L, notification.realId)
        assertEquals("Новая оценка", notification.title)
        assertFalse(notification.is_read)
    }

    @Test
    fun testUserAgreementStatusDeserialization() {
        val jsonString = """
            {
                "agreement": "user_agreement",
                "version": "2026-08-01",
                "decision": "accepted",
                "accepted": true,
                "decided_at": "2026-08-23T12:00:00Z"
            }
        """.trimIndent()

        val status = json.decodeFromString<UserAgreementStatus>(jsonString)
        assertEquals("user_agreement", status.agreement)
        assertEquals("2026-08-01", status.version)
        assertTrue(status.accepted)
    }

    @Test
    fun testExternalServerHealthCheck() {
        try {
            val client = getOkHttpClient()
            val request = Request.Builder()
                .url("https://lms.signal.qlabs.pro:9001/healthz")
                .get()
                .build()

            val response = client.newCall(request).execute()
            assertTrue("External server should return successful HTTP code", response.isSuccessful)
        } catch (e: Exception) {
            assumeNoException("External live server not reachable in current offline unit test environment", e)
        }
    }

    @Test
    fun testExternalServerSemestersEndpoint() {
        try {
            val client = getOkHttpClient()
            val request = Request.Builder()
                .url("https://lms.signal.qlabs.pro:9001/api/semesters")
                .get()
                .build()

            val response = client.newCall(request).execute()
            assertTrue("Semesters endpoint should return 200", response.isSuccessful)
            val body = response.body?.string() ?: ""
            assertTrue("Response should contain semesters", body.contains("semesters") || body.contains("items"))
        } catch (e: Exception) {
            assumeNoException("External live server not reachable in current offline unit test environment", e)
        }
    }
}
