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

        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}