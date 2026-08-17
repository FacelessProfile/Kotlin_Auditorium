package com.example.kotlinroomdatabase.fragments.grades

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.data.StudentDatabase
import com.example.kotlinroomdatabase.model.GradeItem
import com.example.kotlinroomdatabase.repository.IStudentRepository
import com.example.kotlinroomdatabase.repository.StudentRepositoryHTTPS
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

class EditGradeBottomSheet : BottomSheetDialogFragment() {

    private lateinit var repository: IStudentRepository
    private var studentId: Int = -1
    private var subjectId: Int = -1
    private var studentName: String = ""
    private var gradeItems: List<GradeItem> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_edit_grade_bottom_sheet, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        studentId = arguments?.getInt("student_id", -1) ?: -1
        subjectId = arguments?.getInt("subject_id", -1) ?: -1
        studentName = arguments?.getString("student_name", "") ?: ""

        val tvStudentNameSheet = view.findViewById<TextView>(R.id.tvStudentNameSheet)
        tvStudentNameSheet.text = studentName

        val etScore = view.findViewById<TextInputEditText>(R.id.etScore)
        val etComment = view.findViewById<TextInputEditText>(R.id.etComment)
        val tilScore = view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilScore)
        val tilComment = view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilComment)
        val layoutGradeSelection = view.findViewById<View>(R.id.layoutGradeSelection)
        val toggleModeGroup = view.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.toggleModeGroup)
        val btnSaveGrade = view.findViewById<Button>(R.id.btnSaveGrade)

        val preselectedScore = arguments?.getInt("preselected_score", -1) ?: -1
        if (preselectedScore != -1) {
            etScore.setText(preselectedScore.toString())
        }
        val preselectedComment = arguments?.getString("preselected_comment", "") ?: ""
        if (preselectedComment.isNotEmpty()) {
            etComment.setText(preselectedComment)
        }

        val db = StudentDatabase.getInstance(requireContext())
        repository = StudentRepositoryHTTPS(requireContext(), db.studentDao())

        loadGradeItems(view)

        toggleModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                if (checkedId == R.id.btnModeGrade) {
                    layoutGradeSelection.visibility = View.VISIBLE
                    tilScore.hint = "Балл"
                    tilComment.hint = "Комментарий (опционально)"
                    btnSaveGrade.text = "Сохранить"
                } else if (checkedId == R.id.btnModeReward) {
                    layoutGradeSelection.visibility = View.GONE
                    tilScore.hint = "Баллы поощрения (+) или наказания (-)"
                    tilComment.hint = "Причина (обязательно)"
                    btnSaveGrade.text = "Выдать поощрение/наказание"
                }
            }
        }

        btnSaveGrade.setOnClickListener {
            saveGrade(view)
        }
    }

    private fun loadGradeItems(view: View) {
        lifecycleScope.launch {
            try {
                gradeItems = repository.getTeacherGradeItems(subjectId)
                if (gradeItems.isNotEmpty()) {
                    val actvGradeItems = view.findViewById<com.google.android.material.textfield.MaterialAutoCompleteTextView>(R.id.actvGradeItems)
                    val titles = gradeItems.map { it.title }
                    com.example.kotlinroomdatabase.utils.GradeUtils.setupNoFilterAdapter(actvGradeItems, requireContext(), titles) { _, _ -> }
                    
                    val preselectedId = arguments?.getInt("preselected_item_id", -1) ?: -1
                    if (preselectedId != -1) {
                        val index = gradeItems.indexOfFirst { it.item_id == preselectedId }
                        if (index >= 0) {
                            actvGradeItems.setText(gradeItems[index].title, false)
                        }
                    }
                } else {
                    Toast.makeText(requireContext(), "Нет заданий для этого предмета", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Ошибка загрузки заданий", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveGrade(view: View) {
        val toggleModeGroup = view.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.toggleModeGroup)
        val etScore = view.findViewById<TextInputEditText>(R.id.etScore)
        val etComment = view.findViewById<TextInputEditText>(R.id.etComment)

        val scoreStr = etScore.text.toString()
        if (scoreStr.isEmpty()) {
            Toast.makeText(requireContext(), "Введите баллы", Toast.LENGTH_SHORT).show()
            return
        }

        val inputScore = scoreStr.toIntOrNull()
        if (inputScore == null) {
            Toast.makeText(requireContext(), "Неверный формат баллов", Toast.LENGTH_SHORT).show()
            return
        }

        val isRewardMode = toggleModeGroup.checkedButtonId == R.id.btnModeReward

        if (isRewardMode) {
            val reason = etComment.text.toString().trim()
            if (reason.isEmpty()) {
                Toast.makeText(requireContext(), "Введите причину поощрения/наказания", Toast.LENGTH_SHORT).show()
                return
            }

            lifecycleScope.launch {
                val success = repository.createRewardPunishment(studentId, subjectId, inputScore, reason)
                if (success) {
                    repository.syncOfflineGrades()
                    Toast.makeText(requireContext(), "Баллы зачислены", Toast.LENGTH_SHORT).show()
                    parentFragmentManager.setFragmentResult("request_refresh", Bundle())
                    dismiss()
                } else {
                    Toast.makeText(requireContext(), "Ошибка сохранения поощрения/наказания", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            val actvGradeItems = view.findViewById<com.google.android.material.textfield.MaterialAutoCompleteTextView>(R.id.actvGradeItems)
            val selectedItemText = actvGradeItems.text.toString()
            val selectedGradeItem = gradeItems.find { it.title == selectedItemText }
            
            if (selectedGradeItem == null) {
                Toast.makeText(requireContext(), "Выберите задание", Toast.LENGTH_SHORT).show()
                return
            }

            val maxScore = selectedGradeItem.max_score
            val score = inputScore.coerceIn(0, maxScore)
            if (score != inputScore) {
                etScore.setText(score.toString())
                Toast.makeText(requireContext(), "Оценка ограничена максимальным баллом $maxScore", Toast.LENGTH_SHORT).show()
            }

            val itemId = selectedGradeItem.item_id
            val comment = etComment.text.toString().takeIf { it.isNotBlank() }

            lifecycleScope.launch {
                val success = repository.setStudentGrade(studentId, itemId, score, comment)
                if (success) {
                    repository.syncOfflineGrades()
                    Toast.makeText(requireContext(), "Оценка сохранена", Toast.LENGTH_SHORT).show()
                    parentFragmentManager.setFragmentResult("request_refresh", Bundle())
                    dismiss()
                } else {
                    Toast.makeText(requireContext(), "Ошибка сохранения оценки", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
