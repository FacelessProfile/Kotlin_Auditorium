package com.example.kotlinroomdatabase.util

import android.content.Context
import android.util.Base64
import android.util.Log
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
     * Clears all session and attendance preferences on logout.
     */
    fun clearAllSessionData(context: Context) {
        try {
            context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE).edit().clear().apply()
            context.getSharedPreferences("student_prefs", Context.MODE_PRIVATE).edit().clear().apply()
            context.getSharedPreferences("student_attendance_state_prefs", Context.MODE_PRIVATE).edit().clear().apply()
        } catch (e: Exception) {
            Log.e("JwtUtils", "Error clearing session data", e)
        }
    }
}
