package com.example.kotlinroomdatabase.fragments.grades

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.data.StudentDatabase
import com.example.kotlinroomdatabase.repository.GenericResult
import com.example.kotlinroomdatabase.model.SemesterInfo
import com.example.kotlinroomdatabase.repository.IStudentRepository
import com.example.kotlinroomdatabase.repository.StudentRepositoryHTTPS
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import kotlinx.coroutines.launch

class StudentGradesFragment : Fragment() {

    private lateinit var progressScore: CircularProgressIndicator
    private lateinit var tvScoreValue: TextView
    private lateinit var tvWorks: TextView
    private lateinit var tvStatus: TextView
    private lateinit var actvSemester: MaterialAutoCompleteTextView
    
    private lateinit var rvSubjects: RecyclerView
    private lateinit var adapter: SubjectGradesAdapter
    private lateinit var repository: IStudentRepository

    private var selectedSemesterId: Int? = null
    private var availableSemesters: List<SemesterInfo> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_student_grades, container, false)
        progressScore = view.findViewById(R.id.progressScore)
        tvScoreValue = view.findViewById(R.id.tvScoreValue)
        tvWorks = view.findViewById(R.id.tvWorks)
        tvStatus = view.findViewById(R.id.tvStatus)
        actvSemester = view.findViewById(R.id.actvSemester)
        rvSubjects = view.findViewById(R.id.rvSubjects)

        rvSubjects.layoutManager = LinearLayoutManager(requireContext())
        adapter = SubjectGradesAdapter(emptyList())
        rvSubjects.adapter = adapter

        val db = StudentDatabase.getInstance(requireContext())
        repository = StudentRepositoryHTTPS(requireContext(), db.studentDao())

        loadSemestersAndData()

        return view
    }

    private fun loadSemestersAndData() {
        lifecycleScope.launch {
            val semRes = repository.getSemesters()
            if (semRes is GenericResult.Success && semRes.data.isNotEmpty()) {
                availableSemesters = semRes.data
                val semNames = availableSemesters.map { it.name }

                val currentSem = availableSemesters.find { it.is_current } ?: availableSemesters.first()
                selectedSemesterId = currentSem.id

                // Set up adapter with no-filter FIRST, then set text
                val semAdapter = object : ArrayAdapter<String>(
                    requireContext(),
                    android.R.layout.simple_dropdown_item_1line,
                    semNames
                ) {
                    private val noFilter = object : android.widget.Filter() {
                        override fun performFiltering(constraint: CharSequence?): FilterResults {
                            return FilterResults().apply {
                                values = semNames
                                count = semNames.size
                            }
                        }
                        override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                            notifyDataSetChanged()
                        }
                    }
                    override fun getFilter(): android.widget.Filter = noFilter
                }
                actvSemester.setAdapter(semAdapter)
                actvSemester.setText(currentSem.name, false)

                actvSemester.setOnItemClickListener { _, _, position, _ ->
                    if (position in semNames.indices) {
                        val selectedName = semNames[position]
                        val sem = availableSemesters.find { it.name == selectedName }
                        if (sem != null && sem.id != selectedSemesterId) {
                            selectedSemesterId = sem.id
                            Log.d("SEMESTER_SWITCH", "Student grades: semester changed to ${sem.name} (id=${sem.id})")
                            Toast.makeText(requireContext(), "Семестр: ${sem.name}", Toast.LENGTH_SHORT).show()
                            // Clear existing data immediately for visual feedback
                            adapter.updateData(emptyList())
                            progressScore.setProgressCompat(0, true)
                            tvScoreValue.text = "..."
                            tvWorks.text = "..."
                            tvStatus.text = "Загрузка..."
                            // Load new data
                            loadData()
                        }
                    }
                }
            }
            loadData()
        }
    }

    private fun loadData() {
        lifecycleScope.launch {
            try {
                Log.d("SEMESTER_SWITCH", "Student loadData with semesterId=$selectedSemesterId")
                val listData = repository.getStudentGradesAll(selectedSemesterId)
                Log.d("SEMESTER_SWITCH", "Student got ${listData?.size ?: 0} subjects for semester $selectedSemesterId")

                adapter.updateData(listData ?: emptyList())

                var currentScore = 0
                var passedMax = 0
                var totalMax = 0

                listData?.forEach { subj ->
                    currentScore += subj.current_score
                    passedMax += subj.passed_max
                    totalMax += subj.total_max
                }

                val percent = if (totalMax > 0) ((currentScore * 100) / totalMax).coerceAtMost(100) else 0
                
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
                Log.e("SEMESTER_SWITCH", "Student loadData error", e)
                Toast.makeText(requireContext(), "Ошибка загрузки оценок", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
