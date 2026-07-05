package com.example.kotlinroomdatabase.fragments.grades

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.data.StudentDatabase
import com.example.kotlinroomdatabase.model.GroupSubjectPerformanceRow
import com.example.kotlinroomdatabase.repository.IStudentRepository
import com.example.kotlinroomdatabase.repository.StudentRepositoryHTTPS
import com.example.kotlinroomdatabase.utils.GradeUtils
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.example.kotlinroomdatabase.getColorFromAttr
import kotlinx.coroutines.launch

class TeacherGradesDashboardFragment : Fragment() {

    private lateinit var pieChart: PieChart
    private lateinit var barChart: BarChart
    private lateinit var rvDashboardStudents: RecyclerView
    private lateinit var rvAssignments: RecyclerView
    private lateinit var rvGradebook: RecyclerView
    private lateinit var actvAnalyticsGroup: com.google.android.material.textfield.MaterialAutoCompleteTextView
    private lateinit var actvGradebookGrouping: com.google.android.material.textfield.MaterialAutoCompleteTextView
    private lateinit var tvDashboardSubjectName: TextView
    private lateinit var tvAvgGrade: TextView
    private lateinit var tvAvgAttendance: TextView
    private lateinit var tvTotalStudents: TextView
    private lateinit var adapter: DashboardStudentsAdapter
    private lateinit var assignmentsAdapter: AssignmentsAdapter
    private lateinit var gradebookAdapter: GradebookAccordionAdapter
    private lateinit var repository: IStudentRepository

    private lateinit var layoutGradebook: View
    private lateinit var layoutAnalytics: View
    private lateinit var layoutAssignments: View
    private lateinit var bottomNavDashboard: BottomNavigationView

    private var subjectId: Int = -1
    private var subjectName: String = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_teacher_dashboard, container, false)
        
        layoutGradebook = view.findViewById(R.id.layoutGradebook)
        layoutAnalytics = view.findViewById(R.id.layoutAnalytics)
        layoutAssignments = view.findViewById(R.id.layoutAssignments)
        bottomNavDashboard = view.findViewById(R.id.bottomNavDashboard)

        pieChart = view.findViewById(R.id.pieChart)
        barChart = view.findViewById(R.id.barChart)
        rvDashboardStudents = view.findViewById(R.id.rvDashboardStudents)
        rvAssignments = view.findViewById(R.id.rvAssignments)
        rvGradebook = view.findViewById(R.id.rvGradebook)
        actvAnalyticsGroup = view.findViewById(R.id.actvAnalyticsGroup)
        actvGradebookGrouping = view.findViewById(R.id.actvGradebookGrouping)
        tvDashboardSubjectName = view.findViewById(R.id.tvDashboardSubjectName)
        tvAvgGrade = view.findViewById(R.id.tvAvgGrade)
        tvAvgAttendance = view.findViewById(R.id.tvAvgAttendance)
        tvTotalStudents = view.findViewById(R.id.tvTotalStudents)

        subjectId = arguments?.getInt("subject_id", -1) ?: -1
        subjectName = arguments?.getString("subject_name", "") ?: ""
        tvDashboardSubjectName.text = subjectName

        // Enable gradebook grouping by group
        view.findViewById<TextView>(R.id.tvDashboardSubjectName).let {
            val parentLayout = (actvGradebookGrouping.parent as? View)?.parent as? View
            parentLayout?.visibility = View.VISIBLE
        }

        setupBottomNavigation()

        rvDashboardStudents.layoutManager = LinearLayoutManager(requireContext())
        adapter = DashboardStudentsAdapter(emptyList()) { student ->
            showEditGradeBottomSheet(student.student_id, student.student_name)
        }
        rvDashboardStudents.adapter = adapter

        rvAssignments.layoutManager = LinearLayoutManager(requireContext())
        assignmentsAdapter = AssignmentsAdapter(emptyList()) { assignment ->
            val dialog = GradeItemDialogFragment().apply {
                arguments = Bundle().apply {
                    putInt("subject_id", subjectId)
                    putInt("item_id", assignment.item_id)
                    putString("title", assignment.title)
                    putInt("max_score", assignment.max_score)
                    putString("item_type", assignment.item_type)
                    putString("deadline", assignment.deadline ?: "")
                }
            }
            dialog.show(parentFragmentManager, "GradeItemDialogFragment")
        }
        rvAssignments.adapter = assignmentsAdapter

        rvGradebook.layoutManager = LinearLayoutManager(requireContext())
        gradebookAdapter = GradebookAccordionAdapter(
            students = emptyList(),
            onLoadGrades = { studentId, callback ->
                lifecycleScope.launch {
                    val gradesResp = repository.getTeacherStudentGrades(studentId, subjectId)
                    val combinedList = gradesResp?.grades?.toMutableList() ?: mutableListOf()
                    val rewardsList = gradesResp?.rewards ?: emptyList()
                    rewardsList.forEach { r ->
                        combinedList.add(
                            com.example.kotlinroomdatabase.model.StudentGradePoint(
                                item_id = -1,
                                title = if (r.score > 0) "Поощрение" else "Наказание",
                                max_score = kotlin.math.abs(r.score),
                                item_type = "reward",
                                score = r.score,
                                graded_at = r.created_at,
                                comment = r.reason
                            )
                        )
                    }
                    callback(combinedList)
                }
            },
            onGradeStudent = { student ->
                showEditGradeBottomSheet(student.student_id, student.student_name)
            },
            onEditSpecificGrade = { gradePoint, student ->
                showEditGradeBottomSheet(student.student_id, student.student_name, gradePoint.item_id, gradePoint.score, gradePoint.comment)
            }
        )
        rvGradebook.adapter = gradebookAdapter

        val fabAddGradeItem = view.findViewById<View>(R.id.fabAddGradeItem)
        fabAddGradeItem.setOnClickListener {
            val dialog = GradeItemDialogFragment().apply {
                arguments = Bundle().apply { putInt("subject_id", subjectId) }
            }
            dialog.show(parentFragmentManager, "GradeItemDialogFragment")
        }

        val db = StudentDatabase.getInstance(requireContext())
        repository = StudentRepositoryHTTPS(requireContext(), db.studentDao())

        setupPieChart()
        setupBarChart()
        loadGroupsAndData()
        
        // Load default tab
        bottomNavDashboard.selectedItemId = R.id.nav_gradebook

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        parentFragmentManager.setFragmentResultListener("request_refresh", viewLifecycleOwner) { _, _ ->
            refreshData()
        }
    }

    private fun showEditGradeBottomSheet(studentId: Int, studentName: String, preselectedItemId: Int? = null, preselectedScore: Int? = null, preselectedComment: String? = null) {
        val bottomSheet = EditGradeBottomSheet().apply {
            arguments = Bundle().apply {
                putInt("student_id", studentId)
                putInt("subject_id", subjectId)
                putString("student_name", studentName)
                if (preselectedItemId != null) {
                    putInt("preselected_item_id", preselectedItemId)
                }
                if (preselectedScore != null) {
                    putInt("preselected_score", preselectedScore)
                }
                if (preselectedComment != null) {
                    putString("preselected_comment", preselectedComment)
                }
            }
        }
        bottomSheet.show(parentFragmentManager, "EditGradeBottomSheet")
    }

    private fun setupBottomNavigation() {
        bottomNavDashboard.setOnItemSelectedListener { item ->
            layoutGradebook.visibility = View.GONE
            layoutAnalytics.visibility = View.GONE
            layoutAssignments.visibility = View.GONE

            when (item.itemId) {
                R.id.nav_gradebook -> layoutGradebook.visibility = View.VISIBLE
                R.id.nav_analytics -> layoutAnalytics.visibility = View.VISIBLE
                R.id.nav_assignments -> {
                    layoutAssignments.visibility = View.VISIBLE
                    loadAssignments()
                }
            }
            true
        }
    }

    private fun loadAssignments() {
        lifecycleScope.launch {
            try {
                val assignments = repository.getTeacherGradeItems(subjectId)
                assignmentsAdapter.updateData(assignments)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Ошибка загрузки заданий", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupPieChart() {
        pieChart.apply {
            description.isEnabled = false
            isDrawHoleEnabled = true
            setHoleColor(android.graphics.Color.TRANSPARENT)
            legend.isEnabled = true
        }
    }

    private fun setupBarChart() {
        val labelColor = requireContext().getColorFromAttr(android.R.attr.textColorPrimary)
        barChart.apply {
            description.isEnabled = false
            setDrawGridBackground(false)
            setDrawBarShadow(false)
            setFitBars(true)
            
            xAxis.apply {
                position = com.github.mikephil.charting.components.XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
                textColor = labelColor
                labelRotationAngle = -45f
            }
            
            axisLeft.apply {
                axisMinimum = 0f
                axisMaximum = 100f
                textColor = labelColor
            }
            axisRight.isEnabled = false
            legend.apply {
                textColor = labelColor
            }
        }
    }

    fun refreshData() {
        loadGroupsAndData()
    }

    private fun loadGroupsAndData() {
        lifecycleScope.launch {
            try {
                val subjects = repository.getTeacherSubjects()
                val currentSubject = subjects.find { it.subject_id == subjectId }
                
                if (currentSubject != null && currentSubject.groups.isNotEmpty()) {
                    val groups = currentSubject.groups
                    val groupNames = groups.map { it.name }
                    
                    val spinnerAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, groupNames)
                    actvAnalyticsGroup.setAdapter(spinnerAdapter)
                    actvAnalyticsGroup.setText(groupNames.firstOrNull(), false)

                    actvAnalyticsGroup.setOnItemClickListener { _, _, _, _ ->
                        val selectedName = actvAnalyticsGroup.text.toString()
                        val group = groups.find { it.name == selectedName }
                        group?.let { loadGroupPerformance(it.id) }
                    }

                    // Enable group switching for Gradebook too
                    actvGradebookGrouping.setAdapter(spinnerAdapter)
                    actvGradebookGrouping.setText(groupNames.firstOrNull(), false)
                    actvGradebookGrouping.setOnItemClickListener { _, _, _, _ ->
                        val selectedName = actvGradebookGrouping.text.toString()
                        val group = groups.find { it.name == selectedName }
                        group?.let { loadGroupPerformance(it.id) }
                    }
                    
                    loadGroupPerformance(groups[0].id)
                } else {
                    Toast.makeText(requireContext(), "Нет привязанных групп", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Ошибка загрузки данных", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadGroupPerformance(groupId: Int) {
        lifecycleScope.launch {
            try {
                val data = repository.getTeacherGroupSubjectPerformance(groupId, subjectId)
                adapter.updateData(data)
                gradebookAdapter.updateData(data)
                updateAnalyticsDashboard(data)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Ошибка загрузки успеваемости группы", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateAnalyticsDashboard(data: List<GroupSubjectPerformanceRow>) {
        updatePieChart(data)
        updateBarChartAndMetrics(data)
    }

    private fun updateBarChartAndMetrics(data: List<GroupSubjectPerformanceRow>) {
        if (data.isEmpty()) {
            barChart.clear()
            tvAvgGrade.text = "0%"
            tvAvgAttendance.text = "0%"
            tvTotalStudents.text = "0"
            return
        }

        var totalGradePct = 0
        var totalAttendancePct = 0.0
        val gradeEntries = mutableListOf<com.github.mikephil.charting.data.BarEntry>()
        val attendanceEntries = mutableListOf<com.github.mikephil.charting.data.BarEntry>()
        val studentNames = mutableListOf<String>()

        data.forEachIndexed { index, row ->
            val gradePct = row.percent
            val attPct = if (row.total_sessions > 0) (row.attended_sessions.toDouble() * 100.0 / row.total_sessions.toDouble()) else 100.0
            
            totalGradePct += gradePct
            totalAttendancePct += attPct

            gradeEntries.add(com.github.mikephil.charting.data.BarEntry(index.toFloat(), gradePct.toFloat()))
            attendanceEntries.add(com.github.mikephil.charting.data.BarEntry(index.toFloat(), attPct.toFloat()))
            
            // Shorten name to just last name or first few chars
            val shortName = row.student_name.split(" ").firstOrNull() ?: row.student_name
            studentNames.add(shortName)
        }

        val avgGrade = totalGradePct / data.size
        val avgAtt = (totalAttendancePct / data.size).toInt()

        tvAvgGrade.text = "$avgGrade%"
        tvAvgAttendance.text = "$avgAtt%"
        tvTotalStudents.text = data.size.toString()

        val gradeSet = com.github.mikephil.charting.data.BarDataSet(gradeEntries, "Успеваемость (%)").apply {
            color = android.graphics.Color.parseColor("#4CAF50") // Green
        }
        val attendanceSet = com.github.mikephil.charting.data.BarDataSet(attendanceEntries, "Посещаемость (%)").apply {
            color = android.graphics.Color.parseColor("#2196F3") // Blue
        }

        val barData = com.github.mikephil.charting.data.BarData(gradeSet, attendanceSet)
        // Group bars
        val groupSpace = 0.08f
        val barSpace = 0.03f
        val barWidth = 0.43f
        barData.barWidth = barWidth
        
        // Use animation and values mapping for better UI
        barChart.animateY(1000)
        barChart.data = barData
        barChart.groupBars(0f, groupSpace, barSpace)
        barChart.setDrawValueAboveBar(true)
        
        barChart.xAxis.apply {
            valueFormatter = com.github.mikephil.charting.formatter.IndexAxisValueFormatter(studentNames)
            axisMinimum = 0f
            axisMaximum = data.size.toFloat()
            granularity = 1f
            isGranularityEnabled = true
        }

        barChart.invalidate()
    }

    private fun updatePieChart(data: List<GroupSubjectPerformanceRow>) {
        if (data.isEmpty()) {
            pieChart.clear()
            return
        }

        var excellent = 0
        var good = 0
        var ok = 0
        var alarm = 0

        for (student in data) {
            val safeTotal = if (student.total_max > 0) student.total_max else 1
            val calculatedPercent = (student.current_score * 100) / safeTotal

            when {
                calculatedPercent >= 80 -> excellent++
                calculatedPercent >= 70 -> good++
                calculatedPercent >= 50 -> ok++
                else -> alarm++
            }
        }

        val entries = mutableListOf<PieEntry>()
        val colors = mutableListOf<Int>()

        if (excellent > 0) {
            entries.add(PieEntry(excellent.toFloat(), "Отлично (>=80%)"))
            colors.add(GradeUtils.getGradeColor(85))
        }
        if (good > 0) {
            entries.add(PieEntry(good.toFloat(), "Хорошо (70-79%)"))
            colors.add(GradeUtils.getGradeColor(75))
        }
        if (ok > 0) {
            entries.add(PieEntry(ok.toFloat(), "Удовл (50-69%)"))
            colors.add(GradeUtils.getGradeColor(60))
        }
        if (alarm > 0) {
            entries.add(PieEntry(alarm.toFloat(), "Тревога (<50%)"))
            colors.add(GradeUtils.getGradeColor(40))
        }

        val dataSet = PieDataSet(entries, "Зоны успеваемости").apply {
            this.colors = colors
            valueTextSize = 16f
            valueTextColor = android.graphics.Color.WHITE
            sliceSpace = 2f
            selectionShift = 8f
        }

        pieChart.data = PieData(dataSet)
        pieChart.animateY(1200, com.github.mikephil.charting.animation.Easing.EaseInOutCubic)
        pieChart.invalidate()
    }
}
