package com.example.kotlinroomdatabase.fragments.grades

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.model.StudentSubjectPerformance
import com.example.kotlinroomdatabase.model.StudentGradePoint
import com.example.kotlinroomdatabase.utils.GradeUtils

class SubjectGradesAdapter(
    private var subjects: List<StudentSubjectPerformance>
) : RecyclerView.Adapter<SubjectGradesAdapter.ViewHolder>() {

    private val expandedStates = mutableMapOf<Int, Boolean>()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val layoutHeader: View = view.findViewById(R.id.layoutStudentSubjectHeader)
        val tvSubjectName: TextView = view.findViewById(R.id.tvSubjectName)
        val tvScore: TextView = view.findViewById(R.id.tvScore)
        val tvPercent: TextView = view.findViewById(R.id.tvPercent)
        val ivChevron: ImageView? = view.findViewById(R.id.ivChevron)
        val layoutContainer: View = view.findViewById(R.id.layoutSubjectGradesContainer)
        val rvInnerGrades: RecyclerView = view.findViewById(R.id.rvSubjectInnerGrades)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_student_subject_accordion, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val subject = subjects[position]
        holder.tvSubjectName.text = subject.subject_name
        holder.tvScore.text = "Набрано: ${subject.current_score} / ${subject.total_max} баллов"
        holder.tvPercent.text = "${subject.displayPercent}%"

        val pct = subject.displayPercent
        val colorRes = when {
            pct >= 85 -> R.color.grade_excellent
            pct >= 70 -> R.color.uni_blue_primary
            pct >= 50 -> R.color.grade_satisfactory
            else -> R.color.grade_poor
        }
        val context = holder.itemView.context
        holder.tvPercent.setTextColor(androidx.core.content.ContextCompat.getColor(context, colorRes))

        val isExpanded = expandedStates[subject.subject_id] == true
        holder.layoutContainer.visibility = if (isExpanded) View.VISIBLE else View.GONE
        holder.ivChevron?.rotation = if (isExpanded) 180f else 0f

        holder.layoutHeader.setOnClickListener {
            val currentlyExpanded = expandedStates[subject.subject_id] == true
            expandedStates[subject.subject_id] = !currentlyExpanded
            notifyItemChanged(position)
        }

        if (isExpanded) {
            holder.rvInnerGrades.layoutManager = LinearLayoutManager(holder.itemView.context)
            val combinedList = subject.grades.toMutableList()
            subject.rewards.forEach { r ->
                combinedList.add(
                    StudentGradePoint(
                        item_id = -1,
                        title = if (r.score > 0) "Поощрение" else "Штраф / Наказание",
                        max_score = kotlin.math.abs(r.score),
                        item_type = "reward",
                        score = r.score,
                        graded_at = r.created_at,
                        comment = r.reason
                    )
                )
            }
            holder.rvInnerGrades.adapter = StudentInnerGradesAdapter(combinedList)
        }
    }

    override fun getItemCount() = subjects.size

    fun updateData(newSubjects: List<StudentSubjectPerformance>) {
        subjects = newSubjects
        notifyDataSetChanged()
    }

    class StudentInnerGradesAdapter(private val grades: List<StudentGradePoint>) :
        RecyclerView.Adapter<StudentInnerGradesAdapter.InnerViewHolder>() {

        class InnerViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvTitle: TextView = view.findViewById(R.id.tvInnerGradeTitle)
            val tvType: TextView = view.findViewById(R.id.tvInnerGradeType)
            val tvScore: TextView = view.findViewById(R.id.tvInnerGradeScore)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): InnerViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_student_inner_grade, parent, false)
            return InnerViewHolder(view)
        }

        override fun onBindViewHolder(holder: InnerViewHolder, position: Int) {
            val grade = grades[position]
            holder.tvTitle.text = grade.title
            holder.tvType.text = GradeUtils.translateItemType(grade.item_type)
            holder.tvScore.text = "${grade.score} / ${grade.max_score}"

            val context = holder.itemView.context
            val pct = if (grade.max_score > 0) (grade.score * 100) / grade.max_score else 0
            val colorRes = when {
                pct >= 85 -> R.color.grade_excellent
                pct >= 70 -> R.color.uni_blue_primary
                pct >= 50 -> R.color.grade_satisfactory
                else -> R.color.grade_poor
            }
            holder.tvScore.setTextColor(androidx.core.content.ContextCompat.getColor(context, colorRes))
        }

        override fun getItemCount() = grades.size
    }
}
