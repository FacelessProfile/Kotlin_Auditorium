package com.example.kotlinroomdatabase.data

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.model.Student
import com.example.kotlinroomdatabase.nfc.HCEservice
import com.example.kotlinroomdatabase.repository.StudentRepository
import kotlinx.coroutines.launch
import kotlinx.serialization.InternalSerializationApi

class LoginFragment : Fragment() {

    private lateinit var studentRepository: StudentRepository
    private lateinit var etName: EditText
    private lateinit var etGroup: EditText
    private lateinit var btnLogin: Button

    override fun onAttach(context: Context) {
        super.onAttach(context)
        val database = StudentDatabase.getInstance(requireContext())
        studentRepository = StudentRepository(database.studentDao())
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_login, container, false)
        etName = view.findViewById(R.id.etName)
        etGroup = view.findViewById(R.id.etGroup)
        btnLogin = view.findViewById(R.id.btnLogin)

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupLogin()
    }

    @OptIn(InternalSerializationApi::class)
    private fun setupLogin() {
        btnLogin.setOnClickListener {
            val name = etName.text.toString()
            val group = etGroup.text.toString()

            if (name.isBlank() || group.isBlank()) {
                Toast.makeText(requireContext(), "Заполните все поля", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            lifecycleScope.launch {
                val student = studentRepository.getStudentByNameAndGroup(name, group)
                if (student != null) {
                    HCEservice.currentStudent = student
                    enableHceForStudent(student)
                    findNavController().navigate(R.id.action_login_to_main)
                } else {
                    Toast.makeText(requireContext(), "Студент не найден", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    @OptIn(InternalSerializationApi::class)
    private fun enableHceForStudent(student: Student) {
        val prefs = requireContext().getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
        prefs.edit().putInt("current_student_id", student.id).apply()

        Toast.makeText(requireContext(),
            "HCE активирован для: ${student.studentName}",
            Toast.LENGTH_SHORT).show()
    }

}