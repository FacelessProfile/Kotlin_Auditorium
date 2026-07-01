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

    private val barcodeLauncher = registerForActivityResult(com.journeyapps.barcodescanner.ScanContract()) { result ->
        if (result.contents != null) {
            val scanned = result.contents
            val uri = android.net.Uri.parse(scanned)
            val secret = if (scanned.startsWith("otpauth://")) {
                uri.getQueryParameter("secret")
            } else {
                scanned
            }
            if (!secret.isNullOrBlank()) {
                try {
                    val masterKey = androidx.security.crypto.MasterKey.Builder(requireContext())
                        .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                        .build()
                    val encPrefs = androidx.security.crypto.EncryptedSharedPreferences.create(
                        requireContext(),
                        "secret_shared_prefs",
                        masterKey,
                        androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                    )
                    encPrefs.edit().putString("totp_secret", secret).apply()
                    binding.switch2FA.isChecked = true
                    Toast.makeText(requireContext(), "2FA успешно привязана!", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "Ошибка сохранения ключа", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(requireContext(), "Не удалось извлечь секрет из QR", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val prefs = requireContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        
        // Load theme mode
        val themeMode = prefs.getString("theme_mode", "system") ?: "system"
        when (themeMode) {
            "light" -> binding.rbThemeLight.isChecked = true
            "dark" -> binding.rbThemeDark.isChecked = true
            else -> binding.rbThemeSystem.isChecked = true
        }

        binding.rgThemeMode.setOnCheckedChangeListener { _, checkedId ->
            val newMode = when (checkedId) {
                R.id.rbThemeLight -> "light"
                R.id.rbThemeDark -> "dark"
                else -> "system"
            }
            prefs.edit().putString("theme_mode", newMode).apply()
            
            // Apply theme immediately
            val appCompatMode = when (newMode) {
                "light" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(appCompatMode)
        }

        try {
            val masterKey = androidx.security.crypto.MasterKey.Builder(requireContext())
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                .build()
            val encPrefs = androidx.security.crypto.EncryptedSharedPreferences.create(
                requireContext(),
                "secret_shared_prefs",
                masterKey,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            binding.switch2FA.isChecked = encPrefs.getString("totp_secret", null) != null

            binding.switch2FA.setOnCheckedChangeListener { buttonView, isChecked ->
                if (isChecked) {
                    val currentSecret = encPrefs.getString("totp_secret", null)
                    if (currentSecret.isNullOrBlank()) {
                        val dummySecret = "JBSWY3DPEHPK3PXP" // Fallback secret
                        encPrefs.edit().putString("totp_secret", dummySecret).apply()
                    }
                    Toast.makeText(context, "2FA включена", Toast.LENGTH_SHORT).show()
                } else {
                    val builder = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    builder.setTitle("Отключить 2FA?")
                    builder.setMessage("Вы уверены, что хотите отключить двухфакторную аутентификацию?")
                    builder.setPositiveButton("Отключить") { _, _ ->
                        encPrefs.edit().remove("totp_secret").apply()
                        Toast.makeText(context, "2FA отключена", Toast.LENGTH_SHORT).show()
                    }
                    builder.setNegativeButton("Отмена") { _, _ ->
                        buttonView.isChecked = true
                    }
                    builder.setOnCancelListener { buttonView.isChecked = true }
                    builder.show()
                }
            }

            binding.btnScan2FaQr.setOnClickListener {
                val options = com.journeyapps.barcodescanner.ScanOptions()
                options.setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
                options.setPrompt("Отсканируйте 2FA QR-код с экрана")
                options.setCameraId(0)
                options.setBeepEnabled(true)
                options.setBarcodeImageEnabled(true)
                barcodeLauncher.launch(options)
            }
        } catch (e: Exception) {
            Log.e("Settings", "Crypto init failed", e)
        }

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