
package com.example.kotlinroomdatabase.data

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.model.Student
import com.example.kotlinroomdatabase.repository.*
import com.example.kotlinroomdatabase.settings.RepositoryZMQ
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.InternalSerializationApi

class LoginFragment : Fragment() {
    private lateinit var studentRepository: IStudentRepository
    private var isLoginMode = true

    private lateinit var etName: EditText
    private lateinit var etGroup: EditText
    private lateinit var etPassword: EditText
    private lateinit var etPasswordConfirm: EditText
    private lateinit var btnAction: Button
    private lateinit var tvToggleMode: TextView
    private lateinit var tvTitle: TextView

    private lateinit var groupLayout: View
    private lateinit var passwordConfirmLayout: View

    override fun onAttach(context: Context) {
        super.onAttach(context)


        val useHttp = true // MARK FALSE IF YOU USE ZMQ

        if (useHttp) {
            val db = StudentDatabase.getInstance(requireContext())
            studentRepository = StudentRepositoryHTTPS(requireContext(), db.studentDao())
        } else {
            studentRepository = RepositoryZMQ.getStudentRepository(requireContext())
        }
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
        groupLayout = view.findViewById(R.id.groupLayout)
        passwordConfirmLayout = view.findViewById(R.id.passwordConfirmLayout)

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

        setupPasswordVisibility(etPassword)
    }

    override fun onResume() {
        super.onResume()
        isLoginMode = true
        updateUI()
    }

    private fun updateUI() {
        if (isLoginMode) {
            tvTitle.text = "Вход в систему"
            btnAction.text = "Войти"
            tvToggleMode.text = "Нет аккаунта? Зарегистрироваться"
            groupLayout.isVisible = false
            passwordConfirmLayout.isVisible = false
        } else {
            tvTitle.text = "Регистрация"
            btnAction.text = "Создать аккаунт"
            tvToggleMode.text = "Уже есть аккаунт? Войти"
            groupLayout.isVisible = true
            passwordConfirmLayout.isVisible = true
        }
    }

    @OptIn(InternalSerializationApi::class)
    private fun handleAction() {
        val name = etName.text.toString().trim()
        val pass = etPassword.text.toString().trim()

        if (isLoginMode) {
            if (name.isBlank() || pass.isBlank()) {
                Toast.makeText(context, "Введите логин и пароль", Toast.LENGTH_SHORT).show()
                return
            }

            lifecycleScope.launch(Dispatchers.IO) {
                val result = studentRepository.login(name, pass)
                withContext(Dispatchers.Main) {
                    when (result) {
                        is LoginResult.Success -> proceedToApp(result.student)
                        is LoginResult.Error -> Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else {
            val group = etGroup.text.toString().trim()
            val confirm = etPasswordConfirm.text.toString().trim()

            if (name.isBlank() || group.isBlank() || pass.isBlank()) {
                Toast.makeText(context, "Заполните все поля!", Toast.LENGTH_SHORT).show()
                return
            }

            val passwordPattern = "^(?=.*[0-9]).{6,}$".toRegex()
            if (!passwordPattern.matches(pass)) {
                etPassword.error = "Пароль от 6 символов и минимум одна цифра"
                return
            }
            if (pass != confirm) {
                etPasswordConfirm.error = "Пароли не совпадают"
                return
            }

            lifecycleScope.launch(Dispatchers.IO) {
                val result = studentRepository.register(name, group, pass)
                withContext(Dispatchers.Main) {
                    when (result) {
                        is LoginResult.Success -> proceedToApp(result.student)
                        is LoginResult.Error -> Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    @OptIn(InternalSerializationApi::class)
    private fun proceedToApp(student: Student) {
        val prefs = requireContext().getSharedPreferences("student_prefs", Context.MODE_PRIVATE)

        lifecycleScope.launch(Dispatchers.IO) {
            studentRepository.clearLocalRoomData()
            prefs.edit().apply {
                clear()
                putInt("current_student_id", student.id)
                putString("user_role", student.role)
                putString("student_name", student.studentName)
                putString("nfc_payload", student.studentNFC)
                apply()
            }

            enableHceForStudent(student)

            if (student.role == "admin") {
                studentRepository.syncAllStudents()
                withContext(Dispatchers.Main) {
                    findNavController().navigate(R.id.action_loginFragment_to_lessonFragment)
                }
            } else {
                withContext(Dispatchers.Main) {
                    findNavController().navigate(R.id.userHomeFragment)
                    Toast.makeText(context, "Режим пропуска активен!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupPasswordVisibility(editText: EditText) {
        editText.setOnTouchListener { v, event ->
            val DRAWABLE_RIGHT = 2
            val drawable = editText.compoundDrawables[DRAWABLE_RIGHT]
            if (drawable != null) {
                val eyeIconArea = editText.right - drawable.bounds.width() - editText.paddingEnd
                if (event.rawX >= eyeIconArea) {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                            return@setOnTouchListener true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                            editText.setSelection(editText.text.length)
                            v.performClick()
                            return@setOnTouchListener true
                        }
                    }
                }
            }
            false
        }
    }

    @OptIn(InternalSerializationApi::class)
    private fun enableHceForStudent(student: Student) {
        val prefs = requireContext().getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
        val tagForHce = student.studentNFC

        if (!tagForHce.isNullOrBlank()) {
            prefs.edit().putString("nfc_payload", tagForHce).apply()
            android.util.Log.d("DEBUG_NFC", "Saved tag: $tagForHce")
        } else {
            android.util.Log.e("DEBUG_NFC", "Server sent EMPTY tag!")
        }
    }
}