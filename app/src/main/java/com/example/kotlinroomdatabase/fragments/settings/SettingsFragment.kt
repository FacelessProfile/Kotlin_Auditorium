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

        // 3. Notifications & Delivery Mode Settings
        val context = requireContext()
        val isNotifMasterEnabled = LessonReminderScheduler.isNotificationsEnabled(context)
        binding.switchNotificationsMaster.isChecked = isNotifMasterEnabled
        binding.layoutNotificationsConfig.visibility = if (isNotifMasterEnabled) View.VISIBLE else View.GONE

        // Delivery Mode
        val currentMode = LessonReminderScheduler.getNotificationMode(context)
        when (currentMode) {
            LessonReminderScheduler.MODE_FCM_ONLY -> binding.rbModeFcmOnly.isChecked = true
            LessonReminderScheduler.MODE_LOCAL_ONLY -> binding.rbModeLocalOnly.isChecked = true
            else -> binding.rbModeFcmAndLocal.isChecked = true
        }

        binding.rgNotificationMode.setOnCheckedChangeListener { _, checkedId ->
            val newMode = when (checkedId) {
                R.id.rbModeFcmOnly -> LessonReminderScheduler.MODE_FCM_ONLY
                R.id.rbModeLocalOnly -> LessonReminderScheduler.MODE_LOCAL_ONLY
                else -> LessonReminderScheduler.MODE_FCM_AND_LOCAL
            }
            LessonReminderScheduler.setNotificationMode(context, newMode)
            if (newMode == LessonReminderScheduler.MODE_FCM_ONLY) {
                com.example.kotlinroomdatabase.service.NotificationForegroundService.stopService(context)
                Toast.makeText(context, "Режим FCM: фоновая служба остановлена", Toast.LENGTH_SHORT).show()
            } else {
                com.example.kotlinroomdatabase.service.NotificationForegroundService.startService(context)
                Toast.makeText(context, "Режим обновлен", Toast.LENGTH_SHORT).show()
            }
        }

        // Master switch
        binding.switchNotificationsMaster.setOnCheckedChangeListener { _, isChecked ->
            LessonReminderScheduler.setNotificationsEnabled(context, isChecked)
            binding.layoutNotificationsConfig.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked) {
                val mode = LessonReminderScheduler.getNotificationMode(context)
                if (mode != LessonReminderScheduler.MODE_FCM_ONLY) {
                    com.example.kotlinroomdatabase.service.NotificationForegroundService.startService(context)
                }
                Toast.makeText(context, "Уведомления включены", Toast.LENGTH_SHORT).show()
            } else {
                com.example.kotlinroomdatabase.service.NotificationForegroundService.stopService(context)
                Toast.makeText(context, "Уведомления и вибрация отключены", Toast.LENGTH_SHORT).show()
            }
        }

        // Vibration
        binding.switchVibration.isChecked = LessonReminderScheduler.isVibrationEnabled(context)
        binding.switchVibration.setOnCheckedChangeListener { _, isChecked ->
            LessonReminderScheduler.setVibrationEnabled(context, isChecked)
            val msg = if (isChecked) "Вибрация включена" else "Вибрация отключена"
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }

        // Sound
        binding.switchSound.isChecked = LessonReminderScheduler.isSoundEnabled(context)
        binding.switchSound.setOnCheckedChangeListener { _, isChecked ->
            LessonReminderScheduler.setSoundEnabled(context, isChecked)
            val msg = if (isChecked) "Звук уведомлений включен" else "Звук уведомлений отключен"
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }

        // Lesson Reminders
        val isReminderEnabled = LessonReminderScheduler.isRemindersEnabled(context)
        binding.switchLessonReminders.isChecked = isReminderEnabled
        binding.layoutReminderDetails.visibility = if (isReminderEnabled) View.VISIBLE else View.GONE

        binding.switchLessonReminders.setOnCheckedChangeListener { _, isChecked ->
            LessonReminderScheduler.setRemindersEnabled(context, isChecked)
            binding.layoutReminderDetails.visibility = if (isChecked) View.VISIBLE else View.GONE
            val statusText = if (isChecked) "Напоминания о начале пар включены" else "Напоминания о парах отключены"
            Toast.makeText(context, statusText, Toast.LENGTH_SHORT).show()
        }

        val currentReminderMinutes = LessonReminderScheduler.getReminderMinutes(context)
        binding.sliderReminderMinutes.value = currentReminderMinutes.toFloat().coerceIn(1.0f, 15.0f)
        binding.tvReminderMinutesLabel.text = "Предупреждать за: $currentReminderMinutes мин"

        binding.sliderReminderMinutes.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val mins = value.toInt()
                binding.tvReminderMinutesLabel.text = "Предупреждать за: $mins мин"
                LessonReminderScheduler.setReminderMinutes(context, mins)
            }
        }

        binding.btnTestReminder.setOnClickListener {
            LessonReminderScheduler.testReminderNow(context)
            val vibStatus = if (LessonReminderScheduler.isVibrationEnabled(context)) "с вибрацией" else "без вибрации"
            Toast.makeText(context, "Тестовое уведомление ($vibStatus) сработает через 1.5 сек", Toast.LENGTH_SHORT).show()
        }

        // 4. App Version & In-App Update
        val currentVersionName = AppUpdateManager.getCurrentVersionName(requireContext())
        val currentVersionCode = AppUpdateManager.getCurrentVersionCode(requireContext())
        binding.tvAppVersion.text = "СибГУТИ • Электронный журнал v$currentVersionName (сборка $currentVersionCode)"

        binding.switchAutoCheckUpdates.isChecked = AppUpdateManager.isAutoCheckEnabled(requireContext())
        binding.switchAutoCheckUpdates.setOnCheckedChangeListener { _, isChecked ->
            AppUpdateManager.setAutoCheckEnabled(requireContext(), isChecked)
            val msg = if (isChecked) "Автопроверка обновлений при запуске включена" else "Автопроверка обновлений отключена"
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }

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