package com.example.kotlinroomdatabase.fragments.add

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.navigation.fragment.findNavController
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.databinding.FragmentAddBinding
import com.example.kotlinroomdatabase.model.Student
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class AddFragment : Fragment() {

    private var _binding: FragmentAddBinding? = null
    private val binding get() = _binding!!

    private val jsonFileName = "students.json"

    @OptIn(InternalSerializationApi::class)
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAddBinding.inflate(inflater, container, false)

        // When click add its saving student to json at local storage
        binding.addBtn.setOnClickListener {
            insertStudentToJson()
        }

        //METHOd to do it later for autofill update form
        val studentList = loadStudentList()
        /*if (studentList.isNotEmpty()) {
            val lastStudent = studentList.last()
            Log.d("AddFragment", "Последний студент: $lastStudent")
            binding.addFirstNameEt.setText(lastStudent.studentName)
            binding.addLastNameEt.setText(lastStudent.studentGroup)
            binding.attendanceCb.isChecked = lastStudent.attendance
        }*/

        return binding.root
    }

    // Student add to json method
    @OptIn(InternalSerializationApi::class)
    private fun insertStudentToJson() {
        //val number = binding.addJournalNumber.text.toString().trim()
        val name = binding.addFirstNameEt.text.toString().trim()
        val group = binding.addLastNameEt.text.toString().trim()
        val attendance = binding.attendanceCb.isChecked

        val studentList = loadStudentList()

        // userID generation
        val newId = if (studentList.isEmpty()) 1 else (studentList.maxOf { it.id } + 1)

        val student = Student(
            id = newId,
//            studentNumber = number,
            studentName = name,
            studentGroup = group,
            attendance = attendance
        )

        if (student.isValid()) {
            studentList.add(student)
            saveStudentList(studentList)

            Log.d("AddFragment", "Студент добавлен: $student")
            Toast.makeText(requireContext(), "Student saved!", Toast.LENGTH_LONG).show()
            findNavController().navigate(R.id.action_addFragment_to_listFragment)
        } else {
            Toast.makeText(requireContext(), "Некорректные данные!", Toast.LENGTH_LONG).show()
        }
    }

    // later will used to put data in recycle
    @OptIn(InternalSerializationApi::class)
    private fun loadStudentList(): MutableList<Student> {
        return try {
            val jsonString = requireContext()
                .openFileInput(jsonFileName)
                .bufferedReader()
                .use { it.readText() }

            Json.decodeFromString<MutableList<Student>>(jsonString)
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    @OptIn(InternalSerializationApi::class)
    private fun saveStudentList(studentList: List<Student>) {
        val jsonString = Json.encodeToString(studentList)
        requireContext().openFileOutput(jsonFileName, Context.MODE_PRIVATE).use {
            it.write(jsonString.toByteArray())
        }
        Log.d("AddFragment", "Список студентов сохранён: $jsonString")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
