package com.example.kotlinroomdatabase.fragments.history

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
        val rvHistory = view.findViewById<RecyclerView>(R.id.rvHistory)
        
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
                            date = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it.date)),
                            lesson_name = it.subject,
                            status = it.groups,
                            count = null
                        )
                    }
                    adapter.setData(historyItems)
                }
            } else if (studentId > 0 && subjectId > 0) {
                val response = repository.getDetailedStudentHistory(studentId, subjectId)
                adapter.setData(response)
            } else {
                val response = repository.getStudentHistory(2026)
                adapter.setData(response.items)
            }
        }
    }
}
