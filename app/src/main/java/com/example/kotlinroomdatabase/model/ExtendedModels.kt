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
    val id: Int = 0,
    val name: String = "",
    val status: String = "open",
    val start_date: String? = null,
    val end_date: String? = null,
    val is_current: Boolean = false
)

@Serializable
data class SemestersListResponse(
    val semesters: List<SemesterInfo> = emptyList()
)

@Serializable
data class AttachmentInfo(
    val id: Long = 0,
    val filename: String = "",
    val mime_type: String = "",
    val size_bytes: Long = 0,
    val url: String = ""
)

