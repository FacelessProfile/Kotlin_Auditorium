package com.example.kotlinroomdatabase.fragments.list

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.RecyclerView
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.model.Student
import com.example.kotlinroomdatabase.getColorFromAttr
import kotlinx.serialization.InternalSerializationApi

class ListAdapter : RecyclerView.Adapter<ListAdapter.MyViewHolder>() {

    @OptIn(InternalSerializationApi::class)
    private var studentList: MutableList<Student> = mutableListOf()
    @OptIn(InternalSerializationApi::class)
    private var onItemClick: ((Student) -> Unit)? = null
    private var isLessonActive = false

    fun setLessonState(active: Boolean) {
        this.isLessonActive = active
        notifyDataSetChanged()
    }

    class MyViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MyViewHolder {
        return MyViewHolder(
            LayoutInflater.from(parent.context).inflate(
                R.layout.custom_row,
                parent,
                false
            )
        )
    }

    @OptIn(InternalSerializationApi::class)
    override fun getItemCount(): Int = studentList.size

    @OptIn(InternalSerializationApi::class)
    override fun onBindViewHolder(holder: MyViewHolder, position: Int) {
        val student = studentList[position]
        val tvId = holder.itemView.findViewById<TextView>(R.id.id_txt)
        val tvFio = holder.itemView.findViewById<TextView>(R.id.FIO_txt)
        val tvGroup = holder.itemView.findViewById<TextView?>(R.id.tvStudentGroupRow)
        val tvAvatar = holder.itemView.findViewById<TextView?>(R.id.tvAvatarInitials)
        val tvAttendance = holder.itemView.findViewById<TextView>(R.id.attendance_txt)
        val layoutBadge = holder.itemView.findViewById<View?>(R.id.layoutBadgeStatus)

        val nameParts = student.studentName.trim().split("\\s+".toRegex())
        val initials = when {
            nameParts.size >= 2 -> "${nameParts[0].take(1)}${nameParts[1].take(1)}".uppercase()
            nameParts.isNotEmpty() -> nameParts[0].take(2).uppercase()
            else -> "С"
        }
        tvAvatar?.text = initials

        tvId?.text = (position + 1).toString()
        tvFio.text = student.studentName
        tvGroup?.text = "Группа: ${student.studentGroup.ifBlank { "—" }}"

        if (student.attendance) {
            tvAttendance.text = "✓ На паре"
            tvAttendance.setTextColor(Color.parseColor("#047857"))
            layoutBadge?.setBackgroundResource(R.drawable.bg_badge_ontime)
        } else {
            tvAttendance.text = "— Не отмечен"
            tvAttendance.setTextColor(Color.parseColor("#B91C1C"))
            layoutBadge?.setBackgroundResource(R.drawable.bg_badge_absent)
        }

        val rowCard = holder.itemView.findViewById<com.google.android.material.card.MaterialCardView?>(R.id.rowLayout)
        if (student.isFraud) {
            rowCard?.setCardBackgroundColor(Color.parseColor("#FEE2E2"))
            tvAttendance.text = "⚠ Подозрительно"
            tvAttendance.setTextColor(Color.parseColor("#DC2626"))
        } else {
            rowCard?.setCardBackgroundColor(holder.itemView.context.getColorFromAttr(com.google.android.material.R.attr.colorSurface))
        }

        if (isLessonActive) {
            rowCard?.setOnClickListener(null)
            rowCard?.isClickable = false
        } else {
            rowCard?.isClickable = true
            rowCard?.setOnClickListener {
                onItemClick?.invoke(student)
            }
        }
    }

    @OptIn(InternalSerializationApi::class)
    fun setData(students: List<Student>?) {
        studentList = students
            ?.sortedWith(compareBy<Student> { it.studentName })
            ?.toMutableList() ?: mutableListOf()
        notifyDataSetChanged()
    }

    @OptIn(InternalSerializationApi::class)
    fun setOnItemClickListener(listener: (Student) -> Unit) {
        onItemClick = listener
    }

    @OptIn(InternalSerializationApi::class)
    fun getStudentAtPosition(position: Int): Student {
        return studentList[position]
    }

    @OptIn(InternalSerializationApi::class)
    fun removeStudent(position: Int) {
        if (position in 0 until studentList.size) {
            studentList.removeAt(position)
            notifyItemRemoved(position)
        }
    }
}