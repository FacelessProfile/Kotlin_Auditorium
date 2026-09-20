package com.example.kotlinroomdatabase.util

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.example.kotlinroomdatabase.BuildConfig
import com.example.kotlinroomdatabase.config.ServerConfig

object SystemInfoHelper {

    /**
     * Collects detailed diagnostic information about device, OS, network, memory, and app.
     * Formats into a clean Markdown block for developer inspection.
     */
    fun getDiagnosticInfoMarkdown(context: Context): String {
        val deviceName = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL} (${Build.DEVICE})"
        val androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        val appVersion = "${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})"

        // Display
        val dm = context.resources.displayMetrics
        val screen = "${dm.widthPixels}x${dm.heightPixels} (${dm.densityDpi} dpi)"

        // Network
        val networkType = getNetworkType(context)

        // Battery
        val batteryStatus = getBatteryStatus(context)

        // Memory
        val memoryInfo = getMemoryInfo(context)

        // Storage
        val storageInfo = getAvailableStorage()

        // Server
        val serverUrl = ServerConfig.getBaseUrl(context)

        // User Context
        val prefs = context.getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
        val userRole = prefs.getString("user_role", "student") ?: "student"
        val group = prefs.getString("student_group", "СибГУТИ") ?: "СибГУТИ"
        val roleLabel = RoleUtils.getRoleLabel(userRole)

        return buildString {
            append("\n\n---\n")
            append("### 📱 Диагностика устройства\n")
            append("- **Устройство:** ").append(deviceName).append("\n")
            append("- **ОС:** ").append(androidVersion).append("\n")
            append("- **Приложение:** ").append(appVersion).append("\n")
            append("- **Экран:** ").append(screen).append("\n")
            append("- **Сеть:** ").append(networkType).append("\n")
            append("- **Батарея:** ").append(batteryStatus).append("\n")
            append("- **Память (RAM):** ").append(memoryInfo).append("\n")
            append("- **Хранилище:** ").append(storageInfo).append("\n")
            append("- **Сервер:** ").append(serverUrl).append("\n")
            append("- **Профиль:** ").append(roleLabel).append(" (").append(group).append(")\n")
            append("---")
        }
    }

    private fun getNetworkType(context: Context): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return "Неизвестно"
            val activeNetwork = cm.activeNetwork ?: return "Офлайн (нет сети)"
            val caps = cm.getNetworkCapabilities(activeNetwork) ?: return "Офлайн"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Мобильная сеть (LTE/5G)"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                else -> "Подключено"
            }
        } catch (_: Exception) {
            "Неизвестно"
        }
    }

    private fun getBatteryStatus(context: Context): String {
        return try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryIntent = context.registerReceiver(null, filter) ?: return "Неизвестно"
            val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

            val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
            val chargeText = if (isCharging) " (заряжается)" else " (от батареи)"
            if (pct >= 0) "$pct%$chargeText" else "Неизвестно"
        } catch (_: Exception) {
            "Неизвестно"
        }
    }

    private fun getMemoryInfo(context: Context): String {
        return try {
            val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return "Неизвестно"
            val memInfo = ActivityManager.MemoryInfo()
            actManager.getMemoryInfo(memInfo)
            val availMB = memInfo.availMem / (1024 * 1024)
            val totalMB = memInfo.totalMem / (1024 * 1024)
            "$availMB МБ свободно из $totalMB МБ"
        } catch (_: Exception) {
            "Неизвестно"
        }
    }

    private fun getAvailableStorage(): String {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val bytesAvailable = stat.availableBlocksLong * stat.blockSizeLong
            val mbAvailable = bytesAvailable / (1024 * 1024)
            if (mbAvailable >= 1024) {
                String.format("%.1f ГБ свободно", mbAvailable / 1024.0)
            } else {
                "$mbAvailable МБ свободно"
            }
        } catch (_: Exception) {
            "Неизвестно"
        }
    }
}
