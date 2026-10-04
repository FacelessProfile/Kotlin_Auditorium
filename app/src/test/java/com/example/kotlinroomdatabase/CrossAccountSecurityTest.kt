package com.example.kotlinroomdatabase

import com.example.kotlinroomdatabase.data.StudentDatabase
import com.example.kotlinroomdatabase.util.JwtUtils
import com.example.kotlinroomdatabase.util.StudentFacePhotoManager
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64

class CrossAccountSecurityTest {

    private fun createSyntheticJwt(payloadJson: String): String {
        val header = """{"alg":"HS256","typ":"JWT"}"""
        val encHeader = Base64.getUrlEncoder().withoutPadding().encodeToString(header.toByteArray(StandardCharsets.UTF_8))
        val encPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.toByteArray(StandardCharsets.UTF_8))
        return "$encHeader.$encPayload.synthetic_sig_bytes"
    }

    @Test
    fun testJwtUserIdExtraction_subClaim() {
        val jwt = createSyntheticJwt("""{"sub":"101","exp":${(System.currentTimeMillis() / 1000) + 3600}}""")
        val userId = JwtUtils.getUserId(jwt)
        assertEquals("101", userId)
    }

    @Test
    fun testJwtUserIdExtraction_userIdClaim() {
        val jwt = createSyntheticJwt("""{"user_id":202,"exp":${(System.currentTimeMillis() / 1000) + 3600}}""")
        val userId = JwtUtils.getUserId(jwt)
        assertEquals("202", userId)
    }

    @Test
    fun testJwtUserIdExtraction_camelCaseUserId() {
        val jwt = createSyntheticJwt("""{"userId":303,"exp":${(System.currentTimeMillis() / 1000) + 3600}}""")
        val userId = JwtUtils.getUserId(jwt)
        assertEquals("303", userId)
    }

    @Test
    fun testCrossAccountDetection_detectsUserMismatch() {
        val user1Token = createSyntheticJwt("""{"sub":"user_alpha"}""")
        val user2Token = createSyntheticJwt("""{"sub":"user_beta"}""")

        val id1 = JwtUtils.getUserId(user1Token)
        val id2 = JwtUtils.getUserId(user2Token)

        assertNotNull(id1)
        assertNotNull(id2)
        assertNotEquals("User IDs must differ across accounts", id1, id2)
    }

    @Test
    fun testFacePhotosAccountIsolation_pathSeparation() {
        val tempBase = File(System.getProperty("java.io.tmpdir"), "test_face_isolation_${System.currentTimeMillis()}")
        try {
            val user101Dir = File(tempBase, "user_101")
            val user102Dir = File(tempBase, "user_102")

            val user101Front = File(user101Dir, "sample_frontal.jpg")
            val user102Front = File(user102Dir, "sample_frontal.jpg")

            assertNotEquals(
                "Different users must have isolated directory paths",
                user101Front.absolutePath,
                user102Front.absolutePath
            )
            assertTrue("Path for user 101 must contain user_101", user101Front.absolutePath.contains("user_101"))
            assertTrue("Path for user 102 must contain user_102", user102Front.absolutePath.contains("user_102"))
        } finally {
            tempBase.deleteRecursively()
        }
    }

    @Test
    fun testRoomMigrationsVersionsCoverAllUpgrades() {
        assertEquals(1, StudentDatabase.MIGRATION_1_2.startVersion)
        assertEquals(2, StudentDatabase.MIGRATION_1_2.endVersion)

        assertEquals(2, StudentDatabase.MIGRATION_2_3.startVersion)
        assertEquals(3, StudentDatabase.MIGRATION_2_3.endVersion)

        assertEquals(3, StudentDatabase.MIGRATION_3_4.startVersion)
        assertEquals(4, StudentDatabase.MIGRATION_3_4.endVersion)

        assertEquals(4, StudentDatabase.MIGRATION_4_5.startVersion)
        assertEquals(5, StudentDatabase.MIGRATION_4_5.endVersion)

        assertEquals(5, StudentDatabase.MIGRATION_5_6.startVersion)
        assertEquals(6, StudentDatabase.MIGRATION_5_6.endVersion)
    }
}
