package com.example.kotlinroomdatabase.util

object RoleUtils {

    const val ROLE_STUDENT = "student"
    const val ROLE_TEACHER = "teacher"
    const val ROLE_HEAD = "head"
    const val ROLE_ADMIN = "admin"
    const val ROLE_DEAN = "dean"
    const val ROLE_SECRETARY = "secretary"
    const val ROLE_PROGRAM_CREATOR = "program_creator"
    const val ROLE_DIRECTOR = "director"
    const val ROLE_MINISTER = "minister"

    val STAFF_ROLES = setOf(
        ROLE_ADMIN,
        ROLE_HEAD,
        ROLE_DEAN,
        ROLE_SECRETARY,
        ROLE_PROGRAM_CREATOR,
        ROLE_DIRECTOR,
        ROLE_MINISTER
    )

    fun normalizeRole(role: String?): String {
        return role?.trim()?.lowercase()?.ifBlank { ROLE_STUDENT } ?: ROLE_STUDENT
    }

    fun getRoleLabel(role: String?): String {
        return when (normalizeRole(role)) {
            ROLE_ADMIN -> "Администратор"
            ROLE_DEAN -> "Декан"
            ROLE_HEAD -> "Зав. кафедрой"
            ROLE_SECRETARY -> "Секретарь"
            ROLE_PROGRAM_CREATOR -> "Руководитель программы"
            ROLE_DIRECTOR -> "Директор института"
            ROLE_MINISTER -> "Министр образования"
            ROLE_TEACHER -> "Преподаватель"
            ROLE_STUDENT -> "Студент"
            else -> "Пользователь"
        }
    }

    fun getRoleHeroTitle(role: String?): String {
        return when (normalizeRole(role)) {
            ROLE_TEACHER -> "Рабочая панель преподавателя"
            ROLE_ADMIN -> "Панель администратора"
            ROLE_HEAD -> "Панель заведующего кафедрой"
            ROLE_DEAN -> "Панель: Декан"
            ROLE_SECRETARY -> "Панель: Секретарь"
            ROLE_PROGRAM_CREATOR -> "Панель: Руководитель программы"
            ROLE_DIRECTOR -> "Панель: Директор института"
            ROLE_MINISTER -> "Панель: Министр образования"
            else -> "Личный кабинет студента"
        }
    }

    /**
     * Formats full name into compact Russian representation:
     * "Петров Александр Романович" -> "Петров А.Р."
     * "Иванов Иван" -> "Иванов И."
     */
    fun formatShortName(fullName: String?): String {
        if (fullName.isNullOrBlank()) return ""
        val parts = fullName.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
        return when {
            parts.size >= 3 -> {
                val last = parts[0]
                val firstInitial = parts[1].firstOrNull()?.uppercaseChar()?.toString() ?: ""
                val middleInitial = parts[2].firstOrNull()?.uppercaseChar()?.toString() ?: ""
                if (firstInitial.isNotBlank() && middleInitial.isNotBlank()) {
                    "$last $firstInitial.$middleInitial."
                } else if (firstInitial.isNotBlank()) {
                    "$last $firstInitial."
                } else {
                    last
                }
            }
            parts.size == 2 -> {
                val last = parts[0]
                val firstInitial = parts[1].firstOrNull()?.uppercaseChar()?.toString() ?: ""
                if (firstInitial.isNotBlank()) "$last $firstInitial." else last
            }
            parts.isNotEmpty() -> parts[0]
            else -> ""
        }
    }

    /**
     * Returns true if the role is allowed to view schedule.
     */
    fun canViewSchedule(role: String?): Boolean {
        val norm = normalizeRole(role)
        return norm == ROLE_STUDENT || norm == ROLE_TEACHER || norm == ROLE_HEAD
    }

    /**
     * Returns true if user acts as teacher/instructor (teacher or head).
     */
    fun isTeacherOrHead(role: String?): Boolean {
        val norm = normalizeRole(role)
        return norm == ROLE_TEACHER || norm == ROLE_HEAD
    }
}
