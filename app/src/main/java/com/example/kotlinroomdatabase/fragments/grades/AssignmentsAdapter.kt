package com.example.kotlinroomdatabase.fragments.grades

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.model.GradeItem

class AssignmentsAdapter(
    private var assignments: List<GradeItem>,
    private val onClick: (GradeItem) -> Unit = {},
    private val onDeleteClick: ((GradeItem) -> Unit)? = null
) : RecyclerView.Adapter<AssignmentsAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvAssignmentTitle: TextView = view.findViewById(R.id.tvAssignmentTitle)
        val tvAssignmentType: TextView = view.findViewById(R.id.tvAssignmentType)
        val tvAssignmentMaxScore: TextView = view.findViewById(R.id.tvAssignmentMaxScore)
        val ibDeleteAssignment: android.widget.ImageButton = view.findViewById(R.id.ibDeleteAssignment)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_assignment, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = assignments[position]
        holder.tvAssignmentTitle.text = item.title
        holder.tvAssignmentType.text = "Тип: ${item.item_type}"
        holder.tvAssignmentMaxScore.text = "Макс. балл: ${item.max_score}"
        holder.itemView.setOnClickListener { onClick(item) }

        if (onDeleteClick != null) {
            holder.ibDeleteAssignment.visibility = View.VISIBLE
            holder.ibDeleteAssignment.setOnClickListener { onDeleteClick.invoke(item) }
        } else {
            holder.ibDeleteAssignment.visibility = View.GONE
        }
    }

    override fun getItemCount() = assignments.size

    fun updateData(newData: List<GradeItem>) {
        assignments = newData
        notifyDataSetChanged()
    }
}
