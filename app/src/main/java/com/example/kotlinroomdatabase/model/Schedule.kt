package com.example.kotlinroomdatabase.model

import kotlinx.serialization.Serializable

@Serializable
data class DayScheduleResponse(
    val id: String = "",
    val ok: Boolean = false,
    val result: DayScheduleResult? = null,
    val error: String = ""
)

@Serializable
data class DayScheduleResult(
    val date: String = "",
    val weekday: String = "",
    val day_idx: Int = 0,
    val week_type: Int = 1,
    val teacher_id: Int? = null,
    val lessons: List<LessonScheduleItem> = emptyList()
)

@Serializable
data class LessonScheduleItem(
    val lesson_num: Int = 0,
    val start_time: String = "",
    val end_time: String = "",
    val subject_id: Int = 0,
    val subject_name: String = "",
    val teacher_name: String? = null,
    val group_name: String? = null,
    val group_id: Int? = null,
    val lesson_type: String = "Практика",
    val room_info: String = "",
    val subgroup: String = "",
    val subgroup_id: Int? = null,
    val is_other_subgroup: Boolean = false,
    val is_current_subgroup: Boolean = true,
    val is_window: Boolean = false
)

data class ScheduleSlot(
    val lessonNum: Int,
    val startTime: String,
    val endTime: String,
    val lessons: List<LessonScheduleItem>,
    val isWindow: Boolean = false
)
