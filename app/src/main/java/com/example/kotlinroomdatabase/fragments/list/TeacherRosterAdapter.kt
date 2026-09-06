package com.example.kotlinroomdatabase.fragments.list

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.model.AttendanceRosterStudent
import com.example.kotlinroomdatabase.util.RoleUtils

class TeacherRosterAdapter(
    private val onStatusChanged: (student: AttendanceRosterStudent, newStatus: String) -> Unit,
    private val onFraudClicked: (student: AttendanceRosterStudent) -> Unit
) : RecyclerView.Adapter<TeacherRosterAdapter.RosterViewHolder>() {

    private val students = mutableListOf<AttendanceRosterStudent>()

    fun setData(newList: List<AttendanceRosterStudent>) {
        students.clear()
        students.addAll(newList)
        notifyDataSetChanged()
    }

    fun updateStudentStatus(studentId: Int, newStatus: String) {
        val index = students.indexOfFirst { it.student_id == studentId }
        if (index != -1) {
            val old = students[index]
            students[index] = old.copy(status = newStatus)
            notifyItemChanged(index)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RosterViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_roster_student, parent, false)
        return RosterViewHolder(view)
    }

    override fun onBindViewHolder(holder: RosterViewHolder, position: Int) {
        holder.bind(students[position])
    }

    override fun getItemCount(): Int = students.size

    inner class RosterViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvStudentName: TextView = itemView.findViewById(R.id.tvStudentName)
        private val tvStudentGroup: TextView = itemView.findViewById(R.id.tvStudentGroup)
        private val tvAvatarInitials: TextView = itemView.findViewById(R.id.tvAvatarInitials)
        private val tvMarkedTime: TextView = itemView.findViewById(R.id.tvMarkedTime)
        private val tvFraudFlag: TextView = itemView.findViewById(R.id.tvFraudFlag)
        private val layoutStatusSelector: LinearLayout = itemView.findViewById(R.id.layoutStatusSelector)
        private val tvStatusLabel: TextView = itemView.findViewById(R.id.tvStatusLabel)
        private val ivDropdownArrow: ImageView = itemView.findViewById(R.id.ivDropdownArrow)

        fun bind(student: AttendanceRosterStudent) {
            val context = itemView.context
            tvStudentName.text = RoleUtils.formatShortName(student.student_name)
            tvStudentGroup.text = student.group_name.ifBlank { "—" }

            // Initials
            val parts = student.student_name.trim().split("\\s+".toRegex())
            val initials = when {
                parts.size >= 2 -> "${parts[0].take(1)}${parts[1].take(1)}".uppercase()
                parts.isNotEmpty() -> parts[0].take(2).uppercase()
                else -> "С"
            }
            tvAvatarInitials.text = initials

            // Marked time
            if (!student.marked_at.isNullOrBlank()) {
                val timeStr = formatTimeString(student.marked_at)
                tvMarkedTime.text = timeStr
                tvMarkedTime.visibility = View.VISIBLE
            } else {
                tvMarkedTime.visibility = View.GONE
            }

            // Fraud flag
            if (student.is_fraud) {
                tvFraudFlag.visibility = View.VISIBLE
                tvFraudFlag.setOnClickListener {
                    onFraudClicked(student)
                }
            } else {
                tvFraudFlag.visibility = View.GONE
                tvFraudFlag.setOnClickListener(null)
            }

            // Status display
            applyStatusStyle(context, student.status)

            // Popup menu on status selector
            layoutStatusSelector.setOnClickListener { view ->
                showStatusMenu(context, view, student)
            }
        }

        private fun applyStatusStyle(context: Context, status: String) {
            when (status.lowercase()) {
                "present", "ontime" -> {
                    layoutStatusSelector.setBackgroundResource(R.drawable.bg_badge_ontime)
                    tvStatusLabel.text = "✓ Был"
                    val color = ContextCompat.getColor(context, R.color.badge_ontime_text)
                    tvStatusLabel.setTextColor(color)
                    ivDropdownArrow.setColorFilter(color)
                }
                "late" -> {
                    layoutStatusSelector.setBackgroundResource(R.drawable.bg_badge_late)
                    tvStatusLabel.text = "⏰ Опоздал"
                    val color = ContextCompat.getColor(context, R.color.badge_late_text)
                    tvStatusLabel.setTextColor(color)
                    ivDropdownArrow.setColorFilter(color)
                }
                "excused", "valid" -> {
                    layoutStatusSelector.setBackgroundResource(R.drawable.bg_badge_excused)
                    tvStatusLabel.text = "ℹ Уваж."
                    val color = ContextCompat.getColor(context, R.color.badge_excused_text)
                    tvStatusLabel.setTextColor(color)
                    ivDropdownArrow.setColorFilter(color)
                }
                else -> {
                    layoutStatusSelector.setBackgroundResource(R.drawable.bg_badge_absent)
                    tvStatusLabel.text = "✕ Пропуск"
                    val color = ContextCompat.getColor(context, R.color.badge_absent_text)
                    tvStatusLabel.setTextColor(color)
                    ivDropdownArrow.setColorFilter(color)
                }
            }
        }

        private fun showStatusMenu(context: Context, anchor: View, student: AttendanceRosterStudent) {
            val popup = PopupMenu(context, anchor)
            popup.menu.add(0, 1, 0, "✓ Был (На паре)")
            popup.menu.add(0, 2, 1, "⏰ Опоздал")
            popup.menu.add(0, 3, 2, "ℹ Уважительная причина")
            popup.menu.add(0, 4, 3, "✕ Пропуск (Отсутствует)")

            popup.setOnMenuItemClickListener { menuItem ->
                val newStatus = when (menuItem.itemId) {
                    1 -> "present"
                    2 -> "late"
                    3 -> "excused"
                    4 -> "absent"
                    else -> return@setOnMenuItemClickListener false
                }
                applyStatusStyle(context, newStatus)
                onStatusChanged(student, newStatus)
                true
            }
            popup.show()
        }

        private fun formatTimeString(rawTime: String): String {
            return try {
                if (rawTime.contains("T")) {
                    val timePart = rawTime.substringAfter("T").substringBefore(".")
                    timePart.take(5)
                } else if (rawTime.length >= 5 && rawTime.contains(":")) {
                    rawTime.take(5)
                } else {
                    rawTime
                }
            } catch (e: Exception) {
                rawTime
            }
        }
    }
}
