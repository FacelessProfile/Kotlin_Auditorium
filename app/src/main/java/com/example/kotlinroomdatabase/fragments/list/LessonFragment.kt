package com.example.kotlinroomdatabase.fragments.list

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.nfc.NfcAdapter
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.Filter
import android.widget.ImageView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.data.StudentDatabase
import com.example.kotlinroomdatabase.databinding.FragmentLessonBinding
import com.example.kotlinroomdatabase.fragments.QR.GenQR
import com.example.kotlinroomdatabase.fragments.nfc.NFC_Tools
import com.example.kotlinroomdatabase.model.AttendanceRosterStudent
import com.example.kotlinroomdatabase.repository.AttendanceLinkResult
import com.example.kotlinroomdatabase.repository.AttendanceResult
import com.example.kotlinroomdatabase.repository.FinishLessonResult
import com.example.kotlinroomdatabase.repository.GenericResult
import com.example.kotlinroomdatabase.repository.IStudentRepository
import com.example.kotlinroomdatabase.repository.StudentRepositoryHTTPS
import com.example.kotlinroomdatabase.settings.LessonsConfig
import com.example.kotlinroomdatabase.settings.RepositoryZMQ
import com.google.android.gms.location.LocationServices
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.InternalSerializationApi

class LessonFragment : NFC_Tools() {

    private var _binding: FragmentLessonBinding? = null
    private val binding get() = _binding!!

    private lateinit var studentRepository: IStudentRepository
    private lateinit var rosterAdapter: TeacherRosterAdapter

    private var selectedGroups = mutableSetOf<String>()
    private var currentLessonId: Int? = null
    private var isLessonActive = false
    private var currentSubject: String? = null
    private var pollingJob: Job? = null
    private var qrUpdateJob: Job? = null

    private val currentRosterList = mutableListOf<AttendanceRosterStudent>()

    private val LESSON_PREFS = "lesson_active_prefs"
    private val KEY_LESSON_ID = "lesson_id"
    private val KEY_SUBJECT = "subject"
    private val KEY_START_TIME = "start_time"
    private val KEY_SELECTED_GROUPS = "selected_groups"
    private val LESSON_DURATION_MS = 90 * 60 * 1000L // 1.5 hours

    @OptIn(InternalSerializationApi::class)
    override fun onAttach(context: Context) {
        super.onAttach(context)
        val useHttp = true
        if (useHttp) {
            val db = StudentDatabase.getInstance(requireContext())
            studentRepository = StudentRepositoryHTTPS(requireContext(), db.studentDao())
        } else {
            studentRepository = RepositoryZMQ.getStudentRepository(requireContext())
        }

        nfcAdapter = NfcAdapter.getDefaultAdapter(context)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLessonBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rosterAdapter = TeacherRosterAdapter(
            onStatusChanged = { student, newStatus ->
                handleManualStatusChange(student, newStatus)
            },
            onFraudClicked = { student ->
                showFraudDialog(student)
            }
        )

        binding.recyclerViewAttendance.adapter = rosterAdapter
        binding.recyclerViewAttendance.layoutManager = LinearLayoutManager(requireContext())

        binding.btnCreateLessonHero.setOnClickListener { showCreateLessonSheet() }
        binding.btnNoLessonAllStudents.setOnClickListener {
            findNavController().navigate(R.id.action_lessonFragment_to_listFragment)
        }
        binding.btnViewAllStudents.setOnClickListener {
            findNavController().navigate(R.id.action_lessonFragment_to_listFragment)
        }

        binding.btnFinishLesson.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Завершить занятие?")
                .setMessage("Вы действительно хотите завершить текущее занятие?")
                .setPositiveButton("Да") { _, _ -> finishCurrentLesson() }
                .setNegativeButton("Отмена", null)
                .show()
        }

        // Tap on QR code to view enlarged dialog
        binding.cardQrContainer.setOnClickListener {
            showEnlargedQrDialog()
        }

        loadLessonState()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        _binding = null
    }

    private fun handleManualStatusChange(student: AttendanceRosterStudent, newStatus: String) {
        val lessonId = currentLessonId ?: return
        val prevStatus = student.status

        // Optimistic UI update
        rosterAdapter.updateStudentStatus(student.student_id, newStatus)

        val idx = currentRosterList.indexOfFirst { it.student_id == student.student_id }
        if (idx != -1) {
            currentRosterList[idx] = currentRosterList[idx].copy(status = newStatus)
            updateRosterCounts(currentRosterList)
        }

        lifecycleScope.launch {
            val res = studentRepository.teacherMarkAttendance(lessonId, student.student_id, newStatus)
            if (res is GenericResult.Error) {
                Toast.makeText(requireContext(), "Ошибка: ${res.message}", Toast.LENGTH_SHORT).show()
                // Rollback
                rosterAdapter.updateStudentStatus(student.student_id, prevStatus)
                if (idx != -1) {
                    currentRosterList[idx] = currentRosterList[idx].copy(status = prevStatus)
                    updateRosterCounts(currentRosterList)
                }
            }
        }
    }

    private fun showFraudDialog(student: AttendanceRosterStudent) {
        AlertDialog.Builder(requireContext())
            .setTitle("⚠ Подозрение на антифрод")
            .setMessage(
                "Студент: ${student.student_name}\n" +
                "Группа: ${student.group_name}\n" +
                "Причина: ${student.fraud_reason.ifBlank { "Подозрительное устройство, повторный токен или несовпадение геолокации" }}"
            )
            .setPositiveButton("Понятно", null)
            .show()
    }

    private fun showEnlargedQrDialog() {
        val currentBitmap = (binding.ivQrCode.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            ?: return

        val dialog = AlertDialog.Builder(requireContext()).create()
        val imageView = ImageView(requireContext()).apply {
            setImageBitmap(currentBitmap)
            adjustViewBounds = true
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.WHITE)
        }
        dialog.setView(imageView)
        dialog.show()
    }

    private fun updateRosterCounts(students: List<AttendanceRosterStudent>) {
        if (_binding == null) return
        val marked = students.count { it.status.lowercase() in listOf("present", "ontime", "late") }
        val total = students.size
        binding.tvLivePresentCount.text = "👥 $marked"
        binding.tvPresentCounterBadge.text = if (total > 0) "$marked / $total" else "$marked"

        if (students.isEmpty()) {
            binding.layoutEmptyAttendance.visibility = View.VISIBLE
            binding.recyclerViewAttendance.visibility = View.GONE
        } else {
            binding.layoutEmptyAttendance.visibility = View.GONE
            binding.recyclerViewAttendance.visibility = View.VISIBLE
        }
    }

    private fun startAttendancePolling() {
        pollingJob?.cancel()
        pollingJob = lifecycleScope.launch(Dispatchers.Main) {
            while (isLessonActive) {
                val lessonId = currentLessonId ?: break
                try {
                    // 1. Fetch live attendance roster from server
                    val rosterResult = studentRepository.getAttendanceSessionRoster(lessonId)
                    if (rosterResult is GenericResult.Success) {
                        val rosterData = rosterResult.data
                        currentRosterList.clear()
                        currentRosterList.addAll(rosterData.students)
                        rosterAdapter.setData(rosterData.students)

                        val marked = rosterData.marked_count
                        val total = rosterData.roster_size
                        binding.tvLivePresentCount.text = "👥 $marked"
                        binding.tvPresentCounterBadge.text = "$marked / $total"

                        if (rosterData.students.isEmpty()) {
                            binding.layoutEmptyAttendance.visibility = View.VISIBLE
                            binding.recyclerViewAttendance.visibility = View.GONE
                        } else {
                            binding.layoutEmptyAttendance.visibility = View.GONE
                            binding.recyclerViewAttendance.visibility = View.VISIBLE
                        }
                    }

                    // 2. Fetch session timer
                    val timerResult = studentRepository.getSessionTimer(lessonId)
                    if (timerResult is GenericResult.Success) {
                        val remainingSec = timerResult.data
                        if (remainingSec > 0) {
                            val mins = remainingSec / 60
                            binding.tvSessionTimer.text = "⏳ $mins мин"
                            binding.tvSessionTimer.visibility = View.VISIBLE
                        } else {
                            binding.tvSessionTimer.text = "⏳ Завершено"
                            binding.tvSessionTimer.visibility = View.VISIBLE
                        }
                    }

                    // 3. Local sync in background
                    withContext(Dispatchers.IO) {
                        studentRepository.syncAllStudents()
                    }
                } catch (e: Exception) {
                    Log.e("POLLING", "Sync failed: ${e.message}")
                }
                delay(5000)
            }
        }
    }

    private fun saveLessonState(id: Int, subject: String, groups: List<String>) {
        val prefs = requireContext().getSharedPreferences(LESSON_PREFS, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putInt(KEY_LESSON_ID, id)
            putString(KEY_SUBJECT, subject)
            putLong(KEY_START_TIME, System.currentTimeMillis())
            putStringSet(KEY_SELECTED_GROUPS, groups.toSet())
            apply()
        }
    }

    private fun loadLessonState() {
        val prefs = requireContext().getSharedPreferences(LESSON_PREFS, Context.MODE_PRIVATE)
        val id = prefs.getInt(KEY_LESSON_ID, -1)
        val subject = prefs.getString(KEY_SUBJECT, null)
        val startTime = prefs.getLong(KEY_START_TIME, 0)
        val groups = prefs.getStringSet(KEY_SELECTED_GROUPS, null)

        if (id != -1 && subject != null && (System.currentTimeMillis() - startTime) < LESSON_DURATION_MS) {
            currentLessonId = id
            currentSubject = subject
            selectedGroups = groups?.toMutableSet() ?: mutableSetOf()
            isLessonActive = true

            updateUiOnLessonStart(subject, null)
        } else {
            checkServerActiveSession()
        }
    }

    private fun checkServerActiveSession() {
        lifecycleScope.launch {
            try {
                val res = studentRepository.getTeacherActiveSession()
                if (res is GenericResult.Success) {
                    val info = res.data
                    if (info.isActive) {
                        currentLessonId = info.lessonId
                        currentSubject = info.subjectName
                        selectedGroups = info.groupNames.toMutableSet()
                        isLessonActive = true

                        saveLessonState(info.lessonId, info.subjectName, info.groupNames)
                        updateUiOnLessonStart(info.subjectName, null)
                        return@launch
                    }
                }
            } catch (e: Exception) {
                Log.e("LessonFragment", "Error restoring active session from server", e)
            }
            if (!isLessonActive) {
                resetUiAfterLesson()
            }
        }
    }

    private fun clearSavedLessonState() {
        val prefs = requireContext().getSharedPreferences(LESSON_PREFS, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }

    private val nfcStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            if (action == NfcAdapter.ACTION_ADAPTER_STATE_CHANGED) {
                val state = intent.getIntExtra(NfcAdapter.EXTRA_ADAPTER_STATE, NfcAdapter.STATE_OFF)
                if (state == NfcAdapter.STATE_ON || state == NfcAdapter.STATE_OFF) {
                    updateNfcStatusUI()
                }
            }
        }
    }

    private fun finishCurrentLesson() {
        val lessonId = currentLessonId ?: return
        lifecycleScope.launch {
            val result = studentRepository.finishLesson(lessonId)

            when (result) {
                is FinishLessonResult.Success -> {
                    Toast.makeText(context, "Занятие завершено!", Toast.LENGTH_LONG).show()
                    androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(requireContext())
                        .sendBroadcast(Intent("LESSON_FINISHED_EVENT"))
                    stopNfcReadingMode()
                    pollingJob?.cancel()
                    clearSavedLessonState()
                    resetUiAfterLesson()
                }
                is FinishLessonResult.Error -> {
                    Toast.makeText(context, "Ошибка: ${result.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun resetUiAfterLesson() {
        if (_binding == null) return
        isLessonActive = false
        currentLessonId = null
        currentRosterList.clear()
        rosterAdapter.setData(emptyList())

        activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding.layoutNoLesson.visibility = View.VISIBLE
        binding.layoutActiveLesson.visibility = View.GONE
        binding.tvSessionTimer.visibility = View.GONE

        qrUpdateJob?.cancel()
        qrUpdateJob = null

        binding.ivQrCode.setImageBitmap(null)
        binding.statusIcon.setImageResource(R.drawable.ic_nfc)
        binding.statusIcon.setColorFilter(null)

        updateNfcStatusUI()
    }

    @OptIn(InternalSerializationApi::class)
    override fun processNfcTag(nfcId: String) {
        val lessonId = currentLessonId
        if (lessonId == null) {
            Log.e("NFC_DEBUG", "Ошибка: нет активного занятия")
            return
        }

        val cleanTag = nfcId.trim()
        val parsedId = if (cleanTag.startsWith("STUDENT:")) {
            cleanTag.split(":").getOrNull(1)?.toIntOrNull()
        } else {
            cleanTag.toIntOrNull()
        }

        if (parsedId != null && currentRosterList.any { it.student_id == parsedId && it.status.lowercase() in listOf("present", "ontime") }) {
            Toast.makeText(context, "Студент уже отмечен!", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val result = studentRepository.markAttendanceInLesson(lessonId, cleanTag)

            requireActivity().runOnUiThread {
                when (result) {
                    is AttendanceResult.Success -> {
                        val student = result.student
                        Toast.makeText(context, "Отмечен: ${student.studentName}", Toast.LENGTH_SHORT).show()

                        binding.statusIcon.setColorFilter(Color.GREEN)
                        binding.statusIcon.postDelayed({ updateNfcStatusUI() }, 1000)

                        // Immediately refresh session roster
                        val updatedStudent = AttendanceRosterStudent(
                            student_id = student.id,
                            student_name = student.studentName,
                            group_name = student.studentGroup,
                            status = "present",
                            marked_by = "nfc",
                            marked_at = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                        )
                        val idx = currentRosterList.indexOfFirst { it.student_id == student.id }
                        if (idx != -1) {
                            currentRosterList[idx] = updatedStudent
                        } else {
                            currentRosterList.add(0, updatedStudent)
                        }
                        rosterAdapter.setData(currentRosterList)
                        updateRosterCounts(currentRosterList)
                    }
                    is AttendanceResult.Error -> {
                        Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    @OptIn(InternalSerializationApi::class)
    @SuppressLint("SetTextI18n", "InflateParams")
    private fun showCreateLessonSheet() {
        selectedGroups.clear()

        val bottomSheet = BottomSheetDialog(requireContext(), R.style.FullScreenBottomSheetDialog)
        val sheetView = layoutInflater.inflate(R.layout.lesson_dialog, null)
        bottomSheet.setContentView(sheetView)
        bottomSheet.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        bottomSheet.setOnShowListener { dialog ->
            val d = dialog as BottomSheetDialog
            val bottomSheetInternal = d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheetInternal?.let {
                val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(it)
                it.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
                behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }

        val acLessonType = sheetView.findViewById<AutoCompleteTextView>(R.id.acLessonType)
        val acSubject = sheetView.findViewById<AutoCompleteTextView>(R.id.acSubject)
        val acGroupSearch = sheetView.findViewById<AutoCompleteTextView>(R.id.acGroupSearch)
        val chipGroup = sheetView.findViewById<ChipGroup>(R.id.chipGroupGroups)
        val btnStart = sheetView.findViewById<Button>(R.id.btnStartLesson)

        val lessonTypes = listOf("Практика", "Лекция", "Лабораторная работа", "Факультатив")
        acLessonType?.setAdapter(ArrayAdapter(requireContext(), R.layout.dropdown_item, lessonTypes))
        acLessonType?.setText("Практика", false)
        acLessonType?.setOnClickListener { acLessonType.showDropDown() }
        acLessonType?.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) acLessonType.showDropDown()
        }

        val groupAdapter = ContainsArrayAdapter(requireContext(), R.layout.dropdown_item)
        acGroupSearch.setAdapter(groupAdapter)
        acGroupSearch.threshold = 0

        acSubject.setOnClickListener { acSubject.showDropDown() }
        acSubject.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) acSubject.showDropDown()
        }
        acGroupSearch.setOnClickListener { acGroupSearch.showDropDown() }
        acGroupSearch.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) acGroupSearch.showDropDown()
        }

        lifecycleScope.launch {
            val teacherSubjects = studentRepository.getTeacherSubjects()
            val localGroups = studentRepository.getAllUniqueGroups()

            if (teacherSubjects.isNotEmpty()) {
                val subjectNames = teacherSubjects.map { it.subject_name }.distinct()
                acSubject.setAdapter(ArrayAdapter(requireContext(), R.layout.dropdown_item, subjectNames))
                val allGroups = (teacherSubjects.flatMap { it.groups }.map { it.name } + localGroups).distinct().sorted()
                groupAdapter.updateData(allGroups)

                val firstSubject = teacherSubjects.first()
                acSubject.setText(firstSubject.subject_name, false)
                val initialGroups = firstSubject.groups.map { it.name }.distinct().ifEmpty { allGroups.take(1) }
                chipGroup.removeAllViews()
                selectedGroups.clear()
                initialGroups.forEach { group ->
                    if (selectedGroups.add(group)) {
                        val chip = Chip(requireContext()).apply {
                            text = group
                            isCloseIconVisible = true
                            setOnCloseIconClickListener {
                                chipGroup.removeView(this)
                                selectedGroups.remove(group)
                            }
                        }
                        chipGroup.addView(chip)
                    }
                }

                acSubject.setOnItemClickListener { parent, _, position, _ ->
                    val selectedSubjectName = parent.getItemAtPosition(position).toString()
                    val selectedSubject = teacherSubjects.find { it.subject_name == selectedSubjectName }
                    val groupsForSubject = selectedSubject?.groups?.map { it.name }?.distinct() ?: emptyList()

                    chipGroup.removeAllViews()
                    selectedGroups.clear()
                    groupsForSubject.forEach { group ->
                        if (selectedGroups.add(group)) {
                            val chip = Chip(requireContext()).apply {
                                text = group
                                isCloseIconVisible = true
                                setOnCloseIconClickListener {
                                    chipGroup.removeView(this)
                                    selectedGroups.remove(group)
                                }
                            }
                            chipGroup.addView(chip)
                        }
                    }

                    if (groupsForSubject.isNotEmpty()) {
                        groupAdapter.updateData(groupsForSubject)
                    } else {
                        groupAdapter.updateData(allGroups)
                    }
                }
            } else {
                acSubject.setAdapter(ArrayAdapter(requireContext(), R.layout.dropdown_item, LessonsConfig.SUBJECTS_POOL))
                if (localGroups.isNotEmpty()) {
                    groupAdapter.updateData(localGroups)
                }
            }
        }

        acGroupSearch.setOnItemClickListener { parent, _, position, _ ->
            val group = parent.getItemAtPosition(position).toString()
            if (selectedGroups.add(group)) {
                val chip = Chip(requireContext()).apply {
                    text = group
                    isCloseIconVisible = true
                    setOnCloseIconClickListener {
                        chipGroup.removeView(this)
                        selectedGroups.remove(group)
                    }
                }
                chipGroup.addView(chip)
            }
            acGroupSearch.setText("")
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(acGroupSearch.windowToken, 0)
            acGroupSearch.post { groupAdapter.filter.filter(null) }
        }

        btnStart.setOnClickListener {
            val subject = acSubject.text.toString().trim()
            val lessonType = acLessonType?.text?.toString()?.trim().takeUnless { it.isNullOrBlank() } ?: "Практика"
            if (subject.isEmpty() || selectedGroups.isEmpty()) {
                Toast.makeText(context, "Заполните предмет и выберите группу", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val executeCreate = { lat: Double, lon: Double ->
                val prefs = requireContext().getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
                val teacherId = prefs.getInt("current_student_id", 0)

                lifecycleScope.launch {
                    try {
                        val id = studentRepository.createLesson(subject, teacherId, selectedGroups.toList(), lat, lon, lessonType)
                        if (id != null) {
                            currentLessonId = id
                            currentSubject = subject
                            saveLessonState(id, subject, selectedGroups.toList())

                            val authPrefs = requireContext().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
                            authPrefs.edit().putFloat("last_lat", lat.toFloat()).putFloat("last_lon", lon.toFloat()).apply()

                            val students = studentRepository.getAllStudents().first()
                            students.forEach { student ->
                                studentRepository.updateAttendance(student.id, false)
                            }
                            studentRepository.syncAllStudents()
                            updateUiOnLessonStart(subject, bottomSheet)
                        } else {
                            Toast.makeText(context, "Ошибка создания занятия", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(context, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                try {
                    val fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())
                    fusedLocationClient.lastLocation
                        .addOnSuccessListener { location: Location? ->
                            executeCreate(location?.latitude ?: 0.0, location?.longitude ?: 0.0)
                        }
                        .addOnFailureListener {
                            executeCreate(0.0, 0.0)
                        }
                } catch (e: Exception) {
                    executeCreate(0.0, 0.0)
                }
            } else {
                executeCreate(0.0, 0.0)
            }
        }
        bottomSheet.show()
    }

    @SuppressLint("SetTextI18n")
    private fun updateUiOnLessonStart(subject: String, dialog: BottomSheetDialog?) {
        if (_binding == null) return
        isLessonActive = true

        // Keep screen awake while presenting QR and tracking attendance
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        startNfcReadingMode(infiniteMode = true)
        startAttendancePolling()

        binding.layoutNoLesson.visibility = View.GONE
        binding.layoutActiveLesson.visibility = View.VISIBLE

        binding.tvActiveSubjectTitle.text = subject
        binding.tvActiveGroups.text = if (selectedGroups.isNotEmpty()) "Группы: ${selectedGroups.joinToString(", ")}" else "Все группы"

        updateNfcStatusUI()

        dialog?.dismiss()
        if (dialog != null) Toast.makeText(context, "Занятие начато!", Toast.LENGTH_SHORT).show()

        loadAndDisplayQrCode(currentLessonId!!)
    }

    private fun loadAndDisplayQrCode(lessonId: Int) {
        qrUpdateJob?.cancel()
        qrUpdateJob = lifecycleScope.launch {
            val result = studentRepository.getAttendanceLink(lessonId)
            when (result) {
                is AttendanceLinkResult.Success -> {
                    val baseUrl = result.url
                    val prefs = requireContext().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)

                    while (isLessonActive) {
                        var totpSecret = prefs.getString("last_totp_secret", "") ?: ""
                        if (totpSecret.isBlank()) {
                            totpSecret = "JBSWY3DPEHPK3PXP"
                        }
                        val totpCode = com.example.kotlinroomdatabase.utils.TotpUtils.generateCurrentCode(totpSecret, 5, 6)
                        val currentUrl = if (baseUrl.contains("?")) "$baseUrl&totp_code=$totpCode" else "$baseUrl?totp_code=$totpCode"

                        val bitmap = withContext(Dispatchers.Default) {
                            GenQR.generateQrCode(currentUrl)
                        }
                        withContext(Dispatchers.Main) {
                            if (_binding != null && bitmap != null) {
                                binding.ivQrCode.setImageBitmap(bitmap)
                                binding.ivQrCode.visibility = View.VISIBLE
                            }
                        }

                        delay(5000)
                    }
                }
                is AttendanceLinkResult.Error -> {
                    Log.e("QR_DEBUG", "QR link error: ${result.message}")
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        stopNfcReadingMode()
    }

    private fun updateNfcStatusUI() {
        if (_binding == null) return
        val adapterNfc = NfcAdapter.getDefaultAdapter(requireContext())

        if (adapterNfc == null || !adapterNfc.isEnabled) {
            binding.tvLessonStatus.text = "NFC выкл."
            binding.tvLessonStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.badge_absent_text))
            binding.layoutNfcStatusPill.setBackgroundResource(R.drawable.bg_badge_absent)
            binding.statusIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.badge_absent_icon))
        } else {
            binding.tvLessonStatus.text = "NFC готов"
            binding.tvLessonStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.badge_ontime_text))
            binding.layoutNfcStatusPill.setBackgroundResource(R.drawable.bg_badge_ontime)
            binding.statusIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.badge_ontime_icon))
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED)
        requireActivity().registerReceiver(nfcStateReceiver, filter)
    }

    override fun onStop() {
        super.onStop()
        requireActivity().unregisterReceiver(nfcStateReceiver)
    }

    override fun onResume() {
        super.onResume()
        updateNfcStatusUI()
        if (!isLessonActive) {
            checkServerActiveSession()
        }
    }

    override fun showNfcNotSupportedMessage() {
        Toast.makeText(requireContext(), "NFC не поддерживается", Toast.LENGTH_SHORT).show()
    }

    override fun showNfcReadingStartedMessage() {
        Log.d("NFC_DEBUG", "NFC активирован")
    }

    override fun showNfcReadingStoppedMessage() {
        Log.d("NFC_DEBUG", "NFC выключен")
    }
}

class ContainsArrayAdapter(context: Context, resource: Int) :
    ArrayAdapter<String>(context, resource, ArrayList()) {

    private val allItems = ArrayList<String>()

    fun updateData(items: List<String>) {
        allItems.clear()
        allItems.addAll(items)
        clear()
        addAll(items)
        notifyDataSetChanged()
    }

    override fun getFilter(): Filter {
        return object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                val query = constraint?.toString()?.lowercase()?.trim() ?: ""
                val results = FilterResults()

                val filteredList = if (query.isEmpty()) {
                    allItems
                } else {
                    allItems.filter { it.lowercase().contains(query) }
                }

                results.values = filteredList
                results.count = filteredList.size
                return results
            }

            @Suppress("UNCHECKED_CAST")
            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                clear()
                if (results != null && results.count > 0) {
                    addAll(results.values as List<String>)
                }
                notifyDataSetChanged()
            }
        }
    }
}