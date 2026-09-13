package com.example.kotlinroomdatabase.model

import kotlinx.serialization.Serializable

@Serializable
data class DevSprint(
    val sprint_id: Int = 0,
    val name: String = "",
    val starts_at: String = "",
    val ends_at: String = "",
    val status: String = "active",
    val created_at: String = "",
    val stats: SprintStats? = null
)

@Serializable
data class SprintStats(
    val total_tasks: Int = 0,
    val done_tasks: Int = 0,
    val total_bugs: Int = 0,
    val done_bugs: Int = 0,
    val completion_rate: Int = 0
)

@Serializable
data class DevTask(
    val task_id: Int = 0,
    val item_type: String = "task", // "task" or "bug"
    val title: String = "",
    val description: String = "",
    val status: String = "todo", // "todo" or "done"
    val priority: String = "medium", // "low", "medium", "high", "critical"
    val sprint_id: Int? = null,
    val sprint_name: String = "",
    val assignee_id: Int? = null,
    val assignee_name: String = "",
    val assignee_avatar: String = "",
    val creator_id: Int? = null,
    val creator_name: String = "",
    val comments_count: Int = 0,
    val closed_at: String? = null,
    val created_at: String = "",
    val updated_at: String = "",
    val is_following: Boolean = true
)

@Serializable
data class DevComment(
    val comment_id: Int = 0,
    val task_id: Int = 0,
    val author_id: Int = 0,
    val author_name: String = "",
    val author_avatar: String = "",
    val content: String = "",
    val created_at: String = ""
)

@Serializable
data class DevTeamMember(
    val user_id: Int = 0,
    val login: String = "",
    val name: String = "",
    val avatar_url: String = ""
)

@Serializable
data class MemberSprintActivity(
    val user: DevTeamMember,
    val completed_tasks: List<DevTask> = emptyList(),
    val closed_bugs: List<DevTask> = emptyList()
)

@Serializable
data class DevSprintReport(
    val sprint: DevSprint,
    val members: List<MemberSprintActivity> = emptyList(),
    val velocity: Int = 0
)

@Serializable
data class CreateDevTaskPayload(
    val item_type: String,
    val title: String,
    val description: String,
    val priority: String,
    val sprint_id: Int? = null,
    val assignee_id: Int? = null
)
