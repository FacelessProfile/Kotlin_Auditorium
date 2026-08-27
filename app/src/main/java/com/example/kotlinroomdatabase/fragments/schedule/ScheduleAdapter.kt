package com.example.kotlinroomdatabase.fragments.schedule

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.model.LessonScheduleItem

class ScheduleAdapter(private val userRole: String) : RecyclerView.Adapter<ScheduleAdapter.ViewHolder>() {

    private var items = listOf<LessonScheduleItem>()

    fun setData(newItems: List<LessonScheduleItem>) {
        this.items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_schedule_lesson, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context

        // 1. Lesson Number
        holder.tvLessonNumBadge.text = if (item.lesson_num in 1..8) "${item.lesson_num} пара" else "Пара"

        // 2. Time Slot
        val timeText = if (item.start_time.isNotBlank() && item.end_time.isNotBlank()) {
            "${item.start_time} – ${item.end_time}"
        } else if (item.start_time.isNotBlank()) {
            item.start_time
        } else {
            "Время по расписанию"
        }
        holder.tvScheduleTime.text = timeText

        // 3. Subject Name
        holder.tvScheduleSubject.text = item.subject_name.ifBlank { "Занятие" }

        // 4. Teacher vs Group
        if (userRole == "teacher" || userRole == "admin") {
            val group = item.group_name?.takeIf { it.isNotBlank() } ?: "Группа"
            holder.tvTeacherOrGroup.text = "Группа: $group"
            holder.ivTeacherGroupIcon.setImageResource(R.drawable.ic_person)
        } else {
            val teacher = item.teacher_name?.takeIf { it.isNotBlank() } ?: "Преподаватель кафедры"
            holder.tvTeacherOrGroup.text = teacher
            holder.ivTeacherGroupIcon.setImageResource(R.drawable.ic_person)
        }

        // 5. Lesson Type
        val rawType = item.lesson_type.ifBlank { "Практика" }
        holder.tvScheduleLessonType.text = rawType

        // 6. Room Info
        if (item.room_info.isNotBlank()) {
            holder.tvScheduleRoom.visibility = View.VISIBLE
            holder.tvScheduleRoom.text = if (item.room_info.startsWith("Ауд", ignoreCase = true) || item.room_info.startsWith("Лаб", ignoreCase = true)) {
                item.room_info
            } else {
                "Ауд. ${item.room_info}"
            }
        } else {
            holder.tvScheduleRoom.visibility = View.VISIBLE
            holder.tvScheduleRoom.text = "Аудитория кафедры"
        }

        // 7. Subgroup
        if (item.subgroup.isNotBlank()) {
            holder.tvScheduleSubgroup.visibility = View.VISIBLE
            holder.tvScheduleSubgroup.text = "• ${item.subgroup}"
        } else {
            holder.tvScheduleSubgroup.visibility = View.GONE
        }

        // 8. Strip Color
        val stripColorRes = when {
            rawType.contains("Лекция", ignoreCase = true) -> R.color.uni_blue_primary
            rawType.contains("Лаб", ignoreCase = true) -> R.color.badge_late_icon
            rawType.contains("Факульт", ignoreCase = true) -> R.color.badge_ontime_icon
            rawType.contains("Практ", ignoreCase = true) -> R.color.badge_ontime_icon
            else -> R.color.uni_blue_accent
        }
        holder.viewLessonColorStrip.setBackgroundColor(ContextCompat.getColor(context, stripColorRes))
    }

    override fun getItemCount() = items.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val viewLessonColorStrip: View = view.findViewById(R.id.viewLessonColorStrip)
        val tvLessonNumBadge: TextView = view.findViewById(R.id.tvLessonNumBadge)
        val tvScheduleTime: TextView = view.findViewById(R.id.tvScheduleTime)
        val tvScheduleSubject: TextView = view.findViewById(R.id.tvScheduleSubject)
        val layoutTeacherOrGroup: View = view.findViewById(R.id.layoutTeacherOrGroup)
        val ivTeacherGroupIcon: ImageView = view.findViewById(R.id.ivTeacherGroupIcon)
        val tvTeacherOrGroup: TextView = view.findViewById(R.id.tvTeacherOrGroup)
        val tvScheduleLessonType: TextView = view.findViewById(R.id.tvScheduleLessonType)
        val tvScheduleRoom: TextView = view.findViewById(R.id.tvScheduleRoom)
        val tvScheduleSubgroup: TextView = view.findViewById(R.id.tvScheduleSubgroup)
    }
}
