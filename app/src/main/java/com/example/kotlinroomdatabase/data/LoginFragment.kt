package com.example.kotlinroomdatabase.data

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.model.Student
import com.example.kotlinroomdatabase.repository.StudentRepository
import com.example.kotlinroomdatabase.repository.StudentRepository.LoginResult
import com.example.kotlinroomdatabase.settings.RepositoryZMQ
import kotlinx.coroutines.launch
import kotlinx.serialization.InternalSerializationApi

class LoginFragment : Fragment() {

    private lateinit var studentRepository: StudentRepository
    private var isLoginMode = true

    private lateinit var etName: EditText
    private lateinit var etGroup: EditText
    private lateinit var etPassword: EditText
    private lateinit var etPasswordConfirm: EditText
    private lateinit var btnAction: Button
    private lateinit var tvToggleMode: TextView
    private lateinit var tvTitle: TextView

    override fun onAttach(context: Context) {
        super.onAttach(context)
        studentRepository = RepositoryZMQ.getStudentRepository(requireContext())
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_login, container, false)

        etName = view.findViewById(R.id.etName)
        etGroup = view.findViewById(R.id.etGroup)
        etPassword = view.findViewById(R.id.etPassword)
        etPasswordConfirm = view.findViewById(R.id.etPasswordConfirm)
        btnAction = view.findViewById(R.id.btnAction)
        tvToggleMode = view.findViewById(R.id.tvToggleMode)
        tvTitle = view.findViewById(R.id.tvTitle)

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvToggleMode.setOnClickListener {
            isLoginMode = !isLoginMode
            updateUI()
        }

        btnAction.setOnClickListener {
            handleAction()
        }
    }

    private fun updateUI() {
        if (isLoginMode) {
            tvTitle.text = "Вход в систему"
            btnAction.text = "Войти"
            tvToggleMode.text = "Нет аккаунта? Зарегистрироваться"
            etGroup.isVisible = false
            etPasswordConfirm.isVisible = false
        } else {
            tvTitle.text = "Регистрация"
            btnAction.text = "Создать аккаунт"
            tvToggleMode.text = "Уже есть аккаунт? Войти"
            etGroup.isVisible = true
            etPasswordConfirm.isVisible = true
        }
    }

    @OptIn(InternalSerializationApi::class)
    private fun handleAction() {
        val name = etName.text.toString()
        val pass = etPassword.text.toString()

        if (isLoginMode) {
            if (name.isBlank() || pass.isBlank()) return

            lifecycleScope.launch {
                when (val result = studentRepository.login(name, pass)) {
                    is LoginResult.Success -> proceedToApp(result.student)
                    is LoginResult.Error -> Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            val group = etGroup.text.toString()
            val confirm = etPasswordConfirm.text.toString()

            if (name.isBlank() || group.isBlank() || pass.isBlank()) {
                Toast.makeText(context, "Заполните все поля", Toast.LENGTH_SHORT).show()
                return
            }
            if (pass != confirm) {
                Toast.makeText(context, "Пароли не совпадают", Toast.LENGTH_SHORT).show()
                return
            }

            lifecycleScope.launch {
                when (val result = studentRepository.register(name, group, pass)) {
                    is LoginResult.Success -> proceedToApp(result.student)
                    is LoginResult.Error -> Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    @OptIn(InternalSerializationApi::class)
    private fun proceedToApp(student: Student) {
        enableHceForStudent(student)
        if (student.role == "admin") {
            findNavController().navigate(R.id.action_login_to_main)
        } else {
            Toast.makeText(context, "Режим пропуска активен", Toast.LENGTH_LONG).show()
        }
    }
    @OptIn(InternalSerializationApi::class)
    private fun enableHceForStudent(student: Student) {
        val prefs = requireContext().getSharedPreferences("student_prefs", Context.MODE_PRIVATE)

        val tagForHce = student.studentNFC // метка теперь от сервера
        android.util.Log.d("DEBUG_NFC", "Server sent NFC tag: $tagForHce")

        if (!tagForHce.isNullOrBlank()) {
            prefs.edit().putString("nfc_payload", tagForHce).apply()
            android.util.Log.d("DEBUG_NFC", "Saved to SharedPreferences successfully")
        } else {
            android.util.Log.e("DEBUG_NFC", "Server sent EMPTY tag!")
        }
    }
}