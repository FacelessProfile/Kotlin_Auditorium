package com.example.kotlinroomdatabase.fragments.list

import android.annotation.SuppressLint
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
import com.example.kotlinroomdatabase.settings.LessonsConfig
import com.example.kotlinroomdatabase.fragments.nfc.NFC_Tools
import com.example.kotlinroomdatabase.model.Student
import com.example.kotlinroomdatabase.repository.StudentRepository
import com.example.kotlinroomdatabase.settings.RepositoryZMQ
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import kotlinx.coroutines.launch
import kotlinx.serialization.InternalSerializationApi

class LessonFragment : NFC_Tools() {

    private lateinit var studentRepository: StudentRepository
    private lateinit var adapter: ListAdapter

    @OptIn(InternalSerializationApi::class)
    private val attendedStudents = mutableListOf<Student>()
    private val selectedGroups = mutableSetOf<String>()

    
    private var currentLessonId: Int? = null

    private lateinit var tvStatus: TextView
    private lateinit var statusIcon: ImageView
    private lateinit var recyclerView: RecyclerView
    private lateinit var fabCreateLesson: ExtendedFloatingActionButton
    private lateinit var btnFinishLesson: Button

    @OptIn(InternalSerializationApi::class)
    override fun onAttach(context: android.content.Context) {
        super.onAttach(context)
        studentRepository = RepositoryZMQ.getStudentRepository(requireContext())

        
        nfcAdapter = NfcAdapter.getDefaultAdapter(context)
        Log.d("NFC_DEBUG", "NFC Adapter initialized: ${nfcAdapter != null}")
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_lesson, container, false)
        tvStatus = view.findViewById(R.id.tvLessonStatus)
        statusIcon = view.findViewById(R.id.statusIcon)
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

        fabCreateLesson.setOnClickListener { showCreateLessonSheet() }
        btnFinishLesson.setOnClickListener { finishCurrentLesson() }

        return view
    }

    @OptIn(InternalSerializationApi::class)
    private fun finishCurrentLesson() {
        val lessonId = currentLessonId ?: return
        lifecycleScope.launch {
            val result = studentRepository.finishLesson(lessonId)
            when (result) {
                is StudentRepository.FinishLessonResult.Success -> {
                    Toast.makeText(context, "Занятие завершено!", Toast.LENGTH_LONG).show()
                    stopNfcReadingMode() 
                    resetUiAfterLesson()
                }
                is StudentRepository.FinishLessonResult.Error -> {
                    Toast.makeText(context, "Ошибка: ${result.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    @OptIn(InternalSerializationApi::class)
    @SuppressLint("SetTextI18n")
    private fun resetUiAfterLesson() {
        currentLessonId = null
        attendedStudents.clear()
        adapter.setData(emptyList())
        tvStatus.text = "Создайте занятие"
        tvStatus.setTextColor(Color.BLACK)
        btnFinishLesson.visibility = View.GONE
        fabCreateLesson.show()
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
                    is StudentRepository.AttendanceResult.Success -> {
                        val student = result.student
                        Log.d("NFC_DEBUG", "SUCCESS: ${student.studentName}!")
                        attendedStudents.add(0, student)
                        adapter.setData(attendedStudents)

                        
                        statusIcon.setColorFilter(Color.GREEN)
                        statusIcon.postDelayed({ statusIcon.setColorFilter(null) }, 1000)
                    }
                    is StudentRepository.AttendanceResult.Error -> {
                        Log.e("NFC_DEBUG", "ERR: ${result.message}")
                        Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    @SuppressLint("SetTextI18n", "InflateParams")
    private fun showCreateLessonSheet() {
        val bottomSheet = BottomSheetDialog(requireContext(), com.google.android.material.R.style.Theme_Design_Light_BottomSheetDialog)
        val sheetView = layoutInflater.inflate(R.layout.lesson_dialog, null)
        bottomSheet.setContentView(sheetView)

        val acSubject = sheetView.findViewById<AutoCompleteTextView>(R.id.acSubject)
        val acGroupSearch = sheetView.findViewById<AutoCompleteTextView>(R.id.acGroupSearch)
        val chipGroup = sheetView.findViewById<ChipGroup>(R.id.chipGroupGroups)
        val btnStart = sheetView.findViewById<Button>(R.id.btnStartLesson)

        acSubject.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, LessonsConfig.SUBJECTS_POOL))

        lifecycleScope.launch {
            val groups = studentRepository.getAllUniqueGroups()
            acGroupSearch.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, groups))
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
        }

        btnStart.setOnClickListener {
            val subject = acSubject.text.toString()
            if (subject.isEmpty() || selectedGroups.isEmpty()) {
                Toast.makeText(context, "Заполните все поля!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            lifecycleScope.launch {
                try {
                    Log.d("NFC_DEBUG", "Запрос на создание занятия: $subject")
                    val id = studentRepository.createLesson(subject, 1, selectedGroups.toList())
                    if (id != null) {
                        currentLessonId = id
                        Log.d("NFC_DEBUG", "Занятие создано успешно ID: $currentLessonId")
                        updateUiOnLessonStart(subject, bottomSheet)
                    } else {
                        Log.e("NFC_DEBUG", "Ошибка сервера")
                        Toast.makeText(context, "Ошибка сервера", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e("NFC_DEBUG", "Ошибка: ${e.message}")
                }
            }
        }
        bottomSheet.show()
    }

    @OptIn(InternalSerializationApi::class)
    @SuppressLint("SetTextI18n")
    private fun updateUiOnLessonStart(subject: String, dialog: BottomSheetDialog) {
        attendedStudents.clear()
        adapter.setData(attendedStudents)
        adapter.setLessonState(true)
        Log.d("NFC_DEBUG", "Включение NFC...")
        startNfcReadingMode(infiniteMode = true)

        
        tvStatus.text = "Идет занятие: $subject"
        tvStatus.setTextColor(Color.parseColor("#4CAF50"))
        btnFinishLesson.visibility = View.VISIBLE
        fabCreateLesson.hide()

        dialog.dismiss()
        Toast.makeText(context, "Занятие начато!", Toast.LENGTH_SHORT).show()
    }

    override fun onPause() {
        super.onPause()
        
        stopNfcReadingMode()
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