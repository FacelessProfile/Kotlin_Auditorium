package com.example.kotlinroomdatabase.fragments.grades

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.model.GroupSubjectPerformanceRow
import com.example.kotlinroomdatabase.utils.GradeUtils

class DashboardStudentsAdapter(
    private var students: List<GroupSubjectPerformanceRow>,
    private val onClick: (GroupSubjectPerformanceRow) -> Unit
) : RecyclerView.Adapter<DashboardStudentsAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvStudentName: TextView = view.findViewById(R.id.tvStudentName)
        val tvAttendance: TextView = view.findViewById(R.id.tvAttendance)
        val tvScore: TextView = view.findViewById(R.id.tvScore)
        val tvPercent: TextView = view.findViewById(R.id.tvPercent)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_dashboard_student, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val student = students[position]
        holder.tvStudentName.text = student.student_name
        holder.tvAttendance.text = "Посещаемость: ${student.attended_sessions}/${student.total_sessions}"
        holder.tvScore.text = "Оценка: ${student.current_score}/${student.total_max}"
        val safeTotal = if (student.total_max > 0) student.total_max else 1
        val calculatedPercent = ((student.current_score * 100) / safeTotal).coerceAtMost(100)
        
        holder.tvPercent.text = "${calculatedPercent}%"
        GradeUtils.applyColorToTextView(holder.tvPercent, calculatedPercent)
        
        holder.itemView.setOnClickListener { onClick(student) }
    }

    override fun getItemCount() = students.size

    fun updateData(newStudents: List<GroupSubjectPerformanceRow>) {
        students = newStudents
        notifyDataSetChanged()
    }
}
