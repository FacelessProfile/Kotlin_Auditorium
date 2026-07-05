package com.example.kotlinroomdatabase.fragments.grades

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.data.StudentDatabase
import com.example.kotlinroomdatabase.repository.IStudentRepository
import com.example.kotlinroomdatabase.repository.StudentRepositoryHTTPS
import kotlinx.coroutines.launch

class TeacherSubjectsFragment : Fragment() {

    private lateinit var rvTeacherSubjects: RecyclerView
    private lateinit var adapter: TeacherSubjectsAdapter
    private lateinit var repository: IStudentRepository

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_teacher_grades, container, false)
        rvTeacherSubjects = view.findViewById(R.id.rvTeacherSubjects)

        rvTeacherSubjects.layoutManager = LinearLayoutManager(requireContext())
        adapter = TeacherSubjectsAdapter(emptyList()) { subject ->
            // Navigate to TeacherGradesDashboardFragment for specific subject
            val fragment = TeacherGradesDashboardFragment().apply {
                arguments = Bundle().apply {
                    putInt("subject_id", subject.subject_id)
                    putString("subject_name", subject.subject_name)
                    // You could pass groups here or have the Dashboard fetch them
                }
            }
            parentFragmentManager.beginTransaction()
                .replace(R.id.grades_container, fragment)
                .addToBackStack(null)
                .commit()
        }
        rvTeacherSubjects.adapter = adapter

        val db = StudentDatabase.getInstance(requireContext())
        repository = StudentRepositoryHTTPS(requireContext(), db.studentDao())

        loadData()

        return view
    }

    private fun loadData() {
        lifecycleScope.launch {
            try {
                val subjects = repository.getTeacherSubjects()
                adapter.updateData(subjects)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Ошибка загрузки предметов", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
