package com.example.kotlinroomdatabase.fragments.profile

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.kotlinroomdatabase.databinding.FragmentProfileBinding

class ProfileFragment : Fragment() {
    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        
        val prefs = requireContext().getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
        binding.profileName.text = prefs.getString("student_name", "Имя Студента")
        binding.profileRole.text = prefs.getString("user_role", "Студент")
        binding.profileGroup.text = prefs.getString("student_group", "Группа не указана")
        binding.profileEmail.text = "${prefs.getString("student_name", "user")?.replace(" ", ".")?.lowercase()}@university.edu"
        // Дата регистрации должна браться из БД!!!!!!!!!!!!!!!!
        binding.profileRegDate.text = "01.09.2023"
        val appPrefs = requireContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val primaryColorHex = appPrefs.getString("primary_color", "#C48E17")
        primaryColorHex?.let {
            val color = android.graphics.Color.parseColor(it)
            binding.editProfileButton.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
        }

        setupActivityCalendar()

        return binding.root
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}