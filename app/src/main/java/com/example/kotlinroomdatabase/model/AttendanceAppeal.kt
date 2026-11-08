package com.example.kotlinroomdatabase.model

import kotlinx.serialization.Serializable

@Serializable
data class AttendanceAppealsPage(
    val items: List<AttendanceAppeal> = emptyList(),
    val page: Int = 1,
    val has_more: Boolean = false
)

@Serializable
data class AttendanceAppeal(
    val appeal_id: Long,
    val entry_id: String,
    val subject_name: String = "",
    val student_name: String = "",
    val lesson_date: String,
    val original_status: String,
    val requested_status: String,
    val current_status: String? = null,
    val status: String,
    val reason: String,
    val decision_note: String? = null,
    val created_at: String = "",
    val events: List<AttendanceAppealEvent> = emptyList()
)

@Serializable
data class AttendanceAppealEvent(val action: String, val note: String = "", val created_at: String = "")

@Serializable
data class AttendanceAppealResult(val appeal_id: Long, val status: String)
