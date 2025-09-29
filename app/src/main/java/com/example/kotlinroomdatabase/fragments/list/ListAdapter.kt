package com.example.kotlinroomdatabase.fragments.list

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.RecyclerView
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.model.Student
import com.example.kotlinroomdatabase.model.User
import kotlinx.serialization.InternalSerializationApi

class  ListAdapter : RecyclerView.Adapter<ListAdapter.MyViewHolder>() {

    @OptIn(InternalSerializationApi::class)
    private var studentList: List<Student>? = emptyList()

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
    override fun getItemCount(): Int = studentList!!.size

    @OptIn(InternalSerializationApi::class)
    override fun onBindViewHolder(holder: MyViewHolder, position: Int) {
        val student = studentList!!.get(position)
        val fullName = student.studentName.split(" ")
        val fio = "${fullName[0]} ${fullName[1]} ${fullName[2][0]}."
        holder.itemView.findViewById<TextView>(R.id.id_txt).text = (position + 1).toString() //student.studentNumber
        holder.itemView.findViewById<TextView>(R.id.FIO_txt).text = fio

        holder.itemView.findViewById<TextView>(R.id.attendance_txt).text = if (student.attendance) "+" else "-"
        holder.itemView.findViewById<ConstraintLayout>(R.id.rowLayout).setOnClickListener {
            // val action = ListFragmentDirections.actionListFragmentToUpdateFragment(student)
            // holder.itemView.findNavController().navigate(action) ATTENTION DO IT NEXT TIME
        }
    }

    @OptIn(InternalSerializationApi::class)
    fun setData(students: List<Student>?) {
        studentList = students
            ?.sortedWith(compareBy<Student> { it.studentName })
            ?: emptyList() // сортируем по журнальному номеру(БЫЛО) -> сортируем по ФИО (СТАЛО)
        notifyDataSetChanged()
    }
}
