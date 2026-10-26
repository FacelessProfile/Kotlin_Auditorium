package com.example.kotlinroomdatabase

import com.example.kotlinroomdatabase.model.*
import com.example.kotlinroomdatabase.repository.AnalyticsPayloadParser
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AnalyticsTest {
    @Test fun fractionalStudentScoresAndAttendanceUseSeparateMetrics() {
        val snapshot = AnalyticsPayloadParser.parse(JSONObject("""{
            "subjects": [{"subject_id":1,"subject_name":"Математика","current_score":7.5,"total_max":12.5,
            "percent":60,"total_sessions":10,"attended_sessions":9,"attendance_percent":90}],
            "semester":{"name":"Осенний семестр"}
        }"""), false)
        val entry = snapshot.entries.single()
        assertEquals(60.0, entry.rating!!, 0.001)
        assertEquals(90.0, entry.attendance!!, 0.001)
        assertEquals(7.5, entry.score!!, 0.001)
        assertEquals("Осенний семестр", snapshot.period)
    }

    @Test fun noSessionsOrGradeItemsAreMissingRatherThanZero() {
        val entry = AnalyticsPayloadParser.parse(JSONObject("""{"subjects":[{
            "subject_id":1,"subject_name":"Физика","current_score":0,"total_max":0,
            "percent":0,"total_sessions":0,"attended_sessions":0,"attendance_percent":0
        }]}"""), false).entries.single()
        assertNull(entry.rating)
        assertNull(entry.attendance)
        assertEquals("—", AnalyticsMath.format(entry.value(AnalyticsMetric.RATING)))
    }

    @Test fun teacherAttendanceComesFromSessionsAndRatingFromScores() {
        val entry = AnalyticsPayloadParser.parse(JSONObject("""{"students":[{
            "student_id":7,"student_name":"Анна","total_sessions":8,"attended_sessions":6,
            "current_score":22.5,"total_max":25
        }]}"""), true).entries.single()
        assertEquals(90.0, entry.rating!!, 0.001)
        assertEquals(75.0, entry.attendance!!, 0.001)
    }

    @Test fun measuredZeroIsPreservedButMissingValuesSortLastInBothDirections() {
        val rows = listOf(
            AnalyticsEntry(1, "Нет данных", null, null),
            AnalyticsEntry(2, "Нулевой результат", 0.0, 20.0),
            AnalyticsEntry(3, "Высокий результат", 90.0, 80.0)
        )
        assertEquals(listOf(2, 3, 1), AnalyticsMath.sorted(rows, AnalyticsMetric.RATING, AnalyticsOrder.LOWEST).map { it.id })
        assertEquals(listOf(3, 2, 1), AnalyticsMath.sorted(rows, AnalyticsMetric.RATING, AnalyticsOrder.HIGHEST).map { it.id })
        assertEquals(45.0, AnalyticsMath.average(rows, AnalyticsMetric.RATING)!!, 0.001)
    }

    @Test fun invalidOrUnboundedNumbersNeverLeakIntoCharts() {
        assertNull(AnalyticsMath.percent(1.0, 0.0))
        assertNull(AnalyticsMath.percent(Double.NaN, 20.0))
        assertNull(AnalyticsMath.percent(1.0, Double.POSITIVE_INFINITY))
        assertEquals(100.0, AnalyticsMath.percent(120.0, 100.0)!!, 0.001)
        assertEquals(0.0, AnalyticsMath.percent(-5.0, 100.0)!!, 0.001)
        assertNull(AnalyticsEntry(1, "Нет", Double.NaN, null).value(AnalyticsMetric.RATING))
    }

    @Test fun emptySemesterAndNullScoresRemainUsable() {
        assertTrue(AnalyticsPayloadParser.parse(JSONObject("""{"subjects":[]}"""), false).entries.isEmpty())
        val entry = AnalyticsPayloadParser.parse(JSONObject("""{"subjects":[{
            "subject_id":1,"subject_name":"Предмет","current_score":null,"total_max":null,
            "total_sessions":2,"attended_sessions":0,"attendance_percent":null
        }]}"""), false).entries.single()
        assertNull(entry.rating)
        assertEquals(0.0, entry.attendance!!, 0.001)
    }
}
