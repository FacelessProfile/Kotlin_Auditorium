package com.example.kotlinroomdatabase.fragments.list

import android.app.AlertDialog
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.nfc.NfcAdapter
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.getColorFromAttr
import com.example.kotlinroomdatabase.data.StudentDatabase
import com.example.kotlinroomdatabase.settings.LessonsConfig
import com.example.kotlinroomdatabase.fragments.nfc.NFC_Tools
import com.example.kotlinroomdatabase.fragments.QR.GenQR
import com.example.kotlinroomdatabase.model.Student
import com.example.kotlinroomdatabase.repository.*
import com.example.kotlinroomdatabase.settings.RepositoryZMQ
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.gms.location.LocationServices
import android.location.Location
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.InternalSerializationApi

class LessonFragment : NFC_Tools() {

    private lateinit var studentRepository: IStudentRepository
    private lateinit var adapter: ListAdapter

    @OptIn(InternalSerializationApi::class)
    private val attendedStudents = mutableListOf<Student>()
    private var selectedGroups = mutableSetOf<String>()

    private var currentLessonId: Int? = null
    private var isLessonActive = false
    private var currentSubject: String? = null
    private var pollingJob: Job? = null
    private var qrUpdateJob: Job? = null

    private lateinit var tvStatus: TextView
    private lateinit var tvSubStatus: TextView
    private lateinit var statusIcon: ImageView
    private lateinit var ivQrCode: ImageView
    private lateinit var recyclerView: RecyclerView
    private lateinit var fabCreateLesson: ExtendedFloatingActionButton
    private lateinit var btnFinishLesson: Button

    private lateinit var layoutNoLesson: View
    private lateinit var layoutActiveLesson: View
    private lateinit var btnCreateLessonHero: Button
    private lateinit var cardAllStudentsShortcut: View
    private lateinit var tvActiveSubjectTitle: TextView
    private lateinit var tvActiveGroups: TextView
    private lateinit var tvLivePresentCount: TextView
    private lateinit var tvPresentCounterBadge: TextView
    private lateinit var layoutEmptyAttendance: View
    private lateinit var layoutNfcStatusPill: View

    private val LESSON_PREFS = "lesson_active_prefs"
    private val KEY_LESSON_ID = "lesson_id"
    private val KEY_SUBJECT = "subject"
    private val KEY_START_TIME = "start_time"
    private val KEY_SELECTED_GROUPS = "selected_groups"
    private val LESSON_DURATION_MS = 90 * 60 * 1000L // 1.5 hours

    @OptIn(InternalSerializationApi::class)
    override fun onAttach(context: android.content.Context) {
        super.onAttach(context)

        val useHttp = true

        if (useHttp) {
            val db = StudentDatabase.getInstance(requireContext())
            studentRepository = StudentRepositoryHTTPS(requireContext(), db.studentDao())
        } else {
            studentRepository = RepositoryZMQ.getStudentRepository(requireContext())
        }

        nfcAdapter = NfcAdapter.getDefaultAdapter(context)
        Log.d("NFC_DEBUG", "NFC Adapter initialized: ${nfcAdapter != null}")
    }

    @OptIn(InternalSerializationApi::class)
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_lesson, container, false)
        tvStatus = view.findViewById(R.id.tvLessonStatus)
        tvSubStatus = view.findViewById(R.id.tvLessonSubStatus)
        statusIcon = view.findViewById(R.id.statusIcon)
        ivQrCode = view.findViewById(R.id.ivQrCode)
        recyclerView = view.findViewById(R.id.recyclerViewAttendance)
        fabCreateLesson = view.findViewById(R.id.fabCreateLesson)
        btnFinishLesson = view.findViewById(R.id.btnFinishLesson)

        layoutNoLesson = view.findViewById(R.id.layoutNoLesson)
        layoutActiveLesson = view.findViewById(R.id.layoutActiveLesson)
        btnCreateLessonHero = view.findViewById(R.id.btnCreateLessonHero)
        cardAllStudentsShortcut = view.findViewById(R.id.cardAllStudentsShortcut)
        tvActiveSubjectTitle = view.findViewById(R.id.tvActiveSubjectTitle)
        tvActiveGroups = view.findViewById(R.id.tvActiveGroups)
        tvLivePresentCount = view.findViewById(R.id.tvLivePresentCount)
        tvPresentCounterBadge = view.findViewById(R.id.tvPresentCounterBadge)
        layoutEmptyAttendance = view.findViewById(R.id.layoutEmptyAttendance)
        layoutNfcStatusPill = view.findViewById(R.id.layoutNfcStatusPill)

        btnCreateLessonHero.setOnClickListener { showCreateLessonSheet() }
        cardAllStudentsShortcut.setOnClickListener {
            findNavController().navigate(R.id.action_lessonFragment_to_listFragment)
        }

        val btnNoLessonStudents = view.findViewById<Button?>(R.id.btnNoLessonAllStudents)
        btnNoLessonStudents?.setOnClickListener {
            findNavController().navigate(R.id.action_lessonFragment_to_listFragment)
        }

        val btnAllStudents = view.findViewById<Button>(R.id.btnViewAllStudents)
        btnAllStudents.setOnClickListener {
            findNavController().navigate(R.id.action_lessonFragment_to_listFragment)
        }

        adapter = ListAdapter()
        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        applyTheme(view)

        adapter.setOnItemClickListener { student ->
            val bundle = Bundle()
            bundle.putInt("studentId", student.id)
            findNavController().navigate(R.id.action_lessonFragment_to_history, bundle)
        }

        fabCreateLesson.setOnClickListener { showCreateLessonSheet() }
        btnFinishLesson.setOnClickListener { 
            AlertDialog.Builder(requireContext())
                .setTitle("Завершить занятие?")
                .setMessage("Вы действительно хотите завершить текущее занятие?")
                .setPositiveButton("Да") { dialog, which -> finishCurrentLesson() }
                .setNegativeButton("Отмена", null)
                .show()
        }

        loadLessonState()
        observeStudents()

        return view
    }

    private fun updateLivePresentCount(count: Int) {
        tvLivePresentCount.text = "👥 $count"
        tvPresentCounterBadge.text = count.toString()
        if (count == 0) {
            layoutEmptyAttendance.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            layoutEmptyAttendance.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        }
    }

    @OptIn(InternalSerializationApi::class)
    private fun observeStudents() {
        lifecycleScope.launch {
            studentRepository.getAllStudents().collect { allStudents ->
                Log.d("OBSERVE", "Got ${allStudents.size} students. isLessonActive=$isLessonActive")
                if (isLessonActive) {
                    val cleanSelectedGroups = selectedGroups.map { it.trim().uppercase() }.toSet()
                    val filtered = allStudents.filter { 
                        it.attendance && (cleanSelectedGroups.isEmpty() || cleanSelectedGroups.contains(it.studentGroup.trim().uppercase())) 
                    }
                    Log.d("OBSERVE", "Filtered to ${filtered.size} students for groups: $selectedGroups")
                    
                    attendedStudents.clear()
                    attendedStudents.addAll(filtered.sortedByDescending { it.id })
                    adapter.setData(attendedStudents)

                    updateLivePresentCount(attendedStudents.size)
                }
            }
        }
    }

    private fun startAttendancePolling() {
        pollingJob?.cancel()
        pollingJob = lifecycleScope.launch(Dispatchers.Main) { // Use Main to keep it simple or IO with careful observation
            while (isLessonActive) {
                try {
                    Log.d("POLLING", "Auto-syncing attendance for lesson $currentLessonId")
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

    @OptIn(InternalSerializationApi::class)
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

    private fun applyTheme(view: View) {
        val prefs = requireContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val btnColor = Color.parseColor(prefs.getString("button_color", "#673AB7"))
        
        val buttonStates = android.content.res.ColorStateList.valueOf(btnColor)
        fabCreateLesson.backgroundTintList = buttonStates
        statusIcon.imageTintList = buttonStates
        
        view.findViewById<Button>(R.id.btnViewAllStudents).setTextColor(btnColor)
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

    @OptIn(InternalSerializationApi::class)
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

    @OptIn(InternalSerializationApi::class)
    @SuppressLint("SetTextI18n")
    private fun resetUiAfterLesson() {
        isLessonActive = false
        currentLessonId = null
        attendedStudents.clear()
        adapter.setData(emptyList())

        layoutNoLesson.visibility = View.VISIBLE
        layoutActiveLesson.visibility = View.GONE
        btnFinishLesson.visibility = View.GONE
        fabCreateLesson.visibility = View.GONE

        qrUpdateJob?.cancel()
        qrUpdateJob = null

        ivQrCode.setImageBitmap(null)
        statusIcon.setImageResource(R.drawable.ic_nfc)
        statusIcon.setColorFilter(null)

        adapter.setLessonState(false)
        updateNfcStatusUI()
    }

    @OptIn(InternalSerializationApi::class)
    override fun processNfcTag(nfcId: String) {
        Log.d("NFC_DEBUG", "найдена метка: $nfcId")

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

        if (parsedId != null && attendedStudents.any { it.id == parsedId }) {
            Log.w("NFC_DEBUG", "студент с id=$parsedId уже в списке")
            Toast.makeText(context, "Студент уже отмечен!", Toast.LENGTH_SHORT).show()
            return
        }

        if (attendedStudents.any { it.studentNFC.isNotBlank() && it.studentNFC.equals(cleanTag, ignoreCase = true) }) {
            Log.w("NFC_DEBUG", "студент с nfc=$cleanTag уже в списке")
            Toast.makeText(context, "Студент уже отмечен!", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            Log.d("NFC_DEBUG", "Отправляю на сервер. LessonID: $lessonId, NFC: $cleanTag")
            val result = studentRepository.markAttendanceInLesson(lessonId, cleanTag)

            requireActivity().runOnUiThread {
                when (result) {
                    is AttendanceResult.Success -> {
                        val student = result.student
                        Log.d("NFC_DEBUG", "SUCCESS: ${student.studentName} (ID: ${student.id})!")
                        attendedStudents.removeAll { it.id == student.id }
                        attendedStudents.add(0, student)
                        adapter.setData(attendedStudents)
                        updateLivePresentCount(attendedStudents.size)

                        if (statusIcon.visibility == View.VISIBLE) {
                            statusIcon.setColorFilter(Color.GREEN)
                            statusIcon.postDelayed({ updateNfcStatusUI() }, 1000)
                        }
                        Toast.makeText(context, "Отмечен: ${student.studentName}", Toast.LENGTH_SHORT).show()
                    }
                    is AttendanceResult.Error -> {
                        Log.e("NFC_DEBUG", "ERR: ${result.message}")
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
        bottomSheet.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        
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

                // Pre-populate with the first subject
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
                        Log.e("NFC_DEBUG", "Ошибка создания: ${e.message}")
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

    @OptIn(InternalSerializationApi::class)
    @SuppressLint("SetTextI18n")
    private fun updateUiOnLessonStart(subject: String, dialog: BottomSheetDialog?) {
        isLessonActive = true
        adapter.setLessonState(true)

        startNfcReadingMode(infiniteMode = true)
        startAttendancePolling()

        layoutNoLesson.visibility = View.GONE
        layoutActiveLesson.visibility = View.VISIBLE
        btnFinishLesson.visibility = View.VISIBLE
        fabCreateLesson.visibility = View.GONE

        tvActiveSubjectTitle.text = subject
        tvActiveGroups.text = if (selectedGroups.isNotEmpty()) "Группы: ${selectedGroups.joinToString(", ")}" else "Все группы"
        updateLivePresentCount(attendedStudents.size)
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
                            totpSecret = "JBSWY3DPEHPK3PXP" // Fallback secret for testing/demo
                        }
                        val totpCode = com.example.kotlinroomdatabase.utils.TotpUtils.generateCurrentCode(totpSecret, 5, 6)
                        val currentUrl = if (baseUrl.contains("?")) "$baseUrl&totp_code=$totpCode" else "$baseUrl?totp_code=$totpCode"

                        val bitmap = withContext(Dispatchers.Default) {
                            GenQR.generateQrCode(currentUrl)
                        }
                        withContext(Dispatchers.Main) {
                            if (bitmap != null) {
                                ivQrCode.setImageBitmap(bitmap)
                                ivQrCode.visibility = View.VISIBLE
                            }
                        }
                        
                        delay(5000)
                    }
                }
                is AttendanceLinkResult.Error -> {
                    withContext(Dispatchers.Main) {
                        Log.d("NFC_DEBUG", "QR link error: ${result.message}")
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        stopNfcReadingMode()
    }

    private fun updateNfcStatusUI() {
        val adapterNfc = NfcAdapter.getDefaultAdapter(requireContext())

        if (adapterNfc == null || !adapterNfc.isEnabled) {
            tvStatus.text = "NFC выкл."
            tvStatus.setTextColor(Color.parseColor("#DC2626"))
            layoutNfcStatusPill.setBackgroundResource(R.drawable.bg_badge_absent)
            statusIcon.setColorFilter(Color.parseColor("#DC2626"))
        } else {
            tvStatus.text = "NFC готов"
            tvStatus.setTextColor(Color.parseColor("#047857"))
            layoutNfcStatusPill.setBackgroundResource(R.drawable.bg_badge_ontime)
            statusIcon.setColorFilter(Color.parseColor("#047857"))
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