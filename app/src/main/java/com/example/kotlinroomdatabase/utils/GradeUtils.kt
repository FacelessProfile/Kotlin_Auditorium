package com.example.kotlinroomdatabase.utils

import android.graphics.Color
import android.widget.TextView

object GradeUtils {

    fun getGradeColor(percent: Int): Int {
        return when {
            percent >= 80 -> Color.parseColor("#4CAF50") // Green (Excellent)
            percent >= 70 -> Color.parseColor("#FFC107") // Yellow (Good)
            percent >= 50 -> Color.parseColor("#FF9800") // Orange (OK)
            else -> Color.parseColor("#F44336") // Red (Alarm/Dead)
        }
    }

    fun applyColorToTextView(textView: TextView, percent: Int) {
        textView.setTextColor(getGradeColor(percent))
    }

    fun translateItemType(type: String): String {
        return when (type.lowercase()) {
            "assignment" -> "Домашнее задание"
            "exam" -> "Экзамен/Зачёт"
            "quiz" -> "Тест"
            "attendance" -> "Посещаемость"
            else -> type
        }
    }
}
