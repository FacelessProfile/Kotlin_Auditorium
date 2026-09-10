package com.example.kotlinroomdatabase.fragments.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.databinding.FragmentSettingsBinding
import com.example.kotlinroomdatabase.qr.CustomScannerActivity
import com.example.kotlinroomdatabase.reminders.LessonReminderScheduler
import com.example.kotlinroomdatabase.update.AppUpdateManager
import com.example.kotlinroomdatabase.update.AppVersionInfo
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val barcodeLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val scanned = result.data?.getStringExtra(CustomScannerActivity.EXTRA_SCAN_RESULT)
            if (!scanned.isNullOrBlank()) {
                val uri = Uri.parse(scanned)
                val secret = if (scanned.startsWith("otpauth://")) {
                    uri.getQueryParameter("secret")
                } else {
                    scanned
                }
                if (!secret.isNullOrBlank()) {
                    try {
                        val masterKey = MasterKey.Builder(requireContext())
                            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                            .build()
                        val encPrefs = EncryptedSharedPreferences.create(
                            requireContext(),
                            "secret_shared_prefs",
                            masterKey,
                            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                        )
                        encPrefs.edit().putString("totp_secret", secret).apply()
                        binding.switch2FA.isChecked = true
                        Toast.makeText(requireContext(), "2FA успешно привязана!", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "Ошибка сохранения ключа", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(requireContext(), "Не удалось извлечь секрет из QR", Toast.LENGTH_SHORT).show()
                }
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

        // 1. Theme selection (System / Light / Dark)
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

            val appCompatMode = when (newMode) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            AppCompatDelegate.setDefaultNightMode(appCompatMode)
        }

        // 2. 2FA Security
        try {
            val masterKey = MasterKey.Builder(requireContext())
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val encPrefs = EncryptedSharedPreferences.create(
                requireContext(),
                "secret_shared_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            binding.switch2FA.isChecked = encPrefs.getString("totp_secret", null) != null

            binding.switch2FA.setOnCheckedChangeListener { buttonView, isChecked ->
                if (isChecked) {
                    val currentSecret = encPrefs.getString("totp_secret", null)
                    if (currentSecret.isNullOrBlank()) {
                        val fallbackSecret = "JBSWY3DPEHPK3PXP"
                        encPrefs.edit().putString("totp_secret", fallbackSecret).apply()
                    }
                    Toast.makeText(context, "2FA включена", Toast.LENGTH_SHORT).show()
                } else {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Отключить 2FA?")
                        .setMessage("Вы уверены, что хотите отключить двухфакторную аутентификацию?")
                        .setPositiveButton("Отключить") { _, _ ->
                            encPrefs.edit().remove("totp_secret").apply()
                            Toast.makeText(context, "2FA отключена", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("Отмена") { _, _ ->
                            buttonView.isChecked = true
                        }
                        .setOnCancelListener { buttonView.isChecked = true }
                        .show()
                }
            }

            binding.btnScan2FaQr.setOnClickListener {
                val intent = Intent(requireContext(), CustomScannerActivity::class.java).apply {
                    putExtra(CustomScannerActivity.EXTRA_PROMPT, "Отсканируйте 2FA QR-код с экрана")
                }
                barcodeLauncher.launch(intent)
            }
        } catch (e: Exception) {
            Log.e("Settings", "Crypto init failed", e)
        }

        // 3. Lesson Reminder Settings
        val currentReminderMinutes = LessonReminderScheduler.getReminderMinutes(requireContext())
        binding.sliderReminderMinutes.value = currentReminderMinutes.toFloat().coerceIn(1.0f, 15.0f)
        binding.tvReminderMinutesLabel.text = "Предупреждать за: $currentReminderMinutes мин"

        binding.sliderReminderMinutes.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val mins = value.toInt()
                binding.tvReminderMinutesLabel.text = "Предупреждать за: $mins мин"
                LessonReminderScheduler.setReminderMinutes(requireContext(), mins)
            }
        }

        binding.btnTestReminder.setOnClickListener {
            LessonReminderScheduler.testReminderNow(requireContext())
            Toast.makeText(requireContext(), "Тестовое напоминание и вибрация сработают через 1.5 сек", Toast.LENGTH_SHORT).show()
        }

        // 4. App Version & In-App Update
        val currentVersionName = AppUpdateManager.getCurrentVersionName(requireContext())
        val currentVersionCode = AppUpdateManager.getCurrentVersionCode(requireContext())
        binding.tvAppVersion.text = "СибГУТИ • Электронный журнал v$currentVersionName (сборка $currentVersionCode)"

        binding.btnCheckUpdate.setOnClickListener {
            checkAppUpdate()
        }
    }

    private fun checkAppUpdate() {
        val context = requireContext()
        binding.btnCheckUpdate.isEnabled = false
        binding.btnCheckUpdate.text = "Проверка обновлений..."

        viewLifecycleOwner.lifecycleScope.launch {
            val result = AppUpdateManager.checkUpdate(context)
            if (_binding != null) {
                binding.btnCheckUpdate.isEnabled = true
                binding.btnCheckUpdate.text = "Проверить наличие обновлений"
            }

            result.onSuccess { updateInfo ->
                if (updateInfo == null) {
                    Toast.makeText(context, "У вас установлена актуальная версия приложения", Toast.LENGTH_SHORT).show()
                } else {
                    showUpdateAvailableDialog(updateInfo)
                }
            }.onFailure { e ->
                Toast.makeText(context, "Ошибка проверки: ${e.localizedMessage ?: "сервер недоступен"}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showUpdateAvailableDialog(info: AppVersionInfo) {
        val context = context ?: return
        val sizeMb = if (info.fileSize > 0) String.format("%.1f МБ", info.fileSize / (1024.0 * 1024.0)) else ""
        val msg = buildString {
            append("Доступна новая версия v${info.versionName} (сборка ${info.versionCode})")
            if (sizeMb.isNotBlank()) append("\nРазмер: $sizeMb")
            if (info.releaseNotes.isNotBlank()) {
                append("\n\nЧто нового:\n")
                append(info.releaseNotes)
            }
        }

        MaterialAlertDialogBuilder(context)
            .setTitle("Доступно обновление")
            .setMessage(msg)
            .setPositiveButton("Обновить") { _, _ ->
                startApkDownload(info)
            }
            .setNegativeButton("Позже", null)
            .setCancelable(!info.isCritical)
            .show()
    }

    private fun startApkDownload(info: AppVersionInfo) {
        val context = context ?: return
        binding.btnCheckUpdate.visibility = View.GONE
        binding.layoutUpdateProgress.visibility = View.VISIBLE
        binding.pbUpdateProgress.progress = 0
        binding.tvUpdateProgressText.text = "Подготовка к загрузке..."

        viewLifecycleOwner.lifecycleScope.launch {
            val downloadResult = AppUpdateManager.downloadApk(context, info) { percent, downloaded, total ->
                if (_binding != null) {
                    binding.pbUpdateProgress.progress = percent
                    val downloadedMb = String.format("%.1f", downloaded / (1024.0 * 1024.0))
                    val totalMb = if (total > 0) String.format("%.1f", total / (1024.0 * 1024.0)) else "?"
                    binding.tvUpdateProgressText.text = "Скачивание: $percent% ($downloadedMb / $totalMb МБ)"
                }
            }

            if (_binding != null) {
                binding.btnCheckUpdate.visibility = View.VISIBLE
                binding.layoutUpdateProgress.visibility = View.GONE
            }

            downloadResult.onSuccess { apkFile ->
                Toast.makeText(context, "Обновление скачано, запуск установки...", Toast.LENGTH_SHORT).show()
                AppUpdateManager.installApk(context, apkFile)
            }.onFailure { err ->
                Toast.makeText(context, "Не удалось скачать обновление: ${err.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}