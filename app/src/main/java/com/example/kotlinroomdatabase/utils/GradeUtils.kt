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

    fun setupNoFilterAdapter(
        autoCompleteTextView: com.google.android.material.textfield.MaterialAutoCompleteTextView,
        context: android.content.Context,
        items: List<String>,
        onItemSelected: (Int, String) -> Unit
    ) {
        val adapter = object : android.widget.ArrayAdapter<String>(context, android.R.layout.simple_dropdown_item_1line, items) {
            private val noFilter = object : android.widget.Filter() {
                override fun performFiltering(constraint: CharSequence?): android.widget.Filter.FilterResults {
                    return android.widget.Filter.FilterResults().apply {
                        values = items
                        count = items.size
                    }
                }
                override fun publishResults(constraint: CharSequence?, results: android.widget.Filter.FilterResults?) {
                    notifyDataSetChanged()
                }
            }
            override fun getFilter(): android.widget.Filter = noFilter
        }
        autoCompleteTextView.setAdapter(adapter)
        autoCompleteTextView.setOnItemClickListener { _, _, position, _ ->
            if (position in items.indices) {
                onItemSelected(position, items[position])
            }
        }
    }
}
