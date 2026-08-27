package com.example.kotlinroomdatabase.fragments.profile

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.kotlinroomdatabase.MainActivity
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.config.ServerConfig
import com.example.kotlinroomdatabase.databinding.FragmentProfileBinding
import com.example.kotlinroomdatabase.repository.AvatarResult
import com.example.kotlinroomdatabase.settings.RepositoryHTTPS
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale

class ProfileFragment : Fragment() {
    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            saveAvatarLocally(it)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        
        val prefs = requireContext().getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
        val userRole = prefs.getString("user_role", "student") ?: "student"
        val studentId = prefs.getInt("current_student_id", 1)
        val localizedRole = when(userRole) {
            "teacher" -> "Преподаватель"
            "admin" -> "Администратор"
            else -> "Студент"
        }
        
        val studentName = prefs.getString("student_name", localizedRole) ?: localizedRole
        binding.profileName.text = studentName
        binding.profileRole.text = localizedRole
        
        val groupRaw = prefs.getString("student_group", null)?.takeIf { it.isNotBlank() && it != "Unknown" } ?: "DEMO-101"
        val groupName = if (userRole == "teacher") "Кафедра ПОВТ" else groupRaw
        binding.profileGroupBadge.text = groupName
        binding.profileGroup.text = if (userRole == "teacher") "Кафедра ПОВТ (СибГУТИ)" else "$groupName (СибГУТИ)"

        val realEmail = prefs.getString("user_email", null)?.takeIf { it.isNotBlank() }
            ?: requireContext().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE).getString("user_email", null)?.takeIf { it.isNotBlank() }
            ?: if (userRole == "teacher") "teacher@sibsutis.ru" else "student@sibsutis.ru"
        binding.profileEmail.text = realEmail

        val studentCardNum = if (userRole == "teacher") "№ ТР-${1000 + studentId}" else "№ 2023-${groupRaw.take(4)}-${String.format(Locale.US, "%03d", studentId)}"
        binding.profileRegDate.text = studentCardNum

        // Avatar loading
        val avatarPath = prefs.getString("avatar_path", null)
        val avatarUrl = requireContext().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE).getString("avatar_url", null)

        if (avatarPath != null) {
            val file = java.io.File(avatarPath)
            if (file.exists()) {
                binding.profileAvatar.setImageURI(Uri.fromFile(file))
                binding.profileAvatar.imageTintList = null
            } else {
                loadAvatarFromUrl(avatarUrl)
            }
        } else {
            loadAvatarFromUrl(avatarUrl)
        }

        binding.profileAvatar.setOnClickListener {
            pickImage.launch("image/*")
        }

        // Server text update
        updateServerHostText()

        // Quick navigation buttons
        binding.btnQuickSchedule.setOnClickListener {
            findNavController().navigate(R.id.scheduleFragment)
        }

        binding.btnQuickGrades.setOnClickListener {
            findNavController().navigate(R.id.gradesFragment)
        }

        binding.btnQuickHistory.setOnClickListener {
            findNavController().navigate(R.id.historyFragment)
        }

        binding.btnQuickServer.setOnClickListener {
            ServerConfig.showServerSwitcherDialog(requireContext()) {
                updateServerHostText()
            }
        }

        binding.btnLogoutProfile.setOnClickListener {
            showLogoutConfirmDialog()
        }

        // Load real attendance stats
        loadAttendanceStats(userRole)

        // Long click helpers for developers
        binding.profileRole.setOnLongClickListener {
            ServerConfig.showServerSwitcherDialog(requireContext()) { updateServerHostText() }
            true
        }

        return binding.root
    }

    private fun updateServerHostText() {
        val baseUrl = ServerConfig.getBaseUrl(requireContext())
        val host = baseUrl.removePrefix("https://").removePrefix("http://")
        binding.tvCurrentServerHost.text = host
    }

    private fun loadAttendanceStats(userRole: String) {
        val context = context ?: return
        lifecycleScope.launch {
            try {
                val repository = RepositoryHTTPS.getStudentRepository(context)
                if (userRole == "teacher" || userRole == "admin") {
                    val lessons = repository.getAllLessons().first()
                    val total = lessons.size
                    withContext(Dispatchers.Main) {
                        binding.tvProfileTotalCount.text = total.toString()
                        binding.tvProfileOnTimeCount.text = total.toString()
                        binding.tvProfileAttendanceRate.text = "100%"
                        binding.tvAttendanceStatusBadge.text = "Преподаватель"
                        binding.tvAttendanceStatusBadge.setTextColor(android.graphics.Color.parseColor("#3B82F6"))
                    }
                } else {
                    val history = repository.getStudentHistory(Calendar.getInstance().get(Calendar.YEAR))
                    val total = history.items.size
                    val onTime = history.items.count { it.status == "present" || it.status == "ontime" || (it.status == null && !it.is_late) }
                    val attended = history.items.count { it.status == "present" || it.status == "late" || it.status == "ontime" || it.status == null }
                    
                    val rate = if (total > 0) (attended.toDouble() / total.toDouble()) * 100.0 else 100.0

                    withContext(Dispatchers.Main) {
                        binding.tvProfileTotalCount.text = total.toString()
                        binding.tvProfileOnTimeCount.text = onTime.toString()
                        binding.tvProfileAttendanceRate.text = String.format(Locale.US, "%.1f%%", rate)
                        
                        if (rate >= 80.0) {
                            binding.tvAttendanceStatusBadge.text = "Зачетный допуск 👍"
                            binding.tvAttendanceStatusBadge.setTextColor(android.graphics.Color.parseColor("#10B981"))
                        } else if (rate >= 60.0) {
                            binding.tvAttendanceStatusBadge.text = "Нормальная явка ⚡"
                            binding.tvAttendanceStatusBadge.setTextColor(android.graphics.Color.parseColor("#F59E0B"))
                        } else {
                            binding.tvAttendanceStatusBadge.text = "Требуется отработка ⚠️"
                            binding.tvAttendanceStatusBadge.setTextColor(android.graphics.Color.parseColor("#EF4444"))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("ProfileFragment", "Error loading attendance stats", e)
            }
        }
    }

    private fun showLogoutConfirmDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Выход из аккаунта")
            .setMessage("Вы действительно хотите выйти из своего профиля?")
            .setPositiveButton("Выйти") { _, _ ->
                (activity as? MainActivity)?.performLogout("Вы вышли из учетной записи")
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun saveAvatarLocally(uri: Uri) {
        val internalPath = copyUriToInternalStorage(uri) ?: return
        
        val prefs = requireContext().getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("avatar_path", internalPath).apply()
        
        binding.profileAvatar.setImageURI(Uri.fromFile(java.io.File(internalPath)))
        binding.profileAvatar.imageTintList = null
        
        (activity as? MainActivity)?.updateNavHeader()

        lifecycleScope.launch {
            try {
                val repository = RepositoryHTTPS.getStudentRepository(requireContext())
                val result = repository.uploadAvatar(internalPath)
                if (result is AvatarResult.Success) {
                    Toast.makeText(requireContext(), "Аватар успешно обновлен", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("ProfileFragment", "Error uploading avatar", e)
            }
        }
    }

    private fun copyUriToInternalStorage(uri: Uri): String? {
        return try {
            val context = requireContext()
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val file = java.io.File(context.filesDir, "current_avatar.jpg")
            val outputStream = java.io.FileOutputStream(file)
            inputStream.use { input ->
                outputStream.use { output ->
                    input.copyTo(output)
                }
            }
            file.absolutePath
        } catch (e: Exception) {
            Log.e("ProfileFragment", "Failed to copy avatar", e)
            null
        }
    }

    private fun loadAvatarFromUrl(url: String?) {
        if (url.isNullOrBlank() || url == "null") {
            binding.profileAvatar.setImageResource(R.drawable.ic_person)
            binding.profileAvatar.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.GRAY)
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val finalUrl = ServerConfig.resolveMediaUrl(requireContext(), url)
                val repo = RepositoryHTTPS.getStudentRepository(requireContext())
                val client = repo.getUnsafeOkHttpClient()
                val request = okhttp3.Request.Builder().url(finalUrl).build()
                val response = client.newCall(request).execute()
                
                if (response.isSuccessful) {
                    val bytes = response.body.bytes()
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    withContext(Dispatchers.Main) {
                        binding.profileAvatar.setImageBitmap(bitmap)
                        binding.profileAvatar.imageTintList = null
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.profileAvatar.setImageResource(R.drawable.ic_person)
                    binding.profileAvatar.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.GRAY)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}