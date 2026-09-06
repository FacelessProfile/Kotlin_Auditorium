package com.example.kotlinroomdatabase.fragments.analytics

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.data.StudentDatabase
import com.example.kotlinroomdatabase.databinding.FragmentAnalyticsBinding
import com.example.kotlinroomdatabase.repository.IStudentRepository
import com.example.kotlinroomdatabase.repository.StudentRepositoryHTTPS
import com.example.kotlinroomdatabase.util.RoleUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class AnalyticsFragment : Fragment() {

    private var _binding: FragmentAnalyticsBinding? = null
    private val binding get() = _binding!!

    private lateinit var repository: IStudentRepository

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAnalyticsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val db = StudentDatabase.getInstance(requireContext())
        repository = StudentRepositoryHTTPS(requireContext(), db.studentDao())

        binding.swipeRefresh.setOnRefreshListener {
            loadAnalytics()
        }

        loadAnalytics()
    }

    private fun loadAnalytics() {
        binding.swipeRefresh.isRefreshing = true
        binding.progressLoading.visibility = View.VISIBLE
        binding.tvEmptyAnalytics.visibility = View.GONE

        val prefs = requireContext().getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
        val role = prefs.getString("user_role", "student") ?: "student"
        val isTeacher = RoleUtils.isTeacherOrHead(role)

        if (isTeacher) {
            loadTeacherAnalytics()
        } else {
            loadStudentAnalytics()
        }
    }

    private fun loadStudentAnalytics() {
        binding.tvAnalyticsHeaderTitle.text = "Аналитика посещаемости"
        binding.tvAnalyticsSubtitle.text = "Сводка успеваемости и присутствия на парах"
        binding.tvBreakdownTitle.text = "Детализация по дисциплинам"

        val currentYear = Calendar.getInstance().get(Calendar.YEAR)

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val history = repository.getStudentHistory(currentYear)
                val radar = try { repository.getStudentPerformanceRadar() } catch (_: Exception) { emptyList() }

                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    binding.swipeRefresh.isRefreshing = false
                    binding.progressLoading.visibility = View.GONE

                    val items = history.items
                    val total = items.size
                    val attended = items.count { it.status == "present" || it.status == "late" }
                    val missed = items.count { it.status == "absent" || it.status == "fraud" }
                    val rate = if (total > 0) {
                        (attended.toDouble() / total * 100).toInt()
                    } else 0

                    binding.tvStatAttendanceRate.text = "$rate%"
                    binding.tvStatTotalClasses.text = total.toString()
                    binding.tvStatPresentCount.text = attended.toString()
                    binding.tvStatMissedCount.text = missed.toString()

                    binding.containerSubjectStats.removeAllViews()

                    if (radar.isNotEmpty()) {
                        for (point in radar) {
                            val itemView = createStatRowView(point.subject_name, "${point.percent}%", point.percent)
                            binding.containerSubjectStats.addView(itemView)
                        }
                    } else if (items.isNotEmpty()) {
                        // Aggregate by subject from history items
                        val grouped = items.groupBy { it.subject_name ?: it.lesson_name ?: "Предмет" }
                        for ((subjName, subjItems) in grouped) {
                            val subjTotal = subjItems.size
                            val subjPresent = subjItems.count { it.status == "present" || it.status == "late" }
                            val pct = if (subjTotal > 0) (subjPresent * 100 / subjTotal) else 0
                            val itemView = createStatRowView(subjName, "$subjPresent/$subjTotal ($pct%)", pct)
                            binding.containerSubjectStats.addView(itemView)
                        }
                    } else {
                        binding.tvEmptyAnalytics.visibility = View.VISIBLE
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    binding.swipeRefresh.isRefreshing = false
                    binding.progressLoading.visibility = View.GONE
                    binding.tvEmptyAnalytics.text = "Не удалось загрузить данные аналитики"
                    binding.tvEmptyAnalytics.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun loadTeacherAnalytics() {
        binding.tvAnalyticsHeaderTitle.text = "Аналитика преподавателя"
        binding.tvAnalyticsSubtitle.text = "Посещаемость по преподаваемым дисциплинам"
        binding.tvBreakdownTitle.text = "Дисциплины и группы"

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val subjects = repository.getTeacherSubjects()
                var totalSessions = 0
                var attendedSessions = 0
                var totalStudentsCount = 0

                val subjectRows = mutableListOf<Triple<String, String, Int>>()

                for (subject in subjects) {
                    val groupsText = subject.groups.joinToString(", ") { it.name }
                    var subjAttended = 0
                    var subjTotal = 0

                    for (group in subject.groups) {
                        try {
                            val stats = repository.getGroupAttendance(group.id, subject.subject_id)
                            totalStudentsCount += stats.size
                            for (s in stats) {
                                subjAttended += s.attended_sessions
                                subjTotal += s.total_sessions
                            }
                        } catch (_: Exception) {}
                    }

                    val pct = if (subjTotal > 0) ((subjAttended * 100) / subjTotal).coerceIn(0, 100) else 0
                    val subText = if (subjTotal > 0) "$groupsText • $pct%" else groupsText
                    subjectRows.add(Triple(subject.subject_name, subText, if (subjTotal > 0) pct else 100))

                    attendedSessions += subjAttended
                    totalSessions += subjTotal
                }

                val overallRate = if (totalSessions > 0) ((attendedSessions * 100) / totalSessions).coerceIn(0, 100) else 0

                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    binding.swipeRefresh.isRefreshing = false
                    binding.progressLoading.visibility = View.GONE

                    binding.tvStatTotalClasses.text = subjects.size.toString()
                    binding.tvStatAttendanceRate.text = if (totalSessions > 0) "$overallRate%" else "--"
                    binding.tvStatPresentCount.text = if (totalSessions > 0) attendedSessions.toString() else "--"
                    binding.tvStatMissedCount.text = if (totalSessions > 0) (totalSessions - attendedSessions).coerceAtLeast(0).toString() else "--"

                    binding.containerSubjectStats.removeAllViews()

                    if (subjects.isEmpty()) {
                        binding.tvEmptyAnalytics.text = "Назначенные дисциплины отсутствуют"
                        binding.tvEmptyAnalytics.visibility = View.VISIBLE
                    } else {
                        for ((name, sub, progress) in subjectRows) {
                            val itemView = createStatRowView(name, sub, progress)
                            binding.containerSubjectStats.addView(itemView)
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    binding.swipeRefresh.isRefreshing = false
                    binding.progressLoading.visibility = View.GONE
                    binding.tvEmptyAnalytics.text = "Не удалось загрузить список дисциплин"
                    binding.tvEmptyAnalytics.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun createStatRowView(title: String, subtitle: String, progress: Int): View {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 16)
        }

        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val tvTitle = TextView(requireContext()).apply {
            text = title
            textSize = 14f
            setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.sib_text_primary))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvValue = TextView(requireContext()).apply {
            text = subtitle
            textSize = 13f
            setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.sib_text_secondary))
        }

        row.addView(tvTitle)
        row.addView(tvValue)

        val pb = ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            this.progress = progress.coerceIn(0, 100)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                8.dpToPx()
            ).apply {
                topMargin = 6.dpToPx()
            }
            progressTintList = android.content.res.ColorStateList.valueOf(
                androidx.core.content.ContextCompat.getColor(requireContext(), R.color.sib_blue_primary)
            )
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(
                androidx.core.content.ContextCompat.getColor(requireContext(), R.color.sib_border_subtle)
            )
        }

        layout.addView(row)
        layout.addView(pb)
        return layout
    }

    private fun Int.dpToPx(): Int {
        val density = resources.displayMetrics.density
        return (this * density).toInt()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
