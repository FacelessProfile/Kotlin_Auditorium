package com.example.kotlinroomdatabase.repository

import com.example.kotlinroomdatabase.model.AnalyticsEntry
import com.example.kotlinroomdatabase.model.AnalyticsMath
import com.example.kotlinroomdatabase.model.AnalyticsSnapshot
import org.json.JSONObject

/** The API sends fractional scores and null attendance for subjects without sessions. */
object AnalyticsPayloadParser {
    fun parse(result: JSONObject, teacher: Boolean): AnalyticsSnapshot {
        val rows = result.getJSONArray(if (teacher) "students" else "subjects")
        val entries = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val score = row.number("current_score")
            val maximum = row.number("total_max")
            val sessions = row.number("total_sessions")?.toInt()
            val attended = row.number("attended_sessions")?.toInt()
            AnalyticsEntry(
                id = row.getInt(if (teacher) "student_id" else "subject_id"),
                name = row.getString(if (teacher) "student_name" else "subject_name"),
                rating = AnalyticsMath.percent(score, maximum),
                attendance = if (sessions != null && sessions > 0) {
                    row.number("attendance_percent")?.coerceIn(0.0, 100.0)
                        ?: AnalyticsMath.percent(attended?.toDouble(), sessions.toDouble())
                } else null,
                score = score, maximum = maximum, attended = attended, sessions = sessions
            )
        }
        val semester = result.optJSONObject("semester")
        val period = semester?.optString("name")?.takeIf { it.isNotBlank() }
            ?: "Текущий семестр"
        return AnalyticsSnapshot(entries, period)
    }

    private fun JSONObject.number(key: String): Double? =
        if (!has(key) || isNull(key)) null else optDouble(key, Double.NaN).takeIf { it.isFinite() }
}
