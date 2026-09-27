package com.example.kotlinroomdatabase.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.example.kotlinroomdatabase.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object ServerConfig {

    private const val TAG = "ServerConfig"

    // =========================================================================
    // PRODUCTION DEFAULT: Set to false for external production server (https://lms.signal.qlabs.pro:9001)
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
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing attendance state", e)
        }
    }

    fun getOrigin(url: String): String {
        val httpUrl = url.toHttpUrlOrNull() ?: return url
        return "${httpUrl.scheme}://${httpUrl.host}:${httpUrl.port}"
    }

    private fun handleServerOriginChangeIfDifferent(context: Context, oldBaseUrl: String, newBaseUrl: String) {
        val oldOrigin = getOrigin(oldBaseUrl)
        val newOrigin = getOrigin(newBaseUrl)
        if (!oldOrigin.equals(newOrigin, ignoreCase = true)) {
            clearAttendanceState(context)
            com.example.kotlinroomdatabase.util.JwtUtils.clearAllSessionData(context)
            com.example.kotlinroomdatabase.crypto.BiometricAuthManager.clearCredentials(context)
            Log.w(TAG, "Server origin changed from $oldOrigin to $newOrigin. Session tokens and credentials cleared.")
        }
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
        val oldBase = getBaseUrl(context)
        getPrefs(context).edit().putBoolean(KEY_USE_LOCAL_OVERRIDE, enabled).apply()
        handleServerOriginChangeIfDifferent(context, oldBase, getBaseUrl(context))
    }

    fun resetToDefault(context: Context) {
        val oldBase = getBaseUrl(context)
        getPrefs(context).edit().remove(KEY_USE_LOCAL_OVERRIDE).remove(KEY_CUSTOM_URL).apply()
        handleServerOriginChangeIfDifferent(context, oldBase, getBaseUrl(context))
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
        val oldBase = getBaseUrl(context)
        if (url.isNullOrBlank()) {
            getPrefs(context).edit().remove(KEY_CUSTOM_URL).apply()
        } else {
            val trimmed = url.trim().removeSuffix("/")
            val httpUrl = trimmed.toHttpUrlOrNull()
            if (httpUrl == null) {
                Toast.makeText(context, "Некорректный URL сервера", Toast.LENGTH_SHORT).show()
                return
            }
            // Enforce HTTPS in non-debug mode unless localhost/emulator
            if (!BuildConfig.DEBUG && !httpUrl.isHttps) {
                val host = httpUrl.host
                if (host != "localhost" && host != "127.0.0.1" && host != "10.0.2.2") {
                    Toast.makeText(context, "В релизной версии разрешён только HTTPS", Toast.LENGTH_SHORT).show()
                    return
                }
            }
            getPrefs(context).edit().putString(KEY_CUSTOM_URL, trimmed).apply()
        }
        handleServerOriginChangeIfDifferent(context, oldBase, getBaseUrl(context))
    }

    fun isDebugSwitcherAllowedForUser(loginOrRole: String?): Boolean {
        return true // Always allow server switching on test/debug users and by long press
    }

    fun isTrustedOrigin(context: Context, url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val targetHttpUrl = url.toHttpUrlOrNull() ?: return false
        val baseHttpUrl = getBaseUrl(context).toHttpUrlOrNull() ?: return false
        return targetHttpUrl.scheme.equals(baseHttpUrl.scheme, ignoreCase = true) &&
                targetHttpUrl.host.equals(baseHttpUrl.host, ignoreCase = true) &&
                targetHttpUrl.port == baseHttpUrl.port
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
            .setTitle("Сервер бэкенда (Debug)")
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
        Toast.makeText(context, "Сервер переключен на:\n$newUrl\nСессия сброшена в целях безопасности.", Toast.LENGTH_LONG).show()
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
