package com.example.kotlinroomdatabase.fragments.grades

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.kotlinroomdatabase.R

class GradesFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_grades, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val prefs = requireContext().getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
        val role = prefs.getString("user_role", "student")

        if (savedInstanceState == null) {
            val fragment = if (role == "teacher") {
                TeacherSubjectsFragment()
            } else {
                StudentGradesFragment()
            }

            childFragmentManager.beginTransaction()
                .replace(R.id.grades_container, fragment)
                .commit()
        }
    }
}
