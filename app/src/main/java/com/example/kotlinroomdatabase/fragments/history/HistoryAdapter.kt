package com.example.kotlinroomdatabase.fragments.history

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.model.HistoryItem
import java.text.SimpleDateFormat
import java.util.Locale

class HistoryAdapter : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    private var items = listOf<HistoryItem>()

    fun setData(newItems: List<HistoryItem>) {
        this.items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.history_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context

        // 1. Day Header logic: show header if first item or if date changed from previous item
        val currentDate = item.date
        val previousDate = if (position > 0) items[position - 1].date else null

        if (position == 0 || currentDate != previousDate) {
            holder.layoutDateHeader.visibility = View.VISIBLE
            holder.tvDateGroup.text = formatDisplayDate(currentDate)
        } else {
            holder.layoutDateHeader.visibility = View.GONE
        }

        // 2. Subject & Topic
        val subject = if (!item.subject_name.isNullOrBlank()) item.subject_name else item.lesson_name ?: "Занятие"
        val topic = if (!item.lesson_name.isNullOrBlank() && item.lesson_name != item.subject_name) {
            "Тема: ${item.lesson_name}"
        } else {
            "Тема: Основной курс дисциплины"
        }

        holder.tvSubjectName.text = subject
        holder.tvLessonTopic.text = topic

        // 3. Lesson Type & Time
        val inferredType = when {
            item.lesson_name?.contains("Факульт", ignoreCase = true) == true -> "Факультатив"
            item.lesson_name?.contains("Лаб", ignoreCase = true) == true -> "Лабораторная работа"
            item.lesson_name?.contains("Лекци", ignoreCase = true) == true -> "Лекция"
            item.lesson_name?.contains("Практ", ignoreCase = true) == true -> "Практика"
            !item.lesson_type.isNullOrBlank() -> item.lesson_type
            else -> "Практика"
        }
        holder.tvLessonType.text = inferredType
        holder.tvTimeSlot.text = item.time ?: "09:00 - 10:35"

        // 4. Status Indicator (On-Time vs Late)
        val isLate = item.is_late || item.status == "late"
        if (isLate) {
            holder.viewStatusStrip.setBackgroundColor(ContextCompat.getColor(context, R.color.badge_late_icon))
            holder.layoutStatusBadge.setBackgroundResource(R.drawable.bg_badge_late)
            holder.ivStatusIcon.setImageResource(R.drawable.ic_schedule_clock)
            holder.ivStatusIcon.setColorFilter(ContextCompat.getColor(context, R.color.badge_late_icon))
            holder.tvStatusText.text = "Опоздание"
            holder.tvStatusText.setTextColor(ContextCompat.getColor(context, R.color.badge_late_text))
        } else {
            holder.viewStatusStrip.setBackgroundColor(ContextCompat.getColor(context, R.color.badge_ontime_icon))
            holder.layoutStatusBadge.setBackgroundResource(R.drawable.bg_badge_ontime)
            holder.ivStatusIcon.setImageResource(R.drawable.ic_check)
            holder.ivStatusIcon.setColorFilter(ContextCompat.getColor(context, R.color.badge_ontime_icon))
            holder.tvStatusText.text = "Вовремя"
            holder.tvStatusText.setTextColor(ContextCompat.getColor(context, R.color.badge_ontime_text))
        }
    }

    override fun getItemCount() = items.size

    private fun formatDisplayDate(dateStr: String): String {
        return try {
            val inFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val date = inFormat.parse(dateStr) ?: return dateStr
            val outFormat = SimpleDateFormat("d MMMM yyyy (EEEE)", Locale("ru"))
            outFormat.format(date).replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale("ru")) else it.toString() }
        } catch (e: Exception) {
            dateStr
        }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val layoutDateHeader: View = view.findViewById(R.id.layoutDateHeader)
        val tvDateGroup: TextView = view.findViewById(R.id.tvDateGroup)
        val viewStatusStrip: View = view.findViewById(R.id.viewStatusStrip)
        val tvSubjectName: TextView = view.findViewById(R.id.tvSubjectName)
        val layoutStatusBadge: View = view.findViewById(R.id.layoutStatusBadge)
        val ivStatusIcon: ImageView = view.findViewById(R.id.ivStatusIcon)
        val tvStatusText: TextView = view.findViewById(R.id.tvStatusText)
        val tvLessonTopic: TextView = view.findViewById(R.id.tvLessonTopic)
        val tvLessonType: TextView = view.findViewById(R.id.tvLessonType)
        val tvTimeSlot: TextView = view.findViewById(R.id.tvTimeSlot)
    }
}
