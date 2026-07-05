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
import com.example.kotlinroomdatabase.getColorFromAttr
import com.example.kotlinroomdatabase.utils.GradeUtils
import android.widget.TextView
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.launch

class StudentGradesFragment : Fragment() {

    private lateinit var progressScore: CircularProgressIndicator
    private lateinit var tvScoreValue: TextView
    private lateinit var tvWorks: TextView
    private lateinit var tvStatus: TextView
    
    private lateinit var rvSubjects: RecyclerView
    private lateinit var adapter: SubjectGradesAdapter
    private lateinit var repository: IStudentRepository

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_student_grades, container, false)
        progressScore = view.findViewById(R.id.progressScore)
        tvScoreValue = view.findViewById(R.id.tvScoreValue)
        tvWorks = view.findViewById(R.id.tvWorks)
        tvStatus = view.findViewById(R.id.tvStatus)
        rvSubjects = view.findViewById(R.id.rvSubjects)

        rvSubjects.layoutManager = LinearLayoutManager(requireContext())
        adapter = SubjectGradesAdapter(emptyList())
        rvSubjects.adapter = adapter

        val db = StudentDatabase.getInstance(requireContext())
        repository = StudentRepositoryHTTPS(requireContext(), db.studentDao())


        loadData()

        return view
    }

    private fun loadData() {
        lifecycleScope.launch {
            try {
                val listData = repository.getStudentGradesAll()

                adapter.updateData(listData)

                var currentScore = 0
                var passedMax = 0
                var totalMax = 0

                listData?.forEach { subj ->
                    currentScore += subj.current_score
                    passedMax += subj.passed_max
                    totalMax += subj.total_max
                }

                val percent = if (passedMax > 0) (currentScore * 100) / passedMax else 0
                
                progressScore.setProgressCompat(percent, true)
                tvScoreValue.text = "$percent%"
                tvWorks.text = "$currentScore / $totalMax"

                tvStatus.text = when {
                    percent >= 85 -> "Отличник"
                    percent >= 70 -> "Хорошист"
                    percent >= 50 -> "Нормально"
                    percent > 0 -> "Слабовато"
                    else -> "Нет оценок"
                }

                val colorCode = when {
                    percent >= 85 -> "#4CAF50"
                    percent >= 70 -> "#FFEB3B"
                    percent >= 50 -> "#FF9800"
                    else -> "#F44336"
                }
                progressScore.setIndicatorColor(android.graphics.Color.parseColor(colorCode))

            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Ошибка загрузки оценок", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
