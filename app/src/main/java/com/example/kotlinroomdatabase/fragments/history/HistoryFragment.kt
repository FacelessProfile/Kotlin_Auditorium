package com.example.kotlinroomdatabase.fragments.history

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.data.StudentDatabase
import com.example.kotlinroomdatabase.model.HistoryItem
import com.example.kotlinroomdatabase.repository.IStudentRepository
import com.example.kotlinroomdatabase.repository.StudentRepositoryHTTPS
import kotlinx.coroutines.launch

class HistoryFragment : Fragment() {

    private lateinit var repository: IStudentRepository
    private lateinit var adapter: HistoryAdapter
    private lateinit var tvCountOnTime: TextView
    private lateinit var tvCountLate: TextView
    private lateinit var tvCountTotal: TextView
    private lateinit var layoutEmptyHistory: LinearLayout
    private lateinit var rvHistory: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = StudentDatabase.getInstance(requireContext())
        repository = StudentRepositoryHTTPS(requireContext(), db.studentDao())
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_history, container, false)
        rvHistory = view.findViewById(R.id.rvHistory)
        tvCountOnTime = view.findViewById(R.id.tvCountOnTime)
        tvCountLate = view.findViewById(R.id.tvCountLate)
        tvCountTotal = view.findViewById(R.id.tvCountTotal)
        layoutEmptyHistory = view.findViewById(R.id.layoutEmptyHistory)

        adapter = HistoryAdapter()
        rvHistory.adapter = adapter
        rvHistory.layoutManager = LinearLayoutManager(requireContext())

        loadHistory()

        return view
    }

    private fun loadHistory() {
        val studentId = arguments?.getInt("studentId") ?: 0
        val subjectId = arguments?.getInt("subjectId") ?: 0

        val prefs = requireContext().getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
        val role = prefs.getString("user_role", "student")

        lifecycleScope.launch {
            if (role == "teacher" && studentId == 0) {
                repository.getAllLessons().collect { lessons ->
                    val historyItems = lessons.map {
                        HistoryItem(
                            date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date(it.date)),
                            subject_name = it.subject,
                            lesson_name = "Занятие с группами: ${it.groups}",
                            lesson_type = "Практика",
                            time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it.date)),
                            status = "present",
                            is_late = false
                        )
                    }
                    displayHistory(historyItems)
                }
            } else if (studentId > 0 && subjectId > 0) {
                val response = repository.getDetailedStudentHistory(studentId, subjectId)
                displayHistory(response)
            } else {
                val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
                val response = repository.getStudentHistory(currentYear)
                displayHistory(response.items)
            }
        }
    }

    private fun displayHistory(items: List<HistoryItem>) {
        adapter.setData(items)

        if (items.isEmpty()) {
            layoutEmptyHistory.visibility = View.VISIBLE
            rvHistory.visibility = View.GONE
            tvCountOnTime.text = "0"
            tvCountLate.text = "0"
            tvCountTotal.text = "0"
        } else {
            layoutEmptyHistory.visibility = View.GONE
            rvHistory.visibility = View.VISIBLE

            var onTime = 0
            var late = 0

            items.forEach {
                if (it.is_late || it.status == "late") {
                    late++
                } else {
                    onTime++
                }
            }

            tvCountOnTime.text = onTime.toString()
            tvCountLate.text = late.toString()
            tvCountTotal.text = items.size.toString()
        }
    }
}
