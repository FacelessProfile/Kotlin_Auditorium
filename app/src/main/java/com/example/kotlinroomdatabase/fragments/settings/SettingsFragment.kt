package com.example.kotlinroomdatabase.fragments.settings

import android.content.Context
import android.graphics.Color
import android.util.Log
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private var navbarColor = "#C48E17"
    private var buttonColor = "#673AB7"
    private var inputColor = "#673AB7"
    private var toggleColor = "#4CAF50"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val prefs = requireContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        
        // Load saved colors
        navbarColor = prefs.getString("navbar_color", "#C48E17") ?: "#C48E17"
        buttonColor = prefs.getString("button_color", "#673AB7") ?: "#673AB7"
        inputColor = prefs.getString("input_color", "#673AB7") ?: "#673AB7"
        toggleColor = prefs.getString("toggle_color", "#4CAF50") ?: "#4CAF50"

        setupColorCategory(binding.navbarColorPresets) { color -> 
            navbarColor = color
            prefs.edit().putString("navbar_color", color).apply()
            updatePreview()
        }
        
        setupColorCategory(binding.buttonColorPresets) { color ->
            buttonColor = color
            prefs.edit().putString("button_color", color).apply()
            updatePreview()
        }

        setupColorCategory(binding.inputColorPresets) { color ->
            inputColor = color
            prefs.edit().putString("input_color", color).apply()
            updatePreview()
        }

        setupColorCategory(binding.toggleColorPresets) { color ->
            toggleColor = color
            prefs.edit().putString("toggle_color", color).apply()
            updatePreview()
        }

        binding.btnApplyTheme.setOnClickListener {
            Toast.makeText(context, "Тема применена", Toast.LENGTH_SHORT).show()
            requireActivity().recreate()
        }

        updatePreview()
    }

    private fun setupColorCategory(layout: LinearLayout, onColorSelected: (String) -> Unit) {
        for (i in 0 until layout.childCount) {
            val child = layout.getChildAt(i)
            child.setOnClickListener {
                val color = child.tag as? String ?: "#000000"
                onColorSelected(color)
            }
            child.setOnLongClickListener {
                showCustomColorDialog(onColorSelected)
                true
            }
        }
    }

    private fun showCustomColorDialog(onColorSelected: (String) -> Unit) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_color_picker, null)
        val etHex = dialogView.findViewById<android.widget.EditText>(R.id.etHex)
        val seekR = dialogView.findViewById<android.widget.SeekBar>(R.id.seekR)
        val seekG = dialogView.findViewById<android.widget.SeekBar>(R.id.seekG)
        val seekB = dialogView.findViewById<android.widget.SeekBar>(R.id.seekB)
        val colorPreview = dialogView.findViewById<View>(R.id.colorPreview)

        val updateFromRGB = {
            val r = seekR.progress
            val g = seekG.progress
            val b = seekB.progress
            val color = Color.rgb(r, g, b)
            val hex = String.format("#%02X%02X%02X", r, g, b)
            etHex.setText(hex)
            colorPreview.backgroundTintList = ColorStateList.valueOf(color)
        }

        val listener = object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) updateFromRGB()
            }
            override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
        }

        seekR.setOnSeekBarChangeListener(listener)
        seekG.setOnSeekBarChangeListener(listener)
        seekB.setOnSeekBarChangeListener(listener)

        etHex.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val hex = s.toString()
                if (hex.startsWith("#") && hex.length == 7) {
                    try {
                        val color = Color.parseColor(hex)
                        colorPreview.backgroundTintList = ColorStateList.valueOf(color)
                        seekR.progress = Color.red(color)
                        seekG.progress = Color.green(color)
                        seekB.progress = Color.blue(color)
                    } catch (e: Exception) {}
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        val builder = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
        builder.setTitle("Выбор цвета")
        builder.setView(dialogView)
        builder.setPositiveButton("Применить") { _, _ ->
            onColorSelected(etHex.text.toString())
        }
        builder.setNegativeButton("Отмена", null)
        builder.show()
    }

    private fun updatePreview() {
        try {
            val btnColorInt = Color.parseColor(buttonColor)
            binding.btnApplyTheme.backgroundTintList = ColorStateList.valueOf(btnColorInt)

            val inputColorInt = Color.parseColor(inputColor)
            binding.previewInputLayout.setBoxStrokeColor(inputColorInt)
            binding.previewInputLayout.defaultHintTextColor = ColorStateList.valueOf(inputColorInt)
            
            val toggleColorInt = Color.parseColor(toggleColor)
            binding.previewSwitch.thumbTintList = ColorStateList.valueOf(toggleColorInt)
            binding.previewSwitch.trackTintList = ColorStateList.valueOf(toggleColorInt).withAlpha(128)
        } catch (e: Exception) {
            Log.e("Settings", "Preview update error", e)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}