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
