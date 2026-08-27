package com.example.kotlinroomdatabase.model

import kotlinx.serialization.Serializable

@Serializable
data class AttendanceHistoryResponse(
    val items: List<HistoryItem>,
    val year: Int
)

@Serializable
data class HistoryItem(
    val date: String,
    val count: Int? = null,
    val lesson_name: String? = null,
    val subject_name: String? = null,
    val lesson_type: String? = null,
    val time: String? = null,
    val status: String? = null,
    val is_late: Boolean = false
)
