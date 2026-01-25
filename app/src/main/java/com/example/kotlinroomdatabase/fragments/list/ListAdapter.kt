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
        val tvAttendance = holder.itemView.findViewById<TextView>(R.id.attendance_txt)
        val fullName = student.studentName.split(" ")
        val fio = if (fullName.size >= 3) {

            "${fullName[0]} ${fullName[1]} ${fullName[2][0]}."
        } else {
            student.studentName
        }
        tvId.text = (position + 1).toString()
        tvFio.text = fio
        tvAttendance.text = if (student.attendance) "+" else "-"
        val textColor = if (isLessonActive) Color.BLACK else Color.LTGRAY
        tvId.setTextColor(textColor)
        tvFio.setTextColor(textColor)
        tvAttendance.setTextColor(textColor)

        holder.itemView.findViewById<ConstraintLayout>(R.id.rowLayout).setOnClickListener {
            onItemClick?.invoke(student)
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