package com.example.kotlinroomdatabase.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.config.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class AppVersionInfo(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val fileSize: Long,
    val sha256: String,
    val releaseNotes: String,
    val isCritical: Boolean
)

object AppUpdateManager {
    private const val TAG = "AppUpdateManager"

    private fun getOkHttpClient(): OkHttpClient {
        return try {
            val trustAllCerts = arrayOf<TrustManager>(
                object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                }
            )
            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, SecureRandom())
            OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
        } catch (e: Exception) {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
        }
    }

    fun getCurrentVersionCode(context: Context): Int {
        return try {
            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
        } catch (e: Exception) {
            1
        }
    }

    fun getCurrentVersionName(context: Context): String {
        return try {
            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            pInfo.versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }
    }

    suspend fun checkUpdate(context: Context): Result<AppVersionInfo?> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = ServerConfig.getBaseUrl(context)
            val versionUrl = "$baseUrl/api/app/version"
            val request = Request.Builder()
                .url(versionUrl)
                .get()
                .build()

            val client = getOkHttpClient()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP ${response.code}: Не удалось проверить версию"))
            }

            val body = response.body?.string() ?: ""
            val json = JSONObject(body)
            val serverVersionCode = json.optInt("version_code", 0)
            val versionName = json.optString("version_name", "1.0")
            val rawDownloadUrl = json.optString("download_url", "/downloads/app-release.apk")
            val downloadUrl = ServerConfig.resolveMediaUrl(context, rawDownloadUrl)
            val fileSize = json.optLong("file_size", 0L)
            val sha256 = json.optString("sha256", "").trim()
            val releaseNotes = json.optString("release_notes", "").trim()
            val isCritical = json.optBoolean("is_critical", false)

            val currentVersionCode = getCurrentVersionCode(context)
            Log.d(TAG, "Local version: $currentVersionCode, Server version: $serverVersionCode ($versionName)")

            val info = AppVersionInfo(
                versionCode = serverVersionCode,
                versionName = versionName,
                downloadUrl = downloadUrl,
                fileSize = fileSize,
                sha256 = sha256,
                releaseNotes = releaseNotes,
                isCritical = isCritical
            )

            if (serverVersionCode > currentVersionCode) {
                Result.success(info)
            } else {
                Result.success(null) // No update needed
            }
        } catch (e: Exception) {
            Log.e(TAG, "checkUpdate failed", e)
            Result.failure(e)
        }
    }

    suspend fun downloadApk(
        context: Context,
        info: AppVersionInfo,
        onProgress: (percent: Int, downloadedBytes: Long, totalBytes: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val updateDir = File(context.cacheDir, "apk_updates")
            if (!updateDir.exists()) updateDir.mkdirs()

            val targetFile = File(updateDir, "update_v${info.versionCode}.apk")
            if (targetFile.exists()) {
                // If already downloaded and valid, reuse
                if (info.sha256.isNotBlank() && verifySha256(targetFile, info.sha256)) {
                    Log.d(TAG, "Existing cached APK is valid")
                    withContext(Dispatchers.Main) {
                        onProgress(100, targetFile.length(), targetFile.length())
                    }
                    return@withContext Result.success(targetFile)
                } else {
                    targetFile.delete()
                }
            }

            val request = Request.Builder()
                .url(info.downloadUrl)
                .get()
                .build()

            val client = getOkHttpClient()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP ${response.code}: Ошибка скачивания APK"))
            }

            val body = response.body ?: return@withContext Result.failure(Exception("Пустой ответ сервера"))
            val totalBytes = if (body.contentLength() > 0) body.contentLength() else info.fileSize

            val tempFile = File(updateDir, "downloading.tmp")
            if (tempFile.exists()) tempFile.delete()

            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8 * 1024)
                    var bytesRead: Int
                    var totalRead = 0L
                    var lastPercent = -1

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (totalBytes > 0) {
                            val percent = ((totalRead * 100) / totalBytes).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                withContext(Dispatchers.Main) {
                                    onProgress(percent, totalRead, totalBytes)
                                }
                            }
                        }
                    }
                    output.flush()
                }
            }

            if (tempFile.renameTo(targetFile)) {
                // Check SHA-256 if provided
                if (info.sha256.isNotBlank()) {
                    if (!verifySha256(targetFile, info.sha256)) {
                        targetFile.delete()
                        return@withContext Result.failure(SecurityException("Контрольная сумма SHA-256 не совпадает! Файл поврежден."))
                    }
                }
                withContext(Dispatchers.Main) {
                    onProgress(100, targetFile.length(), targetFile.length())
                }
                Result.success(targetFile)
            } else {
                Result.failure(Exception("Не удалось сохранить APK файл"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "downloadApk failed", e)
            Result.failure(e)
        }
    }

    fun verifySha256(file: File, expectedHash: String): Boolean {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8 * 1024)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            val calculatedHash = digest.digest().joinToString("") { "%02x".format(it) }
            calculatedHash.equals(expectedHash.trim(), ignoreCase = true)
        } catch (e: Exception) {
            Log.e(TAG, "verifySha256 error", e)
            false
        }
    }

    fun installApk(context: Context, file: File): Boolean {
        try {
            if (!file.exists()) {
                Toast.makeText(context, "APK файл не найден", Toast.LENGTH_SHORT).show()
                return false
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    Toast.makeText(context, "Разрешите установку из этого источника и повторите установку", Toast.LENGTH_LONG).show()
                    return false
                }
            }

            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "installApk failed", e)
            Toast.makeText(context, "Ошибка запуска установщика: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            return false
        }
    }

    // =========================================================================
    // AUTO-UPDATE SCANNER & DIALOGS
    // =========================================================================

    const val PREFS_NAME = "app_update_prefs"
    const val KEY_AUTO_CHECK_ENABLED = "auto_check_updates_enabled"
    const val KEY_LAST_DISMISSED_VERSION = "last_dismissed_version_code"
    const val KEY_LAST_DISMISSED_TIME = "last_dismissed_time"

    fun isAutoCheckEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_CHECK_ENABLED, true)
    }

    fun setAutoCheckEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_CHECK_ENABLED, enabled)
            .apply()
    }

    /**
     * Automatically scans for app updates on launch.
     * Respects user preference and skips non-critical updates if recently dismissed.
     */
    fun checkForUpdatesOnLaunch(activity: AppCompatActivity) {
        if (!isAutoCheckEnabled(activity)) return

        activity.lifecycleScope.launch {
            val result = checkUpdate(activity)
            if (activity.isFinishing || activity.isDestroyed) return@launch

            result.onSuccess { info ->
                if (info != null) {
                    val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val lastDismissedCode = prefs.getInt(KEY_LAST_DISMISSED_VERSION, -1)
                    val lastDismissedTime = prefs.getLong(KEY_LAST_DISMISSED_TIME, 0L)
                    val now = System.currentTimeMillis()

                    val wasRecentlyDismissed = !info.isCritical &&
                            lastDismissedCode == info.versionCode &&
                            (now - lastDismissedTime < 12 * 60 * 60 * 1000L)

                    if (!wasRecentlyDismissed) {
                        showUpdateDialog(activity, info)
                    }
                }
            }.onFailure { e ->
                Log.d(TAG, "Silent auto-update check skipped: ${e.message}")
            }
        }
    }

    fun showUpdateDialog(
        activity: AppCompatActivity,
        info: AppVersionInfo,
        onDismiss: (() -> Unit)? = null
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        val sizeMb = if (info.fileSize > 0) String.format(Locale.US, "%.1f МБ", info.fileSize / (1024.0 * 1024.0)) else ""
        val msg = buildString {
            append("Доступна новая версия: v${info.versionName} (сборка ${info.versionCode})\n")
            if (sizeMb.isNotBlank()) append("Размер загрузки: $sizeMb\n")
            if (info.releaseNotes.isNotBlank()) {
                append("\nЧто нового:\n")
                append(info.releaseNotes)
            }
        }

        val builder = com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setTitle(if (info.isCritical) "Критическое обновление!" else "Доступно обновление приложения")
            .setMessage(msg)
            .setPositiveButton("Обновить") { _, _ ->
                startDownloadWithDialog(activity, info)
            }

        if (!info.isCritical) {
            builder.setNegativeButton("Позже") { _, _ ->
                val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .putInt(KEY_LAST_DISMISSED_VERSION, info.versionCode)
                    .putLong(KEY_LAST_DISMISSED_TIME, System.currentTimeMillis())
                    .apply()
                onDismiss?.invoke()
            }
            builder.setCancelable(true)
        } else {
            builder.setCancelable(false)
        }

        builder.show()
    }

    fun startDownloadWithDialog(
        activity: AppCompatActivity,
        info: AppVersionInfo
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_app_update_progress, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvUpdateDialogTitle)
        val tvStatus = dialogView.findViewById<TextView>(R.id.tvUpdateDialogStatus)
        val tvBytes = dialogView.findViewById<TextView>(R.id.tvUpdateDialogBytes)
        val pb = dialogView.findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.pbUpdateDialog)

        tvTitle.text = "Загрузка обновления v${info.versionName}..."

        var downloadJob: Job? = null

        val progressDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setView(dialogView)
            .setCancelable(false)
            .apply {
                if (!info.isCritical) {
                    setNegativeButton("Отмена") { _, _ ->
                        downloadJob?.cancel()
                    }
                }
            }
            .create()

        progressDialog.show()

        downloadJob = activity.lifecycleScope.launch {
            val downloadResult = downloadApk(activity, info) { percent, downloaded, total ->
                if (!activity.isFinishing && !activity.isDestroyed) {
                    pb.progress = percent
                    val downloadedMb = String.format(Locale.US, "%.1f", downloaded / (1024.0 * 1024.0))
                    val totalMb = if (total > 0) String.format(Locale.US, "%.1f", total / (1024.0 * 1024.0)) else "?"
                    tvStatus.text = "Скачивание установочного пакета ($percent%)"
                    tvBytes.text = "$downloadedMb / $totalMb МБ"
                }
            }

            if (progressDialog.isShowing) {
                try {
                    progressDialog.dismiss()
                } catch (_: Exception) {}
            }

            downloadResult.onSuccess { apkFile ->
                Toast.makeText(activity, "Файл обновления готов к установке", Toast.LENGTH_SHORT).show()
                installApk(activity, apkFile)
            }.onFailure { err ->
                if (err !is kotlinx.coroutines.CancellationException) {
                    Toast.makeText(activity, "Ошибка скачивания: ${err.localizedMessage ?: "неизвестная ошибка"}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
