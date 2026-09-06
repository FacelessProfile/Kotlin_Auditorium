package com.example.kotlinroomdatabase.model

import kotlinx.serialization.Serializable

@Serializable
data class UserProfile(
    val user_id: Int = 0,
    val login: String = "",
    val name: String = "",
    val student_name: String = "",
    val teacher_name: String = "",
    val role: String = "student",
    val active_role: String = "",
    val primary_role: String = "",
    val roles: List<String> = emptyList(),
    val email: String = "",
    val avatar: String = "",
    val group_id: Int? = null,
    val group_name: String = "",
    val group: String = "",
    val lectern_id: Int? = null,
    val job_title: String = "",
    val nfc_tag: String = "",
    val total_cheat_attempts: Int = 0
) {
    val effectiveDisplayName: String
        get() = when {
            teacher_name.isNotBlank() -> teacher_name
            name.isNotBlank() -> name
            student_name.isNotBlank() -> student_name
            login.isNotBlank() -> login
            else -> "Пользователь"
        }

    val effectiveRole: String
        get() = active_role.ifBlank { role.ifBlank { primary_role } }.trim().lowercase().ifBlank { "student" }

    val effectiveGroupOrDepartment: String
        get() = when {
            group_name.isNotBlank() -> group_name
            group.isNotBlank() -> group
            job_title.isNotBlank() -> job_title
            else -> ""
        }

    val availableRoles: List<String>
        get() = if (roles.isNotEmpty()) roles else listOf(effectiveRole)
}

@Serializable
data class SwitchRoleResult(
    val token: String = "",
    val role: String = "",
    val active_role: String = "",
    val primary_role: String = "",
    val roles: List<String> = emptyList(),
    val expires_at: String = ""
)
