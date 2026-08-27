package com.example.kotlinroomdatabase.config

import android.content.Context
import android.content.SharedPreferences
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

object ServerConfig {

    // =========================================================================
    // SINGLE LINE CODE TOGGLE: Set to true for local debug (127.0.0.1:9001)
    // or false for external production server (https://lms.signal.qlabs.pro:9001)
    // =========================================================================
    const val USE_LOCAL_SERVER_BY_DEFAULT: Boolean = false

    const val REMOTE_SERVER_URL: String = "https://lms.signal.qlabs.pro:9001"
    const val LOCAL_SERVER_URL: String = "https://127.0.0.1:9001"
    const val LOCAL_WIFI_SERVER_URL: String = "https://192.168.0.55:9001"
    const val EMULATOR_SERVER_URL: String = "https://10.0.2.2:9001"

    private const val PREFS_NAME = "server_config_prefs"
    private const val KEY_CUSTOM_URL = "custom_server_url"
    private const val KEY_USE_LOCAL_OVERRIDE = "use_local_override"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun clearAttendanceState(context: Context) {
        try {
            context.applicationContext.getSharedPreferences("student_attendance_state_prefs", Context.MODE_PRIVATE)
                .edit().clear().apply()
        } catch (e: Exception) {}
    }

    fun isLocalServer(context: Context): Boolean {
        val prefs = getPrefs(context)
        return if (prefs.contains(KEY_USE_LOCAL_OVERRIDE)) {
            prefs.getBoolean(KEY_USE_LOCAL_OVERRIDE, USE_LOCAL_SERVER_BY_DEFAULT)
        } else {
            USE_LOCAL_SERVER_BY_DEFAULT
        }
    }

    fun setLocalServerEnabled(context: Context, enabled: Boolean) {
        clearAttendanceState(context)
        getPrefs(context).edit().putBoolean(KEY_USE_LOCAL_OVERRIDE, enabled).apply()
    }

    fun resetToDefault(context: Context) {
        clearAttendanceState(context)
        getPrefs(context).edit().remove(KEY_USE_LOCAL_OVERRIDE).remove(KEY_CUSTOM_URL).apply()
    }

    fun getBaseUrl(context: Context): String {
        val prefs = getPrefs(context)
        val customUrl = prefs.getString(KEY_CUSTOM_URL, null)
        if (!customUrl.isNullOrBlank()) {
            return customUrl.trim().removeSuffix("/")
        }
        return if (isLocalServer(context)) {
            LOCAL_SERVER_URL
        } else {
            REMOTE_SERVER_URL
        }
    }

    fun setCustomServerUrl(context: Context, url: String?) {
        clearAttendanceState(context)
        if (url.isNullOrBlank()) {
            getPrefs(context).edit().remove(KEY_CUSTOM_URL).apply()
        } else {
            getPrefs(context).edit().putString(KEY_CUSTOM_URL, url.trim().removeSuffix("/")).apply()
        }
    }

    fun isDebugSwitcherAllowedForUser(loginOrRole: String?): Boolean {
        return true // Always allow server switching on test/debug users and by long press
    }

    fun showServerSwitcherDialog(context: Context, onServerChanged: (() -> Unit)? = null) {
        val currentUrl = getBaseUrl(context)
        val options = arrayOf(
            "Внешний сервер (https://lms.signal.qlabs.pro:9001)",
            "Локальный Wi-Fi (https://192.168.0.55:9001)",
            "Локальный Loopback / ADB USB (https://127.0.0.1:9001)",
            "Эмулятор Android (https://10.0.2.2:9001)",
            "Ввести свой IP / URL",
            "Сбросить настройки сервера"
        )
        val selectedIdx = when {
            currentUrl.contains("192.168.0.55") -> 1
            currentUrl.contains("127.0.0.1") -> 2
            currentUrl.contains("10.0.2.2") -> 3
            !currentUrl.contains("lms.signal.qlabs.pro") -> 4
            else -> 0
        }

        AlertDialog.Builder(context)
            .setTitle("⚙️ Сервер бэкенда (Debug)")
            .setSingleChoiceItems(options, selectedIdx) { dialog, which ->
                when (which) {
                    0 -> {
                        setLocalServerEnabled(context, false)
                        setCustomServerUrl(context, null)
                        dialog.dismiss()
                        showServerChangedToast(context)
                        onServerChanged?.invoke()
                    }
                    1 -> {
                        setLocalServerEnabled(context, true)
                        setCustomServerUrl(context, LOCAL_WIFI_SERVER_URL)
                        dialog.dismiss()
                        showServerChangedToast(context)
                        onServerChanged?.invoke()
                    }
                    2 -> {
                        setLocalServerEnabled(context, true)
                        setCustomServerUrl(context, LOCAL_SERVER_URL)
                        dialog.dismiss()
                        showServerChangedToast(context)
                        onServerChanged?.invoke()
                    }
                    3 -> {
                        setLocalServerEnabled(context, true)
                        setCustomServerUrl(context, EMULATOR_SERVER_URL)
                        dialog.dismiss()
                        showServerChangedToast(context)
                        onServerChanged?.invoke()
                    }
                    4 -> {
                        dialog.dismiss()
                        showCustomUrlPrompt(context, onServerChanged)
                    }
                    5 -> {
                        resetToDefault(context)
                        dialog.dismiss()
                        showServerChangedToast(context)
                        onServerChanged?.invoke()
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showCustomUrlPrompt(context: Context, onServerChanged: (() -> Unit)? = null) {
        val currentUrl = getBaseUrl(context)
        val input = EditText(context).apply {
            setText(currentUrl)
            hint = "https://192.168.x.x:9001"
            setSelection(text.length)
        }
        val container = FrameLayout(context).apply {
            setPadding(48, 16, 48, 16)
            addView(input)
        }

        AlertDialog.Builder(context)
            .setTitle("Введите URL сервера")
            .setView(container)
            .setPositiveButton("Сохранить") { _, _ ->
                val newUrl = input.text.toString().trim()
                if (newUrl.isNotBlank()) {
                    setCustomServerUrl(context, newUrl)
                    showServerChangedToast(context)
                    onServerChanged?.invoke()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showServerChangedToast(context: Context) {
        val newUrl = getBaseUrl(context)
        Toast.makeText(context, "Сервер переключен на:\n$newUrl", Toast.LENGTH_LONG).show()
    }

    fun resolveMediaUrl(context: Context, url: String?): String {
        if (url.isNullOrBlank()) return ""
        val baseUrl = getBaseUrl(context)

        return when {
            url.startsWith("http://localhost:9001") -> url.replace("http://localhost:9001", baseUrl)
            url.startsWith("https://127.0.0.1:9001") -> url.replace("https://127.0.0.1:9001", baseUrl)
            url.startsWith("http://127.0.0.1:9001") -> url.replace("http://127.0.0.1:9001", baseUrl)
            url.startsWith("http://109.172.114.128:9000") -> url.replace("http://109.172.114.128:9000", baseUrl)
            url.startsWith("http://109.172.114.128:9001") -> url.replace("http://109.172.114.128:9001", baseUrl)
            url.startsWith("https://192.168.0.56:9001") -> url.replace("https://192.168.0.56:9001", baseUrl)
            url.startsWith("https://192.168.0.55:9001") -> url.replace("https://192.168.0.55:9001", baseUrl)
            url.startsWith("https://lms.signal.qlabs.pro:9001") && isLocalServer(context) ->
                url.replace("https://lms.signal.qlabs.pro:9001", baseUrl)
            url.startsWith("https://") || url.startsWith("http://") -> url
            else -> "$baseUrl${if (url.startsWith("/")) "" else "/"}$url"
        }
    }
}
