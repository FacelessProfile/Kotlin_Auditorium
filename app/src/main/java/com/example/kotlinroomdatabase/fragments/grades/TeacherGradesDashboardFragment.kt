package com.example.kotlinroomdatabase.fragments.grades

import android.content.Intent
import android.util.Log
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.data.StudentDatabase
import com.example.kotlinroomdatabase.repository.GenericResult
import com.example.kotlinroomdatabase.model.GradeItem
import com.example.kotlinroomdatabase.model.GroupSubjectPerformanceRow
import com.example.kotlinroomdatabase.model.SemesterInfo
import com.example.kotlinroomdatabase.model.StudentGradePoint
import com.example.kotlinroomdatabase.utils.GradeUtils
import com.example.kotlinroomdatabase.repository.IStudentRepository
import com.example.kotlinroomdatabase.repository.StudentRepositoryHTTPS
import com.google.android.material.button.MaterialButton
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import kotlinx.coroutines.launch
import java.io.File

class TeacherGradesDashboardFragment : Fragment() {

    private lateinit var rvDashboardStudents: RecyclerView
    private lateinit var rvAssignments: RecyclerView
    private lateinit var rvGradebook: RecyclerView
    private lateinit var actvAnalyticsGroup: MaterialAutoCompleteTextView
    private lateinit var actvGradebookGrouping: MaterialAutoCompleteTextView
    private lateinit var actvSemester: MaterialAutoCompleteTextView
    private lateinit var tvDashboardSubjectName: TextView
    private lateinit var tvAvgGrade: TextView
    private lateinit var tvAvgAttendance: TextView
    private lateinit var tvTotalStudents: TextView
    private lateinit var btnExportPDF: MaterialButton
    private lateinit var btnExportXLSX: MaterialButton

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
    private var selectedSemesterId: Int? = null
    private var availableSemesters: List<SemesterInfo> = emptyList()
    private var currentGroupId: Int = -1
    private var semesterListenersInitialized = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_teacher_dashboard, container, false)
        
        layoutGradebook = view.findViewById(R.id.layoutGradebook)
        layoutAnalytics = view.findViewById(R.id.layoutAnalytics)
        layoutAssignments = view.findViewById(R.id.layoutAssignments)
        bottomNavDashboard = view.findViewById(R.id.bottomNavDashboard)

        rvDashboardStudents = view.findViewById(R.id.rvDashboardStudents)
        rvAssignments = view.findViewById(R.id.rvAssignments)
        rvGradebook = view.findViewById(R.id.rvGradebook)
        actvAnalyticsGroup = view.findViewById(R.id.actvAnalyticsGroup)
        actvGradebookGrouping = view.findViewById(R.id.actvGradebookGrouping)
        actvSemester = view.findViewById(R.id.actvSemester)
        tvDashboardSubjectName = view.findViewById(R.id.tvDashboardSubjectName)
        tvAvgGrade = view.findViewById(R.id.tvAvgGrade)
        tvAvgAttendance = view.findViewById(R.id.tvAvgAttendance)
        tvTotalStudents = view.findViewById(R.id.tvTotalStudents)

        btnExportPDF = view.findViewById(R.id.btnExportPDF)
        btnExportXLSX = view.findViewById(R.id.btnExportXLSX)

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
        assignmentsAdapter = AssignmentsAdapter(
            emptyList(),
            onClick = { assignment ->
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
            },
            onDeleteClick = { assignment ->
                confirmDeleteGradeItem(assignment)
            }
        )
        rvAssignments.adapter = assignmentsAdapter

        rvGradebook.layoutManager = LinearLayoutManager(requireContext())
        gradebookAdapter = GradebookAccordionAdapter(
            students = emptyList(),
            onLoadGrades = { studentId, callback ->
                lifecycleScope.launch {
                    val gradesResp = repository.getTeacherStudentGrades(studentId, subjectId, selectedSemesterId)
                    val combinedList = gradesResp?.grades?.toMutableList() ?: mutableListOf()
                    val rewardsList = gradesResp?.rewards ?: emptyList()
                    rewardsList.forEach { r ->
                        combinedList.add(
                            StudentGradePoint(
                                grade_id = r.id.toLong(),
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
            },
            onDeleteSpecificGrade = { gradePoint, student ->
                confirmDeleteGrade(gradePoint, student)
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

        btnExportPDF.setOnClickListener { downloadReport("pdf") }
        btnExportXLSX.setOnClickListener { downloadReport("xlsx") }

        val db = StudentDatabase.getInstance(requireContext())
        repository = StudentRepositoryHTTPS(requireContext(), db.studentDao())

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

    private fun confirmDeleteGrade(gradePoint: StudentGradePoint, student: GroupSubjectPerformanceRow) {
        val gradeId = gradePoint.grade_id
        if (gradeId == null || gradeId <= 0) {
            Toast.makeText(requireContext(), "Невозможно удалить эту запись", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Удаление оценки")
            .setMessage("Удалить оценку за '${gradePoint.title}' для студента ${student.student_name}?")
            .setPositiveButton("Удалить") { _, _ ->
                lifecycleScope.launch {
                    val res = repository.deleteTeacherGrade(gradeId)
                    if (res is GenericResult.Success) {
                        Toast.makeText(requireContext(), "Оценка удалена", Toast.LENGTH_SHORT).show()
                        refreshData()
                    } else if (res is GenericResult.Error) {
                        Toast.makeText(requireContext(), "Ошибка: ${res.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun confirmDeleteGradeItem(assignment: GradeItem) {
        AlertDialog.Builder(requireContext())
            .setTitle("Удаление задания")
            .setMessage("Удалить задание '${assignment.title}' и все выставленные за него оценки?")
            .setPositiveButton("Удалить") { _, _ ->
                lifecycleScope.launch {
                    val res = repository.deleteTeacherGradeItem(assignment.item_id.toLong())
                    if (res is GenericResult.Success) {
                        Toast.makeText(requireContext(), "Задание удалено", Toast.LENGTH_SHORT).show()
                        loadAssignments()
                        refreshData()
                    } else if (res is GenericResult.Error) {
                        Toast.makeText(requireContext(), "Ошибка: ${res.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun downloadReport(format: String) {
        lifecycleScope.launch {
            Toast.makeText(requireContext(), "Скачивание отчета ($format)...", Toast.LENGTH_SHORT).show()
            val ext = if (format.lowercase().contains("pdf")) "pdf" else "xlsx"
            val file = File(requireContext().getExternalFilesDir(null), "performance_report.$ext")
            val res = repository.downloadPerformanceReport(ext, selectedSemesterId, file)
            if (res is GenericResult.Success) {
                Toast.makeText(requireContext(), "Отчет сохранен: ${res.data.name}", Toast.LENGTH_LONG).show()
                openReportFile(res.data, if (ext == "pdf") "application/pdf" else "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
            } else if (res is GenericResult.Error) {
                Toast.makeText(requireContext(), "Ошибка экспорта: ${res.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openReportFile(file: File, mimeType: String) {
        try {
            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Файл сохранен в ${file.absolutePath}", Toast.LENGTH_SHORT).show()
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
                val assignments = repository.getTeacherGradeItems(subjectId, selectedSemesterId)
                assignmentsAdapter.updateData(assignments)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Ошибка загрузки заданий", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun refreshData() {
        // Don't reload semesters/groups — just reload performance data
        // with the currently selected semester and group
        if (currentGroupId > 0) {
            loadGroupPerformance(currentGroupId)
        }
        if (layoutAssignments.visibility == View.VISIBLE) {
            loadAssignments()
        }
    }

    private fun onSemesterSelected(semester: SemesterInfo) {
        Log.d("SEMESTER_SWITCH", "=== SEMESTER CHANGED === to: ${semester.name} (id=${semester.id})")
        selectedSemesterId = semester.id

        // Immediately clear UI to show something is happening
        adapter.updateData(emptyList())
        gradebookAdapter.updateData(emptyList())
        updateAnalyticsMetrics(emptyList())

        // Reload data with the new semester
        if (currentGroupId > 0) {
            loadGroupPerformance(currentGroupId)
        }
        if (layoutAssignments.visibility == View.VISIBLE) {
            loadAssignments()
        }
    }

    private fun loadGroupsAndData() {
        lifecycleScope.launch {
            try {
                // --- Load semesters (only set up listeners once) ---
                val semRes = repository.getSemesters()
                if (semRes is GenericResult.Success && semRes.data.isNotEmpty()) {
                    availableSemesters = semRes.data
                    val semNames = availableSemesters.map { it.name }

                    if (!semesterListenersInitialized) {
                        // First time: pick the current semester
                        val curr = availableSemesters.find { it.is_current } ?: availableSemesters.first()
                        selectedSemesterId = curr.id

                        // Set up the adapter FIRST, then set text
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
                        actvSemester.setText(curr.name, false)

                        actvSemester.setOnItemClickListener { _, _, position, _ ->
                            if (position in semNames.indices) {
                                val selectedName = semNames[position]
                                val sem = availableSemesters.find { it.name == selectedName }
                                if (sem != null && sem.id != selectedSemesterId) {
                                    Toast.makeText(requireContext(), "Семестр: ${sem.name}", Toast.LENGTH_SHORT).show()
                                    onSemesterSelected(sem)
                                }
                            }
                        }
                        semesterListenersInitialized = true
                    }
                    // If listeners already initialized, don't touch them or selectedSemesterId
                }

                // --- Load groups ---
                val subjects = repository.getTeacherSubjects()
                val currentSubject = subjects.find { it.subject_id == subjectId }
                
                if (currentSubject != null && currentSubject.groups.isNotEmpty()) {
                    val groups = currentSubject.groups
                    val groupNames = groups.map { it.name }
                    
                    val groupAdapter = object : ArrayAdapter<String>(
                        requireContext(),
                        android.R.layout.simple_dropdown_item_1line,
                        groupNames
                    ) {
                        private val noFilter = object : android.widget.Filter() {
                            override fun performFiltering(constraint: CharSequence?): FilterResults {
                                return FilterResults().apply {
                                    values = groupNames
                                    count = groupNames.size
                                }
                            }
                            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                                notifyDataSetChanged()
                            }
                        }
                        override fun getFilter(): android.widget.Filter = noFilter
                    }

                    actvAnalyticsGroup.setAdapter(groupAdapter)
                    actvAnalyticsGroup.setText(groupNames.firstOrNull(), false)
                    actvAnalyticsGroup.setOnItemClickListener { _, _, position, _ ->
                        if (position in groupNames.indices) {
                            val group = groups.find { it.name == groupNames[position] }
                            group?.let { loadGroupPerformance(it.id) }
                        }
                    }

                    // Gradebook group selector shares same data
                    val groupAdapter2 = object : ArrayAdapter<String>(
                        requireContext(),
                        android.R.layout.simple_dropdown_item_1line,
                        groupNames
                    ) {
                        private val noFilter = object : android.widget.Filter() {
                            override fun performFiltering(constraint: CharSequence?): FilterResults {
                                return FilterResults().apply {
                                    values = groupNames
                                    count = groupNames.size
                                }
                            }
                            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                                notifyDataSetChanged()
                            }
                        }
                        override fun getFilter(): android.widget.Filter = noFilter
                    }
                    actvGradebookGrouping.setAdapter(groupAdapter2)
                    actvGradebookGrouping.setText(groupNames.firstOrNull(), false)
                    actvGradebookGrouping.setOnItemClickListener { _, _, position, _ ->
                        if (position in groupNames.indices) {
                            val group = groups.find { it.name == groupNames[position] }
                            group?.let { loadGroupPerformance(it.id) }
                        }
                    }
                    
                    loadGroupPerformance(groups[0].id)
                } else {
                    Toast.makeText(requireContext(), "Нет привязанных групп", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("SEMESTER_SWITCH", "Error in loadGroupsAndData", e)
                Toast.makeText(requireContext(), "Ошибка загрузки данных", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadGroupPerformance(groupId: Int) {
        currentGroupId = groupId
        lifecycleScope.launch {
            try {
                Log.d("SEMESTER_SWITCH", "loadGroupPerformance groupId=$groupId, subjectId=$subjectId, semesterId=$selectedSemesterId")
                val data = repository.getTeacherGroupSubjectPerformance(groupId, subjectId, selectedSemesterId)
                Log.d("SEMESTER_SWITCH", "Got ${data.size} rows for semester $selectedSemesterId")
                adapter.updateData(data)
                gradebookAdapter.updateData(data)
                updateAnalyticsMetrics(data)
            } catch (e: Exception) {
                Log.e("SEMESTER_SWITCH", "loadGroupPerformance error", e)
                Toast.makeText(requireContext(), "Ошибка загрузки успеваемости группы", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateAnalyticsMetrics(data: List<GroupSubjectPerformanceRow>) {
        if (data.isEmpty()) {
            tvAvgGrade.text = "0%"
            tvAvgAttendance.text = "0%"
            tvTotalStudents.text = "0"
            return
        }

        var totalGradePct = 0
        var totalAttendancePct = 0.0

        data.forEach { row ->
            val gradePct = row.percent
            val attPct = if (row.total_sessions > 0) (row.attended_sessions.toDouble() * 100.0 / row.total_sessions.toDouble()) else 0.0
            
            totalGradePct += gradePct
            totalAttendancePct += attPct
        }

        val avgGrade = totalGradePct / data.size
        val avgAtt = (totalAttendancePct / data.size).toInt()

        tvAvgGrade.text = "$avgGrade%"
        tvAvgAttendance.text = "$avgAtt%"
        tvTotalStudents.text = data.size.toString()
    }
}
