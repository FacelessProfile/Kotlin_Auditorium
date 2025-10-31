package com.example.kotlinroomdatabase.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable

@InternalSerializationApi
@Serializable
@Entity(tableName = "student")
data class Student(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val studentNFC: String = "", // NFC (Device/tag) ID
    val studentName: String,
    val studentGroup: String,
    val attendance: Boolean,
) {
    companion object {
        // validation patterns for formalized data
        private val FORMALIZED_NAME_REGEX = "^[А-ЯЁ][а-яё]+\\s+[А-ЯЁ][а-яё]+\\s+[А-ЯЁ][а-яё]+\$".toRegex()
        private val FORMALIZED_GROUP_REGEX = "^(ИКС|ИА)-\\d{1,3}\$".toRegex()

        fun validateName(name: String): Boolean {
            return name.isNotBlank() && FORMALIZED_NAME_REGEX.matches(name)
        }

        fun validateGroup(group: String): Boolean {
            return group.isNotBlank() && FORMALIZED_GROUP_REGEX.matches(group)
        }

        // Group formalized from any input (ex group xxx -> group-xxx)
        fun formalizeGroup(group: String): String {
            val trimmed = group.trim()
            // getting prefix and numbers
            val prefixMatch = "(?i)(ИКС|ИА)".toRegex().find(trimmed)
            val numberMatch = "\\d{1,3}".toRegex().find(trimmed)

            return if (prefixMatch != null && numberMatch != null) {
                val prefix = prefixMatch.value.uppercase()
                val number = numberMatch.value
                "$prefix-$number"
            } else {
                trimmed
            }
        }

        // FIO each starts with capital letter (ex firstname lastname middlename -> Firstname Lastname Middlename)
        fun formalizeName(name: String): String {
            val trimmed = name.trim()
            return trimmed.split("\\s+".toRegex())
                .filter { it.isNotBlank() }
                .joinToString(" ") { word ->
                    word.replaceFirstChar {
                        if (it.isLowerCase()) it.titlecase() else it.toString()
                    }
                }
        }
    }

    fun isValid(): Boolean {
        return validateName(studentName) && validateGroup(studentGroup)
    }

    fun studentToMap(): Map<String, Any> {
        return mapOf(
            "id" to id,
            "studentNFC" to studentNFC,
            "studentName" to studentName,
            "studentGroup" to studentGroup,
            "attendance" to attendance
        )
    }

    fun studentToString(): String {
        return "Student(id=$id, nfc='$studentNFC', name='$studentName', group='$studentGroup', attendance=$attendance)"
    }

    fun formalized(): Student {
        return this.copy(
            studentName = formalizeName(studentName),
            studentGroup = formalizeGroup(studentGroup)
        )
    }
}