package com.example.kotlinroomdatabase.util

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

object JwtUtils {

    /**
     * Extracts expiration timestamp (in milliseconds) from JWT claims.
     * Returns 0L if not present or cannot be parsed.
     */
    fun getExpirationMillis(jwtToken: String?): Long {
        if (jwtToken.isNullOrBlank()) return 0L
        return try {
            val parts = jwtToken.split(".")
            if (parts.size >= 2) {
                val payloadBytes = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                val json = JSONObject(String(payloadBytes, Charsets.UTF_8))
                val expSec = json.optLong("exp", 0L)
                expSec * 1000L
            } else {
                0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Extracts issued-at timestamp (in milliseconds) from JWT claims.
     */
    fun getIssuedAtMillis(jwtToken: String?): Long {
        if (jwtToken.isNullOrBlank()) return 0L
        return try {
            val parts = jwtToken.split(".")
            if (parts.size >= 2) {
                val payloadBytes = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                val json = JSONObject(String(payloadBytes, Charsets.UTF_8))
                val iatSec = json.optLong("iat", 0L)
                iatSec * 1000L
            } else {
                0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Returns the remaining lifetime of the token in milliseconds.
     * Positive if valid, <= 0 if expired.
     */
    fun getTimeRemainingMillis(jwtToken: String?): Long {
        val expMillis = getExpirationMillis(jwtToken)
        if (expMillis == 0L) return Long.MAX_VALUE // Token has no expiration claim
        return expMillis - System.currentTimeMillis()
    }

    /**
     * Predicts whether the token should be refreshed.
     * Returns true if the token expires within [thresholdMinutes] (default 10 minutes)
     * or is already expired.
     */
    fun needsRefresh(jwtToken: String?, thresholdMinutes: Long = 10L): Boolean {
        if (jwtToken.isNullOrBlank()) return true
        val remaining = getTimeRemainingMillis(jwtToken)
        if (remaining == Long.MAX_VALUE) return false
        val thresholdMillis = thresholdMinutes * 60 * 1000L
        return remaining <= thresholdMillis
    }

    /**
     * Checks if the given JWT token is strictly expired.
     */
    fun isExpired(jwtToken: String?): Boolean {
        if (jwtToken.isNullOrBlank()) return true
        val expMillis = getExpirationMillis(jwtToken)
        if (expMillis == 0L) {
            return jwtToken.length < 20
        }
        return System.currentTimeMillis() >= expMillis
    }

    /**
     * Checks if the active user session is present.
     */
    fun isUserSessionValid(context: Context): Boolean {
        val authPrefs = context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
        val token = authPrefs.getString("auth_token", null)
        val studentPrefs = context.getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
        val studentId = studentPrefs.getInt("current_student_id", -1)

        if (studentId == -1 || token.isNullOrBlank()) {
            return false
        }
        // Allow refresh within backend grace period (up to 14 days past expiration)
        val expMillis = getExpirationMillis(token)
        if (expMillis > 0 && System.currentTimeMillis() > expMillis + 14 * 24 * 3600 * 1000L) {
            return false
        }
        return true
    }

    /**
     * Clears all session, credentials, TOTP, Room cache, and attendance preferences on logout (KA-08).
     */
    fun clearAllSessionData(context: Context) {
        try {
            val token = context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE).getString("auth_token", null)
            val fcmToken = context.getSharedPreferences("fcm_prefs", Context.MODE_PRIVATE).getString("fcm_token", null)

            // 1. Try to unregister FCM device token on backend if available
            if (!token.isNullOrBlank() && !fcmToken.isNullOrBlank()) {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    try {
                        val db = com.example.kotlinroomdatabase.data.StudentDatabase.getInstance(context)
                        val repo = com.example.kotlinroomdatabase.repository.StudentRepositoryHTTPS(context, db.studentDao())
                        repo.deleteDeviceToken(fcmToken)
                    } catch (e: Exception) {
                        Log.w("JwtUtils", "Failed to revoke device token on backend: ${e.message}")
                    }
                }
            }

            // 2. Clear all authentication and user preferences
            context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE).edit().clear().apply()
            context.getSharedPreferences("student_prefs", Context.MODE_PRIVATE).edit().clear().apply()
            context.getSharedPreferences("student_attendance_state_prefs", Context.MODE_PRIVATE).edit().clear().apply()

            // 3. Clear quick login credentials (KA-08)
            com.example.kotlinroomdatabase.crypto.BiometricAuthManager.clearCredentials(context)

            // 4. Clear TOTP secret shared prefs (KA-08)
            try {
                context.getSharedPreferences("secret_shared_prefs", Context.MODE_PRIVATE).edit().clear().apply()
            } catch (e: Exception) {
                Log.e("JwtUtils", "Error clearing secret_shared_prefs", e)
            }

            // 5. Clear Room database in background (KA-08)
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    val db = com.example.kotlinroomdatabase.data.StudentDatabase.getInstance(context)
                    val dao = db.studentDao()
                    dao.deleteAllStudents()
                    dao.clearScheduleCache()
                    dao.clearAllOfflineGradeActions()
                    dao.deleteAllLessons()
                } catch (e: Exception) {
                    Log.e("JwtUtils", "Error clearing Room DB on logout", e)
                }
            }

            // 6. Clear local avatar and media cache files (KA-08)
            try {
                com.example.kotlinroomdatabase.util.AvatarManager.getCachedAvatarFile(context).delete()
                val avatarCacheDir = java.io.File(context.cacheDir, "avatars")
                if (avatarCacheDir.exists()) avatarCacheDir.deleteRecursively()
            } catch (e: Exception) {
                Log.e("JwtUtils", "Error deleting avatar cache files", e)
            }
        } catch (e: Exception) {
            Log.e("JwtUtils", "Error clearing session data", e)
        }
    }
}
