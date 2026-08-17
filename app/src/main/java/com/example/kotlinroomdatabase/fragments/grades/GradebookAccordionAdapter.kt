package com.example.kotlinroomdatabase.fragments.grades

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.model.GroupSubjectPerformanceRow
import com.example.kotlinroomdatabase.model.StudentGradePoint

class GradebookAccordionAdapter(
    private var students: List<GroupSubjectPerformanceRow>,
    private val onLoadGrades: (Int, (List<StudentGradePoint>) -> Unit) -> Unit,
    private val onGradeStudent: (GroupSubjectPerformanceRow) -> Unit,
    private val onEditSpecificGrade: (StudentGradePoint, GroupSubjectPerformanceRow) -> Unit,
    private val onDeleteSpecificGrade: ((StudentGradePoint, GroupSubjectPerformanceRow) -> Unit)? = null
) : RecyclerView.Adapter<GradebookAccordionAdapter.ViewHolder>() {

    private val expandedStates = mutableMapOf<Int, Boolean>()
    private val gradesCache = mutableMapOf<Int, List<StudentGradePoint>>()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val layoutHeader: View = view.findViewById(R.id.layoutHeader)
        val tvStudentName: TextView = view.findViewById(R.id.tvStudentName)
        val tvStudentTotal: TextView = view.findViewById(R.id.tvStudentTotal)
        val ivExpand: ImageView = view.findViewById(R.id.ivExpand)
        
        val layoutGradesContainer: View = view.findViewById(R.id.layoutGradesContainer)
        val pbLoadingGrades: ProgressBar = view.findViewById(R.id.pbLoadingGrades)
        val rvStudentGrades: RecyclerView = view.findViewById(R.id.rvStudentGrades)
        val btnGradeStudent: Button = view.findViewById(R.id.btnGradeStudent)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_gradebook_student, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val student = students[position]
        holder.tvStudentName.text = student.student_name
        holder.tvStudentTotal.text = "${student.percent}%"
        
        val isExpanded = expandedStates[student.student_id] == true
        holder.layoutGradesContainer.visibility = if (isExpanded) View.VISIBLE else View.GONE
        holder.ivExpand.rotation = if (isExpanded) 180f else 0f

        holder.layoutHeader.setOnClickListener {
            val currentlyExpanded = expandedStates[student.student_id] == true
            expandedStates[student.student_id] = !currentlyExpanded
            notifyItemChanged(position)
        }

        holder.btnGradeStudent.setOnClickListener {
            onGradeStudent(student)
        }

        if (isExpanded) {
            val cachedGrades = gradesCache[student.student_id]
            if (cachedGrades != null) {
                holder.pbLoadingGrades.visibility = View.GONE
                holder.rvStudentGrades.visibility = View.VISIBLE
                setupInnerAdapter(holder, cachedGrades, student)
            } else {
                holder.pbLoadingGrades.visibility = View.VISIBLE
                holder.rvStudentGrades.visibility = View.GONE
                onLoadGrades(student.student_id) { loadedGrades ->
                    gradesCache[student.student_id] = loadedGrades
                    if (expandedStates[student.student_id] == true) {
                        notifyItemChanged(position)
                    }
                }
            }
        }
    }

    private fun setupInnerAdapter(holder: ViewHolder, grades: List<StudentGradePoint>, student: GroupSubjectPerformanceRow) {
        if (holder.rvStudentGrades.layoutManager == null) {
            holder.rvStudentGrades.layoutManager = LinearLayoutManager(holder.itemView.context)
        }
        val innerAdapter = StudentGradesInnerAdapter(
            grades,
            onGradeClick = { gradePoint ->
                onEditSpecificGrade(gradePoint, student)
            },
            onDeleteGradeClick = onDeleteSpecificGrade?.let { callback ->
                { gradePoint -> callback(gradePoint, student) }
            }
        )
        holder.rvStudentGrades.adapter = innerAdapter
    }

    override fun getItemCount() = students.size

    fun updateData(newStudents: List<GroupSubjectPerformanceRow>) {
        students = newStudents
        gradesCache.clear()
        notifyDataSetChanged()
    }
}
