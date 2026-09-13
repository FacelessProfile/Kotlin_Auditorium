package com.example.kotlinroomdatabase.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SubjectSubgroup(
    @SerialName("subgroup_id") val subgroup_id: Int,
    val code: String = "",
    val name: String = "",
    val capacity: Int? = null,
    val occupied: Int = 0,
    val is_current: Boolean = false
) {
    val displayName: String
        get() = if (name.isNotBlank()) name else "Подгруппа $code"

    val capacityInfo: String
        get() = if (capacity != null && capacity > 0) "$occupied/$capacity" else "$occupied чел."
}

@Serializable
data class SubjectWithSubgroups(
    @SerialName("subject_id") val subject_id: Int,
    @SerialName("subject_name") val subject_name: String = "",
    @SerialName("group_id") val group_id: Int = 0,
    @SerialName("group_name") val group_name: String = "",
    @SerialName("current_subgroup_id") val current_subgroup_id: Int? = null,
    val subgroups: List<SubjectSubgroup> = emptyList()
) {
    val currentSubgroup: SubjectSubgroup?
        get() = subgroups.firstOrNull { it.subgroup_id == current_subgroup_id || it.is_current }
}

@Serializable
data class StudentSubgroupsResponse(
    val student_id: Int = 0,
    val semester_id: Int = 0,
    val subjects: List<SubjectWithSubgroups> = emptyList()
)

@Serializable
data class ChangeSubgroupRequest(
    val reason: String? = null
)
