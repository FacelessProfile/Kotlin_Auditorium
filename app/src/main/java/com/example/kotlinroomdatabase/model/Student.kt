package com.example.kotlinroomdatabase.model

import java.util.regex.Pattern

class Student(
    val id: Int = 0,
    val studentName: String,
    val studentGroup: String,
    val attendance: Boolean, // посещение был/нет
    // val lastModified: String = "" функционал на будущее
) {

    fun getName(): String {
        return this.studentName
    }

    fun getId(): Int {
        return this.id
    }

    companion object {
        private const val NAME_REGEX = "^[А-Яа-яA-Za-z\\s]{2,50}$" //Ян Ё граничный случай
        private const val GROUP_REGEX = "^(ИКС|ИА)-\\d{1,3}$"

        fun validateName(name: String): Boolean {
            return name.isNotBlank() && Pattern.matches(NAME_REGEX, name)
        }

        fun validateGroup(group: String): Boolean {
            return group.isNotBlank() && Pattern.matches(GROUP_REGEX, group)
        }
    }

    fun isValid(): Boolean {
        return validateName(studentName) && validateGroup(studentGroup)
    }

    fun studentToMap(): Map<String, Any> {
        return mapOf(
            "id" to id,
            "studentName" to studentName,
            "studentGroup" to studentGroup,
            "attendance" to attendance,
            //"lastModified" to lastModified
        )
    }
    fun studentToString(): String {
        return "Student(id=$id, name='$studentName', group='$studentGroup', attendance=$attendance)" // , modified='$lastModified')"
    }
}