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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.InternalSerializationApi

class LessonFragment : NFC_Tools() {

    private lateinit var studentRepository: IStudentRepository
    private lateinit var adapter: ListAdapter

    @OptIn(InternalSerializationApi::class)
    private val attendedStudents = mutableListOf<Student>()
    private val selectedGroups = mutableSetOf<String>()

    private var currentLessonId: Int? = null
    private var isLessonActive = false
    private var currentSubject: String? = null

    private lateinit var tvStatus: TextView
    private lateinit var tvSubStatus: TextView
    private lateinit var statusIcon: ImageView
    private lateinit var ivQrCode: ImageView
    private lateinit var recyclerView: RecyclerView
    private lateinit var fabCreateLesson: ExtendedFloatingActionButton
    private lateinit var btnFinishLesson: Button

    private val LESSON_PREFS = "lesson_active_prefs"
    private val KEY_LESSON_ID = "lesson_id"
    private val KEY_SUBJECT = "subject"
    private val KEY_START_TIME = "start_time"
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

        return view
    }

    private fun saveLessonState(id: Int, subject: String) {
        val prefs = requireContext().getSharedPreferences(LESSON_PREFS, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putInt(KEY_LESSON_ID, id)
            putString(KEY_SUBJECT, subject)
            putLong(KEY_START_TIME, System.currentTimeMillis())
            apply()
        }
    }

    @OptIn(InternalSerializationApi::class)
    private fun loadLessonState() {
        val prefs = requireContext().getSharedPreferences(LESSON_PREFS, Context.MODE_PRIVATE)
        val id = prefs.getInt(KEY_LESSON_ID, -1)
        val subject = prefs.getString(KEY_SUBJECT, null)
        val startTime = prefs.getLong(KEY_START_TIME, 0)

        if (id != -1 && subject != null && (System.currentTimeMillis() - startTime) < LESSON_DURATION_MS) {
            currentLessonId = id
            currentSubject = subject
            lifecycleScope.launch {
                studentRepository.getAllStudents().collect { allStudents ->
                    if (isLessonActive) {
                        attendedStudents.clear()
                        attendedStudents.addAll(allStudents.filter { it.attendance })
                        adapter.setData(attendedStudents)
                    }
                }
            }

            updateUiOnLessonStart(subject, null)
        } else {
            resetUiAfterLesson()
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
                    stopNfcReadingMode()
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
        tvStatus.text = "Создайте занятие"
        tvStatus.setTextColor(Color.BLACK)
        btnFinishLesson.visibility = View.GONE
        fabCreateLesson.show()

        ivQrCode.visibility = View.GONE
        ivQrCode.setImageBitmap(null)
        statusIcon.visibility = View.VISIBLE
        statusIcon.setImageResource(R.drawable.ic_nfc)
        statusIcon.setColorFilter(null)

        adapter.setLessonState(false)
    }

    @OptIn(InternalSerializationApi::class)
    override fun processNfcTag(nfcId: String) {
        Log.d("NFC_DEBUG", "найдена метка: $nfcId")

        val lessonId = currentLessonId
        if (lessonId == null) {
            Log.e("NFC_DEBUG", "Ошибка сканирования")
            return
        }

        if (attendedStudents.any { it.studentNFC == nfcId }) {
            Log.w("NFC_DEBUG", "студент уже в списке")
            return
        }

        lifecycleScope.launch {
            Log.d("NFC_DEBUG", "Отправляю на сервер. LessonID: $lessonId, NFC: $nfcId")
            val result = studentRepository.markAttendanceInLesson(lessonId, nfcId)

            requireActivity().runOnUiThread {
                when (result) {
                    is AttendanceResult.Success -> {
                        val student = result.student
                        Log.d("NFC_DEBUG", "SUCCESS: ${student.studentName}!")
                        attendedStudents.add(0, student)
                        adapter.setData(attendedStudents)

                        if (statusIcon.visibility == View.VISIBLE) {
                            statusIcon.setColorFilter(Color.GREEN)
                            statusIcon.postDelayed({ statusIcon.setColorFilter(null) }, 1000)
                        }
                    }
                    is AttendanceResult.Error -> {
                        Log.e("NFC_DEBUG", "ERR: ${result.message}")
                        Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    @SuppressLint("SetTextI18n", "InflateParams")
    private fun showCreateLessonSheet() {
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

        val acSubject = sheetView.findViewById<AutoCompleteTextView>(R.id.acSubject)
        val acGroupSearch = sheetView.findViewById<AutoCompleteTextView>(R.id.acGroupSearch)
        val chipGroup = sheetView.findViewById<ChipGroup>(R.id.chipGroupGroups)
        val btnStart = sheetView.findViewById<Button>(R.id.btnStartLesson)

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
        acGroupSearch.setDropDownBackgroundResource(android.R.color.white)

        lifecycleScope.launch {
            val teacherSubjects = studentRepository.getTeacherSubjects()
            val localGroups = studentRepository.getAllUniqueGroups()
            
            if (teacherSubjects.isNotEmpty()) {
                val subjectNames = teacherSubjects.map { it.subject_name }.distinct()
                acSubject.setAdapter(ArrayAdapter(requireContext(), R.layout.dropdown_item, subjectNames))
                val allGroups = (teacherSubjects.flatMap { it.groups }.map { it.name } + localGroups).distinct().sorted()
                groupAdapter.updateData(allGroups)

                acSubject.setOnItemClickListener { parent, _, position, _ ->
                    val selectedSubjectName = parent.getItemAtPosition(position).toString()
                    val selectedSubject = teacherSubjects.find { it.subject_name == selectedSubjectName }
                    val groupsForSubject = selectedSubject?.groups?.map { it.name }?.distinct() ?: emptyList()
                    
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
            val subject = acSubject.text.toString()
            if (subject.isEmpty() || selectedGroups.isEmpty()) {
                Toast.makeText(context, "Заполните предмет и выберите группу", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 101)
                return@setOnClickListener
            }

            val fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())
            fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                val lat = location?.latitude ?: 0.0
                val lon = location?.longitude ?: 0.0

                val prefs = requireContext().getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
                val teacherId = prefs.getInt("current_student_id", 0)

                lifecycleScope.launch {
                    try {
                        val id = studentRepository.createLesson(subject, teacherId, selectedGroups.toList(), lat, lon)
                        if (id != null) {
                            currentLessonId = id
                            currentSubject = subject
                            saveLessonState(id, subject)
                            updateUiOnLessonStart(subject, bottomSheet)
                        } else {
                            Toast.makeText(context, "Ошибка создания", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Log.e("NFC_DEBUG", "Ошибка: ${e.message}")
                    }
                }
            }
        }
        bottomSheet.show()
    }

    @SuppressLint("SetTextI18n")
    private fun updateUiOnLessonStart(subject: String, dialog: BottomSheetDialog?) {
        isLessonActive = true
        adapter.setLessonState(true)

        startNfcReadingMode(infiniteMode = true)

        tvStatus.text = "Идет занятие: $subject"
        tvStatus.setTextColor(Color.parseColor("#4CAF50"))
        btnFinishLesson.visibility = View.VISIBLE
        fabCreateLesson.hide()

        dialog?.dismiss()
        if (dialog != null) Toast.makeText(context, "Занятие начато!", Toast.LENGTH_SHORT).show()

        loadAndDisplayQrCode(currentLessonId!!)
    }

    private fun loadAndDisplayQrCode(lessonId: Int) {
        lifecycleScope.launch {
            val result = studentRepository.getAttendanceLink(lessonId)
            when (result) {
                is AttendanceLinkResult.Success -> {
                    val bitmap = withContext(Dispatchers.Default) {
                        GenQR.generateQrCode(result.url)
                    }
                    withContext(Dispatchers.Main) {
                        if (bitmap != null) {
                            statusIcon.visibility = View.GONE
                            ivQrCode.setImageBitmap(bitmap)
                            ivQrCode.visibility = View.VISIBLE
                            tvSubStatus.text = "Или отсканируйте QR-код"
                        } else {
                            Toast.makeText(context, "Ошибка генерации QR", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                is AttendanceLinkResult.Error -> {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "NFC режим (${result.message})", Toast.LENGTH_SHORT).show()
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
            tvStatus.text = "Не готов!"
            tvStatus.setTextColor(Color.parseColor("#8B0000"))
            tvSubStatus.text = "Включите NFC"
        } else {
            tvStatus.text = "Готов!"
            tvStatus.setTextColor(Color.parseColor("#FFFFAA"))

            if (ivQrCode.visibility == View.VISIBLE) {
                tvSubStatus.text = "Или отсканируйте QR-код"
            } else if (!isLessonActive) {
                tvSubStatus.text = "Начните занятие"
            } else {
                tvSubStatus.text = "Приложите метку"
            }
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