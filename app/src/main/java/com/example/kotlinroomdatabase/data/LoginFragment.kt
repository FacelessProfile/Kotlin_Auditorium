package com.example.kotlinroomdatabase.data

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.navOptions
import com.example.kotlinroomdatabase.MainActivity
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.config.ServerConfig
import com.example.kotlinroomdatabase.crypto.BiometricAuthManager
import com.example.kotlinroomdatabase.databinding.FragmentLoginBinding
import com.example.kotlinroomdatabase.model.Student
import com.example.kotlinroomdatabase.repository.IStudentRepository
import com.example.kotlinroomdatabase.repository.LoginResult
import com.example.kotlinroomdatabase.repository.StudentRepositoryHTTPS
import com.example.kotlinroomdatabase.settings.RepositoryZMQ
import com.example.kotlinroomdatabase.util.ApiErrorMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.InternalSerializationApi

class LoginFragment : Fragment() {

    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!

    private lateinit var studentRepository: IStudentRepository
    private var isLoginMode = true
    private var hasAutoPromptedBiometric = false

    override fun onAttach(context: Context) {
        super.onAttach(context)
        val useHttp = true
        if (useHttp) {
            val db = StudentDatabase.getInstance(requireContext())
            studentRepository = StudentRepositoryHTTPS(requireContext(), db.studentDao())
        } else {
            studentRepository = RepositoryZMQ.getStudentRepository(requireContext())
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLoginBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        updateServerFooter()

        binding.tvToggleMode.setOnClickListener {
            isLoginMode = !isLoginMode
            hideError()
            updateUI()
        }

        binding.btnAction.setOnClickListener {
            handleAction()
        }

        binding.btnBiometricLogin.setOnClickListener {
            triggerBiometricLogin()
        }

        binding.logoContainer.setOnLongClickListener {
            ServerConfig.showServerSwitcherDialog(requireContext()) {
                updateServerFooter()
            }
            true
        }

        binding.tvServerSwitcher.setOnClickListener {
            ServerConfig.showServerSwitcherDialog(requireContext()) {
                updateServerFooter()
            }
        }

        val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                hideError()
                checkPasswordLayout()
            }
            override fun afterTextChanged(s: Editable?) {}
        }

        binding.etName.addTextChangedListener(textWatcher)
        binding.etPassword.addTextChangedListener(textWatcher)
        binding.etInviteCode.addTextChangedListener(textWatcher)
        binding.etPasswordConfirm.addTextChangedListener(textWatcher)

        updateUI()
    }

    private fun checkPasswordLayout() {
        if (_binding == null) return
        val passText = binding.etPassword.text?.toString() ?: ""
        val confirmText = if (!isLoginMode) binding.etPasswordConfirm.text?.toString() ?: "" else ""

        val hasCyrillic = passText.any { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' } ||
                confirmText.any { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' }

        if (hasCyrillic) {
            binding.layoutPasswordWarning.visibility = View.VISIBLE
            binding.tvPasswordWarning.text = "Внимание: в пароле обнаружены русские буквы (проверьте раскладку)"
        } else {
            binding.layoutPasswordWarning.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        hideError()
        updateServerFooter()
        setupBiometricLoginUI()

        val context = context
        if (!hasAutoPromptedBiometric && isLoginMode && context != null &&
            BiometricAuthManager.hasSavedCredentials(context) &&
            BiometricAuthManager.isBiometricOrPinAvailable(context)) {
            hasAutoPromptedBiometric = true
            view?.postDelayed({
                if (isResumed && isLoginMode && _binding != null) {
                    triggerBiometricLogin()
                }
            }, 300)
        }
    }

    private fun updateServerFooter() {
        if (_binding == null) return
        val currentUrl = ServerConfig.getBaseUrl(requireContext())
        val cleanHost = try {
            java.net.URI(currentUrl).host ?: currentUrl
        } catch (_: Exception) {
            currentUrl
        }
        binding.tvServerSwitcher.text = "Сервер: $cleanHost (нажмите для смены)"
    }

    private fun updateUI() {
        if (isLoginMode) {
            binding.tvTitle.text = "Вход в систему"
            binding.btnAction.text = "Войти"
            binding.tvToggleMode.text = "Нет аккаунта? Зарегистрироваться"
            binding.nameLayout.hint = "Логин или email"
            binding.inviteCodeLayout.isVisible = false
            binding.passwordConfirmLayout.isVisible = false
        } else {
            binding.tvTitle.text = "Регистрация по инвайт-коду"
            binding.btnAction.text = "Зарегистрироваться"
            binding.tvToggleMode.text = "Уже есть аккаунт? Войти"
            binding.nameLayout.hint = "Придумайте логин"
            binding.inviteCodeLayout.isVisible = true
            binding.passwordConfirmLayout.isVisible = true
        }
        setupBiometricLoginUI()
        checkPasswordLayout()
    }

    private fun setupBiometricLoginUI() {
        if (_binding == null) return
        val context = context ?: return
        if (isLoginMode && BiometricAuthManager.hasSavedCredentials(context) && BiometricAuthManager.isBiometricOrPinAvailable(context)) {
            binding.btnBiometricLogin.visibility = View.VISIBLE
            if (binding.etName.text.isNullOrBlank()) {
                BiometricAuthManager.getSavedLogin(context)?.let {
                    binding.etName.setText(it)
                }
            }
        } else {
            binding.btnBiometricLogin.visibility = View.GONE
        }
    }

    @OptIn(InternalSerializationApi::class)
    private fun triggerBiometricLogin() {
        if (_binding == null) return
        val context = context ?: return
        if (!BiometricAuthManager.hasSavedCredentials(context)) {
            showError("Нет сохранённых данных для быстрого входа. Войдите с паролем.")
            return
        }

        hideError()
        BiometricAuthManager.authenticate(
            fragment = this,
            title = "Вход в СибГУТИ",
            subtitle = "Используйте отпечаток пальца, Face ID или PIN-код",
            onSuccess = { savedLogin, savedPass ->
                binding.etName.setText(savedLogin)
                binding.etPassword.setText(savedPass)
                performLogin(savedLogin, savedPass)
            },
            onError = { errMsg ->
                showError(errMsg)
            }
        )
    }

    private fun showError(message: String) {
        binding.tvError.text = ApiErrorMapper.getErrorMessage(message)
        binding.layoutError.visibility = View.VISIBLE
    }

    private fun hideError() {
        binding.layoutError.visibility = View.GONE
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnAction.isEnabled = !loading
        binding.btnAction.alpha = if (loading) 0.7f else 1.0f
        binding.btnBiometricLogin.isEnabled = !loading
        binding.btnBiometricLogin.alpha = if (loading) 0.7f else 1.0f
        binding.etName.isEnabled = !loading
        binding.etPassword.isEnabled = !loading
        binding.etInviteCode.isEnabled = !loading
        binding.etPasswordConfirm.isEnabled = !loading
    }

    @OptIn(InternalSerializationApi::class)
    private fun performLogin(name: String, pass: String) {
        if (name.isBlank() || pass.isBlank()) {
            showError("Пожалуйста, заполните логин и пароль")
            return
        }

        setLoading(true)
        lifecycleScope.launch(Dispatchers.IO) {
            val result = studentRepository.login(name, pass)
            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                setLoading(false)
                when (result) {
                    is LoginResult.Success -> {
                        BiometricAuthManager.saveCredentials(requireContext(), name, pass)
                        proceedToApp(result.student)
                    }
                    is LoginResult.Error -> showError(result.message)
                }
            }
        }
    }

    @OptIn(InternalSerializationApi::class)
    private fun handleAction() {
        val name = binding.etName.text.toString().trim()
        val pass = binding.etPassword.text.toString().trim()

        hideError()

        if (isLoginMode) {
            performLogin(name, pass)
        } else {
            val inviteCode = binding.etInviteCode.text.toString().trim()
            val confirm = binding.etPasswordConfirm.text.toString().trim()

            if (inviteCode.isBlank()) {
                showError("Пожалуйста, введите инвайт-код")
                return
            }
            if (name.isBlank()) {
                showError("Пожалуйста, придумайте логин")
                return
            }
            if (pass.isBlank()) {
                showError("Пожалуйста, введите пароль")
                return
            }
            if (pass.length < 8) {
                showError("Пароль должен содержать минимум 8 символов")
                return
            }
            if (pass != confirm) {
                showError("Введенные пароли не совпадают")
                return
            }

            setLoading(true)
            lifecycleScope.launch(Dispatchers.IO) {
                val result = studentRepository.registerByInvite(inviteCode, name, pass)
                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    setLoading(false)
                    when (result) {
                        is LoginResult.Success -> proceedToApp(result.student)
                        is LoginResult.Error -> showError(result.message)
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
            val authPrefs = requireContext().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
            val token = authPrefs.getString("auth_token", "") ?: ""

            val tagForHce = if (student.role == "student") {
                if (token.isNotEmpty()) token else "STUDENT:${student.id}:${student.studentName}"
            } else {
                student.studentNFC
            }

            val oldUserId = prefs.getInt("current_student_id", -1)
            if (oldUserId != -1 && oldUserId != student.id) {
                val localFile = com.example.kotlinroomdatabase.util.AvatarManager.getCachedAvatarFile(requireContext())
                if (localFile.exists()) {
                    try { localFile.delete() } catch (_: Exception) {}
                }
                prefs.edit().remove("avatar_path").remove("synced_avatar_url").apply()
            }

            prefs.edit().apply {
                putInt("current_student_id", student.id)
                putString("user_role", student.role)
                putString("student_name", student.studentName)
                putString("student_group", student.studentGroup)
                putString("nfc_payload", tagForHce)
                apply()
            }

            enableHceForStudent(student)

            // Auto-sync avatar from server immediately
            com.example.kotlinroomdatabase.util.AvatarManager.syncAvatarFromServer(requireContext().applicationContext) {
                val mainActivity = activity as? MainActivity
                mainActivity?.updateNavHeader()
            }

            // Auto-sync data in background
            studentRepository.syncAllStudents()
            try {
                val todayStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                val schedRes = studentRepository.getScheduleForDay(todayStr)
                if (schedRes is com.example.kotlinroomdatabase.repository.GenericResult.Success) {
                    com.example.kotlinroomdatabase.reminders.LessonReminderScheduler.scheduleAlarmsForDay(
                        requireContext().applicationContext,
                        schedRes.data
                    )
                }
            } catch (e: Exception) {
                Log.e("LoginFragment", "Error scheduling reminders on login", e)
            }

            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                val mainActivity = activity as? MainActivity
                mainActivity?.updateUIForRole()
                mainActivity?.refreshNotificationBadge()

                // Navigate to userHomeFragment (Главная) for ALL roles
                findNavController().navigate(R.id.action_login_to_userHome, null, navOptions {
                    popUpTo(R.id.my_nav) { inclusive = true }
                })
            }
        }
    }

    @OptIn(InternalSerializationApi::class)
    private fun enableHceForStudent(student: Student) {
        val prefs = requireContext().getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
        val authPrefs = requireContext().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
        val token = authPrefs.getString("auth_token", "") ?: ""

        val tagForHce = if (student.role == "student") {
            if (token.isNotEmpty()) token else "STUDENT:${student.id}:${student.studentName}"
        } else {
            student.studentNFC
        }

        if (!tagForHce.isNullOrBlank()) {
            prefs.edit().putString("nfc_payload", tagForHce).apply()
            Log.d("DEBUG_NFC", "Saved HCE tag: $tagForHce")
        } else {
            Log.e("DEBUG_NFC", "Server sent EMPTY tag!")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
