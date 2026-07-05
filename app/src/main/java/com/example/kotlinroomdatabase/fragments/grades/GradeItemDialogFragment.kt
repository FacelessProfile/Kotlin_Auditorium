package com.example.kotlinroomdatabase.fragments.grades

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.data.StudentDatabase
import com.example.kotlinroomdatabase.repository.IStudentRepository
import com.example.kotlinroomdatabase.repository.StudentRepositoryHTTPS
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch

class GradeItemDialogFragment : DialogFragment() {

    private lateinit var repository: IStudentRepository
    private var subjectId: Int = -1

    private val typeMap = mapOf(
        "Домашнее задание" to "assignment",
        "Экзамен/Зачёт" to "exam",
        "Тест" to "quiz",
        "Посещаемость" to "attendance",
        "Другое..." to "other"
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_grade_item_dialog, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        subjectId = arguments?.getInt("subject_id", -1) ?: -1

        val db = StudentDatabase.getInstance(requireContext())
        repository = StudentRepositoryHTTPS(requireContext(), db.studentDao())

        val actvItemType = view.findViewById<com.google.android.material.textfield.MaterialAutoCompleteTextView>(R.id.actvItemType)
        val layoutCustomType = view.findViewById<TextInputLayout>(R.id.layoutCustomType)
        val etCustomType = view.findViewById<TextInputEditText>(R.id.etCustomType)

        val displayTypes = typeMap.keys.toList()
        actvItemType.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, displayTypes))
        
        actvItemType.setOnItemClickListener { _, _, position, _ ->
            if (displayTypes[position] == "Другое...") {
                layoutCustomType.visibility = View.VISIBLE
            } else {
                layoutCustomType.visibility = View.GONE
            }
        }

        val itemId = arguments?.getInt("item_id", -1) ?: -1
        val prepTitle = arguments?.getString("title", "") ?: ""
        val prepMaxScore = arguments?.getInt("max_score", 0) ?: 0
        val prepItemType = arguments?.getString("item_type", "") ?: ""
        val prepDeadline = arguments?.getString("deadline", "") ?: ""

        val etItemTitle = view.findViewById<TextInputEditText>(R.id.etItemTitle)
        val etItemMaxScore = view.findViewById<TextInputEditText>(R.id.etItemMaxScore)
        val etItemDeadline = view.findViewById<TextInputEditText>(R.id.etItemDeadline)
        val btnCreate = view.findViewById<Button>(R.id.btnCreate)

        if (itemId > 0) {
            etItemTitle.setText(prepTitle)
            etItemMaxScore.setText(prepMaxScore.toString())
            etItemDeadline.setText(prepDeadline)
            
            val reverseMap = typeMap.entries.associate { (k, v) -> v to k }
            val displayType = reverseMap[prepItemType] ?: "Другое..."
            actvItemType.setText(displayType, false)
            if (displayType == "Другое...") {
                layoutCustomType.visibility = View.VISIBLE
                etCustomType.setText(prepItemType)
            }
            btnCreate.text = "Сохранить"
        }

        view.findViewById<Button>(R.id.btnCancel).setOnClickListener {
            dismiss()
        }

        view.findViewById<Button>(R.id.btnCreate).setOnClickListener {
            val title = view.findViewById<TextInputEditText>(R.id.etItemTitle).text.toString()
            val maxScoreStr = view.findViewById<TextInputEditText>(R.id.etItemMaxScore).text.toString()
            val deadlineText = view.findViewById<TextInputEditText>(R.id.etItemDeadline).text.toString()
            val selectedDisplayType = actvItemType.text.toString()
            
            var type = typeMap[selectedDisplayType] ?: "assignment"

            if (selectedDisplayType == "Другое...") {
                val customType = etCustomType.text.toString().trim()
                if (customType.isBlank()) {
                    Toast.makeText(requireContext(), "Укажите свой тип", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                type = customType
            }

            if (title.isBlank()) {
                Toast.makeText(requireContext(), "Введите название", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val maxScore = maxScoreStr.toIntOrNull()
            if (maxScore == null || maxScore <= 0) {
                Toast.makeText(requireContext(), "Неверный балл", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val finalDeadline = if (deadlineText.isNotBlank()) deadlineText else null

            lifecycleScope.launch {
                val success = if (itemId > 0) {
                    repository.updateGradeItem(itemId, title, maxScore, type, finalDeadline)
                } else {
                    repository.createGradeItem(subjectId, title, maxScore, type, finalDeadline)
                }
                
                if (success) {
                    Toast.makeText(requireContext(), if(itemId > 0) "Изменения сохранены" else "Задание создано", Toast.LENGTH_SHORT).show()
                    parentFragmentManager.setFragmentResult("request_refresh", Bundle())
                    dismiss()
                } else {
                    Toast.makeText(requireContext(), "Ошибка создания/изменения", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
