package com.example.kotlinroomdatabase.util

import com.example.kotlinroomdatabase.model.HistoryItem
import java.time.LocalDate
import java.time.ZoneId

object AttendanceAppealRules {
    val requestedStatuses = listOf("present", "late", "excused")
    private val entryPattern = Regex("(session|journal):[1-9][0-9]*")

    fun canView(role: String?) = RoleUtils.normalizeRole(role) in setOf("student", "teacher", "head", "admin")
    fun canReview(role: String?) = RoleUtils.normalizeRole(role) in setOf("teacher", "head", "admin")

    fun canCreate(item: HistoryItem, today: LocalDate = LocalDate.now(ZoneId.of("Asia/Novosibirsk"))): Boolean {
        val date = runCatching { LocalDate.parse(item.date.take(10)) }.getOrNull() ?: return false
        return entryPattern.matches(item.entry_id) && item.status != "pending" && !date.isAfter(today)
    }

    fun validReason(text: String): Boolean = text.trim().let { it.codePointCount(0, it.length) in 10..4000 }
    fun validNote(text: String): Boolean = text.trim().let { it.codePointCount(0, it.length) in 3..4000 }

    fun markLabel(status: String?): String = when (status) {
        "present" -> "Посещение"
        "late" -> "Опоздание"
        "excused" -> "Уважительная причина"
        "absent" -> "Пропуск"
        "fraud" -> "Антифрод"
        "pending" -> "Отметка ещё открыта"
        else -> "Нет данных"
    }

    fun statusLabel(status: String): String = when (status) {
        "approved" -> "Одобрено"
        "rejected" -> "Отклонено"
        "created" -> "Обращение отправлено"
        else -> "Ожидает решения"
    }

    fun errorMessage(error: String, code: Int = 0): String = when (error) {
        "an appeal for this lesson already exists" -> "Обращение по этому занятию уже отправлено. Проверьте раздел «Обращения»."
        "wait until the lesson has ended" -> "Подать обращение можно после завершения занятия и закрытия отметки."
        "attendance already has the requested status" -> "У занятия уже установлена запрашиваемая отметка. Обновите историю."
        "reason must contain 10 to 4000 characters" -> "Причина должна содержать от 10 до 4000 символов."
        "decision note must contain 3 to 4000 characters" -> "Пояснение должно содержать от 3 до 4000 символов."
        "appeal has already been resolved" -> "По обращению уже принято решение. Обновите список."
        "attendance changed since the appeal; review the current mark first" -> "Отметка изменилась. Закройте карточку, обновите список и проверьте текущую отметку перед решением."
        "lesson assignment changed; appeal cannot be applied" -> "Изменилось назначение занятия. Обратитесь к администратору."
        "attendance entry not found", "appeal not found or forbidden" -> "Запись недоступна. Обновите список и проверьте активную роль."
        else -> when (code) {
            401 -> "Сессия истекла. Войдите снова."
            403 -> "Обращения недоступны для активной роли."
            404 -> "Раздел обращений пока недоступен на сервере."
            429 -> "Слишком много запросов. Повторите попытку позже."
            else -> "Не удалось выполнить запрос. Проверьте соединение и повторите попытку."
        }
    }
}
