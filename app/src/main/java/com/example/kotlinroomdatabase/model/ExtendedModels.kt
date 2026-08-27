package com.example.kotlinroomdatabase.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UserAgreementStatus(
    val agreement: String = "user_agreement",
    val version: String = "",
    val decision: String = "",
    val accepted: Boolean = false,
    val decided_at: String? = null
)

@Serializable
data class UserNotification(
    @SerialName("notification_id") val notification_id: Long = 0,
    val id: Long = 0,
    val user_id: Int = 0,
    val title: String = "",
    val message: String = "",
    val category: String = "",
    val event_type: String = "",
    val type: String = "info",
    val is_read: Boolean = false,
    val created_at: String = ""
) {
    val realId: Long
        get() = if (notification_id != 0L) notification_id else id
}

@Serializable
data class UserNotificationsResponse(
    val notifications: List<UserNotification> = emptyList()
)

@Serializable
data class UnreadCountResult(
    val unread_count: Int = 0
)

@Serializable
data class SemesterInfo(
    @SerialName("semester_id") val semester_id: Int = 0,
    @SerialName("id") private val _id: Int = 0,
    val name: String = "",
    val status: String = "open",
    val start_date: String? = null,
    val end_date: String? = null,
    val starts_at: String? = null,
    val ends_at: String? = null,
    val is_current: Boolean = false
) {
    val id: Int
        get() = if (semester_id != 0) semester_id else _id
}

@Serializable
data class SemestersResponse(
    val semesters: List<SemesterInfo> = emptyList()
)

@Serializable
data class ActiveStudentLessonInfo(
    val is_active: Boolean = false,
    val session_id: Int = 0,
    val subject_id: Int = 0,
    val lesson_name: String = "",
    val subject_name: String = "",
    val expires_at: String = "",
    val marked_at: String = ""
)

@Serializable
data class AttachmentInfo(
    val id: Long = 0,
    val filename: String = "",
    val mime_type: String = "",
    val size_bytes: Long = 0,
    val url: String = ""
)

@Serializable
data class ActiveSessionInfo(
    val id: Int = 0,
    val lessonId: Int = 0,
    val subjectId: Int = 0,
    val subjectName: String = "",
    val groupIds: List<Int> = emptyList(),
    val groupNames: List<String> = emptyList(),
    val createdAt: String = "",
    val expiresAt: String = "",
    val remainingSeconds: Int = 0,
    val markedCount: Int = 0,
    val rosterSize: Int = 0,
    val isActive: Boolean = false
)


