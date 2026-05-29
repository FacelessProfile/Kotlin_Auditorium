package com.example.kotlinroomdatabase.fragments.settings

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val prefs = requireContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE)

        val grid = binding.colorPaletteGrid
        for (i in 0 until grid.childCount) {
            val child = grid.getChildAt(i)
            if (child is View && child !is android.widget.ImageButton) {
                child.setOnClickListener {
                    val colorHex = String.format("#%06X", (0xFFFFFF and child.backgroundTintList?.defaultColor!!))
                    prefs.edit().putString("primary_color", colorHex).apply()
                    applyPreviewColor(colorHex)
                    Toast.makeText(context, "Цвет темы изменен: $colorHex", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val currentColor = prefs.getString("primary_color", "#C48E17") ?: "#C48E17"
        applyPreviewColor(currentColor)

        binding.btnApplyTheme.setOnClickListener {
            requireActivity().recreate()
        }

        binding.btnCustomColor.setOnClickListener {
            showColorPickerDialog(prefs)
        }
    }

    private fun showColorPickerDialog(prefs: android.content.SharedPreferences) {
        val input = android.widget.EditText(requireContext())
        input.hint = "#RRGGBB"
        input.setText(prefs.getString("primary_color", "#C48E17"))

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Введите HEX цвет")
            .setView(input)
            .setPositiveButton("Применить") { _, _ ->
                val colorHex = input.text.toString()
                try {
                    Color.parseColor(colorHex)
                    prefs.edit().putString("primary_color", colorHex).apply()
                    applyPreviewColor(colorHex)
                    Toast.makeText(context, "Цвет изменен", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Некорректный формат", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun applyPreviewColor(colorHex: String) {
        val color = Color.parseColor(colorHex)
        binding.btnApplyTheme.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}