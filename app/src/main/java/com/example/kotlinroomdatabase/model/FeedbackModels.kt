package com.example.kotlinroomdatabase.model

import java.io.File

data class FeedbackTicket(
    val ticketId: Int,
    val title: String,
    val description: String,
    val category: String,
    val priority: String,
    val status: String,
    val attachments: List<String> = emptyList(),
    val createdAt: String = "",
    val updatedAt: String = ""
) {
    val statusLabel: String
        get() = when (status.lowercase()) {
            "open" -> "Открыт"
            "in_progress" -> "В работе"
            "resolved" -> "Решён"
            "closed" -> "Закрыт"
            else -> status
        }

    val categoryLabel: String
        get() = when (category.lowercase()) {
            "bug" -> "Ошибка / Баг"
            "feature" -> "Предложение"
            "access" -> "Доступ и 2FA"
            "question" -> "Вопрос"
            else -> "Другое"
        }
}

data class FeedbackComment(
    val commentId: Int,
    val ticketId: Int,
    val authorId: Int,
    val authorName: String,
    val authorRole: String,
    val authorAvatar: String? = null,
    val content: String,
    val isInternal: Boolean = false,
    val attachments: List<String> = emptyList(),
    val createdAt: String = ""
)

data class FeedbackTicketDetails(
    val ticket: FeedbackTicket,
    val comments: List<FeedbackComment> = emptyList()
)

data class FeedbackAttachmentItem(
    val id: String,
    val file: File,
    var remoteUrl: String? = null,
    var isUploading: Boolean = false,
    var error: String? = null
)
