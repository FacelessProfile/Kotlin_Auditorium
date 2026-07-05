package com.example.kotlinroomdatabase.fragments.grades

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.model.TeacherSubject

class TeacherSubjectsAdapter(
    private var subjects: List<TeacherSubject>,
    private val onClick: (TeacherSubject) -> Unit
) : RecyclerView.Adapter<TeacherSubjectsAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvSubjectName: TextView = view.findViewById(R.id.tvSubjectName)
        val tvGroups: TextView = view.findViewById(R.id.tvGroups)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_teacher_subject, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val subject = subjects[position]
        holder.tvSubjectName.text = subject.subject_name
        
        val groupsStr = subject.groups.joinToString(", ") { it.name }
        holder.tvGroups.text = "Группы: $groupsStr"
        
        holder.itemView.setOnClickListener { onClick(subject) }
    }

    override fun getItemCount() = subjects.size

    fun updateData(newSubjects: List<TeacherSubject>) {
        subjects = newSubjects
        notifyDataSetChanged()
    }
}
