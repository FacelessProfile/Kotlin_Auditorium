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
import com.example.kotlinroomdatabase.settings.RepositoryZMQ
import kotlinx.coroutines.launch

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
        if (avatarPath != null) {
            val file = java.io.File(avatarPath)
            if (file.exists()) {
                binding.profileAvatar.setImageURI(Uri.fromFile(file))
                binding.profileAvatar.imageTintList = null
            } else {
                Log.e("ProfileFragment", "Avatar file does not exist: $avatarPath")
            }
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

        // Mock server request
        lifecycleScope.launch {
            try {
                val repository = RepositoryZMQ.getStudentRepository(requireContext())
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupActivityCalendar() {
        val calendarGrid = binding.activityCalendar
        val context = requireContext()
        
        // Mock data
        val activityData = List(140) { 
            val rand = (0..10).random()
            when {
                rand > 9 -> 4
                rand > 8 -> 3
                rand > 7 -> 2
                rand > 5 -> 1
                else -> 0
            }
        } 

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