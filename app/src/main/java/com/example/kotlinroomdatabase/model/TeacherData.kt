package com.example.kotlinroomdatabase.model

import kotlinx.serialization.Serializable

@Serializable
data class TeacherSubjectsResponse(
    val subjects: List<TeacherSubject>
)

@Serializable
data class TeacherSubject(
    val subject_id: Int,
    val subject_name: String,
    val groups: List<TeacherGroup>
)

@Serializable
data class TeacherGroup(
    val id: Int,
    val name: String
)

@Serializable
data class GroupAttendanceResponse(
    val students: List<StudentAttendanceStats>
)

@Serializable
data class StudentAttendanceStats(
    val student_id: Int,
    val student_name: String,
    val attendance_percent: Double,
    val attended_sessions: Int,
    val total_sessions: Int,
    val last_marked_at: String? = null
)

@Serializable
data class TeacherAttendanceRosterResult(
    val lesson_id: Int = 0,
    val lesson_name: String = "",
    val subject_id: Int = 0,
    val server_time: String = "",
    val timezone: String = "Asia/Novosibirsk",
    val roster_size: Int = 0,
    val marked_count: Int = 0,
    val attendance_percent: Double = 0.0,
    val students: List<AttendanceRosterStudent> = emptyList()
)

@Serializable
data class AttendanceRosterStudent(
    val student_id: Int = 0,
    val student_name: String = "",
    val group_id: Int = 0,
    val group_name: String = "",
    val status: String = "absent",
    val marked_by: String = "",
    val marked_at: String? = null,
    val is_fraud: Boolean = false,
    val fraud_reason: String = ""
)
