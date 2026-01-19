package com.example.kotlinroomdatabase.fragments.list

import android.annotation.SuppressLint
import android.graphics.Color
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

    @OptIn(InternalSerializationApi::class)
    override fun onAttach(context: android.content.Context) {
        super.onAttach(context)
        studentRepository = RepositoryZMQ.getStudentRepository(requireContext())
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_lesson, container, false)
        tvStatus = view.findViewById(R.id.tvLessonStatus)
        statusIcon = view.findViewById(R.id.statusIcon)
        recyclerView = view.findViewById(R.id.recyclerViewAttendance)
        fabCreateLesson = view.findViewById(R.id.fabCreateLesson)
        val btnAllStudents = view.findViewById<Button>(R.id.btnViewAllStudents)
        btnAllStudents.setOnClickListener {
            findNavController().navigate(R.id.action_lessonFragment_to_listFragment)
        }
        adapter = ListAdapter()
        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        fabCreateLesson.setOnClickListener { showCreateLessonSheet() }
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        startNfcReadingMode(true)
    }

    @OptIn(InternalSerializationApi::class)
    override fun processNfcTag(nfcId: String) {
        val lessonId = currentLessonId ?: return
        lifecycleScope.launch {
            val result = studentRepository.markAttendanceInLesson(lessonId, nfcId)
            requireActivity().runOnUiThread {
                when (result) {
                    is StudentRepository.AttendanceResult.Success -> {
                        if (attendedStudents.none { it.studentNFC == result.student.studentNFC }) {
                            attendedStudents.add(0, result.student)
                            adapter.setData(attendedStudents.toList())
                            statusIcon.setImageResource(R.drawable.ic_check)
                            statusIcon.setColorFilter(Color.GREEN)
                            view?.postDelayed({
                                if (isAdded) {
                                    statusIcon.setImageResource(R.drawable.ic_nfc)
                                    statusIcon.setColorFilter(null)
                                }
                            }, 2000)
                        }
                    }
                    is StudentRepository.AttendanceResult.Error -> {
                        Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    @SuppressLint("SetTextI18n", "InflateParams")
    @OptIn(InternalSerializationApi::class)
    private fun showCreateLessonSheet() {
        val bottomSheet = BottomSheetDialog(requireContext(), com.google.android.material.R.style.Theme_Design_Light_BottomSheetDialog)
        val sheetView = layoutInflater.inflate(R.layout.lesson_dialog, null)
        bottomSheet.setContentView(sheetView)
        val behavior = bottomSheet.behavior
        behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        behavior.skipCollapsed = true
        bottomSheet.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val acSubject = sheetView.findViewById<AutoCompleteTextView>(R.id.acSubject)
        val acGroupSearch = sheetView.findViewById<AutoCompleteTextView>(R.id.acGroupSearch)
        val chipGroup = sheetView.findViewById<ChipGroup>(R.id.chipGroupGroups)
        val btnStart = sheetView.findViewById<Button>(R.id.btnStartLesson)

        acSubject.setTextColor(Color.BLACK)
        acGroupSearch.setTextColor(Color.BLACK)
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
                    setTextColor(Color.WHITE)
                    setChipBackgroundColorResource(R.color.purple_500)
                    setCloseIconTintResource(android.R.color.white)
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
                Toast.makeText(context, "Заполните предмет и выберите группы!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            lifecycleScope.launch {
                try {
                    val id = studentRepository.createLesson(subject, 1, selectedGroups.toList())

                    // Если id пришел
                    if (id != null) {
                        currentLessonId = id
                        updateUiOnLessonStart(subject, bottomSheet)
                    } else {
                        // ВРЕМЕННАЯ ЛОГИКА TUDU FIX PLS!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!1
                        currentLessonId = -1
                         updateUiOnLessonStart(subject, bottomSheet)

                        Log.e("LessonFragment", "Ошибка: репозиторий вернул null")
                        Toast.makeText(context, "БД не ответила. Проверьте соединение.", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e("LessonFragment", "Критическая ошибка: ${e.message}")
                    Toast.makeText(context, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
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

        tvStatus.text = "Идет занятие: $subject"
        tvStatus.setTextColor(Color.parseColor("#4CAF50")) // Зеленый

        statusIcon.setImageResource(R.drawable.ic_nfc)
        statusIcon.setColorFilter(null)

        fabCreateLesson.hide()
        dialog.dismiss()

        Toast.makeText(context, "Занятие начато!", Toast.LENGTH_SHORT).show()
    }

    override fun showNfcNotSupportedMessage() {
        Toast.makeText(requireContext(), "NFC не поддерживается", Toast.LENGTH_SHORT).show()
    }

    override fun showNfcReadingStartedMessage() {
        Toast.makeText(requireContext(), "NFC чтение включено", Toast.LENGTH_SHORT).show()
    }

    override fun showNfcReadingStoppedMessage() {
        Toast.makeText(requireContext(), "NFC чтение выключено", Toast.LENGTH_SHORT).show()
    }
}