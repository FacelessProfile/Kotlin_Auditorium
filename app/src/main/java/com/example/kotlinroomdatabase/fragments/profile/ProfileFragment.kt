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
import com.example.kotlinroomdatabase.databinding.FragmentProfileBinding
import com.example.kotlinroomdatabase.repository.AvatarResult
import com.example.kotlinroomdatabase.settings.RepositoryHTTPS
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

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
        val userRole = prefs.getString("user_role", "student")
        val localizedRole = when(userRole) {
            "teacher" -> "Преподаватель"
            "admin" -> "Администратор"
            else -> "Студент"
        }
        
        binding.profileName.text = prefs.getString("student_name", localizedRole)
        binding.profileRole.text = localizedRole
        binding.profileGroup.text = if (userRole == "teacher") "Учитель" else prefs.getString("student_group", "Группа не указана")
        binding.profileEmail.text = "${prefs.getString("student_name", "user")?.replace(" ", ".")?.lowercase()}@university.edu"
        // Дата регистрации должна браться из БД!!!!!!!!!!!!!!!!
        binding.profileRegDate.text = "01.09.2023"

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

        val appPrefs = requireContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val primaryColorHex = appPrefs.getString("button_color", "#C48E17")
        primaryColorHex?.let {
            val color = android.graphics.Color.parseColor(it)
            binding.editProfileButton.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
        }

        setupActivityCalendar()

        return binding.root
    }

    private fun saveAvatarLocally(uri: Uri) {
        val internalPath = copyUriToInternalStorage(uri) ?: return
        
        val prefs = requireContext().getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("avatar_path", internalPath).apply()
        
        binding.profileAvatar.setImageURI(Uri.fromFile(java.io.File(internalPath)))
        binding.profileAvatar.imageTintList = null
        
        // Notify MainActivity to update header
        (activity as? com.example.kotlinroomdatabase.MainActivity)?.updateNavHeader()

        // Server request
        lifecycleScope.launch {
            try {
                val repository = RepositoryHTTPS.getStudentRepository(requireContext())
                val result = repository.uploadAvatar(internalPath)
                if (result is AvatarResult.Success) {
                    // Toast.makeText(requireContext(), "Аватарка загружена", Toast.LENGTH_SHORT).show()
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
            binding.profileAvatar.setImageResource(com.example.kotlinroomdatabase.R.drawable.ic_person)
            binding.profileAvatar.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.GRAY)
            return
        }

        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                // Handle relative URLs
                val finalUrl = if (url.startsWith("http")) {
                    url
                } else {
                    "http://109.172.114.128:9000${if (url.startsWith("/")) "" else "/"}$url"
                }

                val client = okhttp3.OkHttpClient()
                val request = okhttp3.Request.Builder().url(finalUrl).build()
                val response = client.newCall(request).execute()
                
                if (response.isSuccessful) {
                    val bytes = response.body.bytes()
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        binding.profileAvatar.setImageBitmap(bitmap)
                        binding.profileAvatar.imageTintList = null
                    }
                }
            } catch (e: Exception) {
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    binding.profileAvatar.setImageResource(com.example.kotlinroomdatabase.R.drawable.ic_person)
                    binding.profileAvatar.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.GRAY)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupActivityCalendar() {
        val calendarGrid = binding.activityCalendar
        val context = requireContext()
        val prefs = context.getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
        val userRole = prefs.getString("user_role", "student")
        
        lifecycleScope.launch {
            try {
                val repository = RepositoryHTTPS.getStudentRepository(context)
                val activityMap = mutableMapOf<String, Int>()
                val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                
                if (userRole == "teacher") {
                    repository.getAllLessons().first().forEach { lesson ->
                        val dateKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(lesson.date))
                        activityMap[dateKey] = (activityMap[dateKey] ?: 0) + 1
                    }
                } else {
                    val history = repository.getStudentHistory(Calendar.getInstance().get(Calendar.YEAR))
                    history.items.forEach { item ->
                        // Assuming date is in dd.MM.yyyy or similar, try to parse it
                        try {
                            val parts = item.date.split(".")
                            if (parts.size == 3) {
                                val normalizedDate = "${parts[2]}-${parts[1]}-${parts[0]}"
                                activityMap[normalizedDate] = (activityMap[normalizedDate] ?: 0) + 1
                            }
                        } catch (e: Exception) {}
                    }
                }

                val activityData = mutableListOf<Int>()
                val cal = Calendar.getInstance()
                cal.add(Calendar.DAY_OF_YEAR, -139) // Last 140 days
                
                for (i in 0 until 140) {
                    val dateKey = dateFormat.format(cal.time)
                    val count = activityMap[dateKey] ?: 0
                    activityData.add(when {
                        count >= 4 -> 4
                        count == 3 -> 3
                        count == 2 -> 2
                        count == 1 -> 1
                        else -> 0
                    })
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                }

                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    renderHeatmap(activityData)
                }
            } catch (e: Exception) {
                Log.e("ProfileFragment", "Error loading heatmap data", e)
            }
        }
    }

    private fun renderHeatmap(activityData: List<Int>) {
        val calendarGrid = binding.activityCalendar ?: return
        val context = context ?: return
        calendarGrid.removeAllViews()

        for (i in activityData.indices) {
            val cell = View(context)
            val size = (12 * resources.displayMetrics.density).toInt()
            val margin = (2 * resources.displayMetrics.density).toInt()
            
            val params = android.widget.GridLayout.LayoutParams()
            params.width = size
            params.height = size
            params.setMargins(margin, margin, margin, margin)
            cell.layoutParams = params

            val color = when (activityData[i]) {
                1 -> android.graphics.Color.parseColor("#9BE9A8")
                2 -> android.graphics.Color.parseColor("#40C463")
                3 -> android.graphics.Color.parseColor("#30A14E")
                4 -> android.graphics.Color.parseColor("#216E39")
                else -> android.graphics.Color.parseColor("#EBEDF0")
            }
            cell.setBackgroundColor(color)
            calendarGrid.addView(cell)
        }
    }
    
}