package com.example.kotlinroomdatabase.fragments.history

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.kotlinroomdatabase.data.StudentDatabase
import com.example.kotlinroomdatabase.databinding.FragmentHistoryBinding
import com.example.kotlinroomdatabase.model.HistoryItem
import com.example.kotlinroomdatabase.repository.IStudentRepository
import com.example.kotlinroomdatabase.repository.StudentRepositoryHTTPS
import kotlinx.coroutines.launch

class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!

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
    ): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = HistoryAdapter()
        binding.rvHistory.adapter = adapter
        binding.rvHistory.layoutManager = LinearLayoutManager(requireContext())

        binding.swipeRefreshHistory.setOnRefreshListener {
            loadHistory()
        }

        loadHistory()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun loadHistory() {
        val studentId = arguments?.getInt("studentId") ?: 0
        val subjectId = arguments?.getInt("subjectId") ?: 0

        val prefs = requireContext().getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
        val role = prefs.getString("user_role", "student")

        binding.swipeRefreshHistory.isRefreshing = true

        lifecycleScope.launch {
            try {
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
            } catch (e: Exception) {
                displayHistory(emptyList())
            } finally {
                _binding?.swipeRefreshHistory?.isRefreshing = false
            }
        }
    }

    private fun displayHistory(items: List<HistoryItem>) {
        if (_binding == null) return

        adapter.setData(items)

        if (items.isEmpty()) {
            binding.layoutEmptyHistory.visibility = View.VISIBLE
            binding.rvHistory.visibility = View.GONE
            binding.tvOverallAttendanceRate.text = "0%"
            binding.tvCountOnTime.text = "0"
            binding.tvCountLate.text = "0"
            binding.tvCountExcused.text = "0"
            binding.tvCountAbsent.text = "0"
            binding.tvCountTotal.text = "Всего занятий в семестре: 0"
        } else {
            binding.layoutEmptyHistory.visibility = View.GONE
            binding.rvHistory.visibility = View.VISIBLE

            var onTime = 0
            var late = 0
            var excused = 0
            var absent = 0

            items.forEach { item ->
                val s = item.status?.lowercase() ?: ""
                when {
                    item.is_late || s == "late" -> late++
                    s == "excused" || s == "valid" || s == "уважительная" -> excused++
                    s == "absent" || s == "fraud" || s == "н" || s == "пропуск" -> absent++
                    else -> onTime++
                }
            }

            val total = items.size
            val rate = if (total > 0) ((onTime + late) * 100) / total else 0

            binding.tvOverallAttendanceRate.text = "$rate%"
            binding.tvCountOnTime.text = onTime.toString()
            binding.tvCountLate.text = late.toString()
            binding.tvCountExcused.text = excused.toString()
            binding.tvCountAbsent.text = absent.toString()
            binding.tvCountTotal.text = "Всего занятий в семестре: $total"
        }
    }
}
