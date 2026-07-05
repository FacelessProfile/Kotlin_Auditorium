package com.example.kotlinroomdatabase.fragments.grades

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.model.StudentGradePoint

class StudentGradesInnerAdapter(
    private var grades: List<StudentGradePoint>,
    private val onGradeClick: (StudentGradePoint) -> Unit
) : RecyclerView.Adapter<StudentGradesInnerAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvInnerGradeTitle: TextView = view.findViewById(R.id.tvInnerGradeTitle)
        val tvInnerGradeType: TextView = view.findViewById(R.id.tvInnerGradeType)
        val tvInnerGradeScore: TextView = view.findViewById(R.id.tvInnerGradeScore)
        val rootLayout: View = view
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_teacher_inner_grade, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val grade = grades[position]
        holder.tvInnerGradeTitle.text = grade.title
        holder.tvInnerGradeType.text = "Тип: ${grade.item_type}"
        holder.tvInnerGradeScore.text = "${grade.score} / ${grade.max_score}"

        holder.rootLayout.setOnClickListener {
            onGradeClick(grade)
        }
    }

    override fun getItemCount() = grades.size

    fun updateData(newGrades: List<StudentGradePoint>) {
        grades = newGrades
        notifyDataSetChanged()
    }
}
