package com.example.kotlinroomdatabase.util

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.kotlinroomdatabase.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Robust error and crash logger that captures critical diagnostic data,
 * stacktraces, device information, and recent application logcat into
 * structured .log files stored in the app's internal files directory.
 */
object AppErrorLogger {

    private const val TAG = "AppErrorLogger"
    private const val LOGS_DIR_NAME = "app_logs"
    private const val LATEST_LOG_NAME = "latest_errors.log"
    private const val MAX_LOG_FILES = 10
    private const val MAX_LOGCAT_LINES = 300

    /**
     * Scrubs sensitive data (passwords, JWT tokens, Bearer headers, TOTP secrets)
     * from logs before saving or exporting (KA-03).
     */
    fun sanitizeLogText(input: String): String {
        return input
            // 1. Redact Authorization headers and Bearer tokens
            .replace(Regex("(?i)Bearer\\s+[A-Za-z0-9-_=]+\\.[A-Za-z0-9-_=]+\\.[A-Za-z0-9-_=]+"), "Bearer [REDACTED_JWT]")
            .replace(Regex("(?i)(Authorization:\\s*Bearer\\s*)[^\\r\\n]+"), "$1[REDACTED]")
            .replace(Regex("(?i)(Authorization:\\s*)[^\\r\\n]+"), "$1[REDACTED]")
            // 2. Redact standalone JWT tokens without Bearer prefix
            .replace(Regex("""\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]+\b"""), "[REDACTED_JWT]")
            // 3. Redact passwords in JSON, query params, form-data, and unquoted key=val formats
            .replace(Regex("""(?i)("?password"?\s*[:=]\s*")[^"]+(")"""), """$1[REDACTED]$2""")
            .replace(Regex("""(?i)("?pass"?\s*[:=]\s*")[^"]+(")"""), """$1[REDACTED]$2""")
            .replace(Regex("""(?i)\b(password|pass|secret)\s*=\s*[^\s,;&\r\n]+"""), "$1=[REDACTED]")
            // 4. Redact tokens (auth_token, refresh_token) in JSON and key=val
            .replace(Regex("""(?i)("?token"?\s*[:=]\s*")[^"]+(")"""), """$1[REDACTED]$2""")
            .replace(Regex("""(?i)("?auth_token"?\s*[:=]\s*")[^"]+(")"""), """$1[REDACTED]$2""")
            .replace(Regex("""(?i)("?refresh_token"?\s*[:=]\s*")[^"]+(")"""), """$1[REDACTED]$2""")
            .replace(Regex("""(?i)\b(token|auth_token|refresh_token|refreshToken)\s*=\s*[^\s,;&\r\n]+"""), "$1=[REDACTED]")
            // 5. Redact TOTP secret
            .replace(Regex("""(?i)("?totp_secret"?\s*[:=]\s*")[^"]+(")"""), """$1[REDACTED]$2""")
            .replace(Regex("""(?i)(totp_secret\s*[:=]\s*)[^\s,;&\r\n]+"""), "$1[REDACTED]")
            // 6. Redact FCM tokens (key-value and standalone long tokens)
            .replace(Regex("""(?i)\b(fcm_token|fcmToken|registration_token)\s*[:=]\s*"?([^\s,;&"\r\n]+)"?"""), "$1=[REDACTED]")
            .replace(Regex("""\b[a-zA-Z0-9_\-]{22}:[a-zA-Z0-9_\-]{100,}\b"""), "[REDACTED_FCM_TOKEN]")
            .replace(Regex("""\b[a-zA-Z0-9_\-]{140,200}\b"""), "[REDACTED_TOKEN]")
    }

    private var appContext: Context? = null

    /**
     * Initializes logger and crash handler with application context.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        initCrashHandler(context)
    }

    /**
     * Overloaded logError that uses cached appContext or falls back to standard Log.e.
     */
    fun logError(tag: String, message: String, throwable: Throwable? = null): File? {
        val ctx = appContext
        return if (ctx != null) {
            logError(ctx, tag, message, throwable)
        } else {
            try {
                Log.e(tag, message, throwable)
            } catch (_: Throwable) {
                // In unmocked JVM unit test environment
            }
            null
        }
    }

    /**
     * Initializes global uncaught exception handler so any crash automatically creates a .log file.
     */
    fun initCrashHandler(context: Context) {
        appContext = context.applicationContext
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                logError(
                    context = context,
                    tag = "CRASH",
                    message = "Unhandled crash on thread [${thread.name}]",
                    throwable = throwable
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write crash log", e)
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    /**
     * Logs an error into an individual timestamped .log file and appends to latest_errors.log
     */
    fun logError(
        context: Context,
        tag: String,
        message: String,
        throwable: Throwable? = null
    ): File? {
        return try {
            val logsDir = File(context.filesDir, LOGS_DIR_NAME).apply {
                if (!exists()) mkdirs()
            }

            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            val fileDate = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())

            val stackTraceStr = throwable?.let {
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                it.printStackTrace(pw)
                sw.toString()
            } ?: "No stacktrace"

            val deviceName = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL} (${Build.DEVICE})"
            val osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
            val appVersion = "${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})"

            val rawLogContent = buildString {
                append("================================================================\n")
                append("TIMESTAMP:   ").append(timestamp).append("\n")
                append("TAG:         ").append(tag).append("\n")
                append("APP VERSION: ").append(appVersion).append("\n")
                append("OS VERSION:  ").append(osVersion).append("\n")
                append("DEVICE:      ").append(deviceName).append("\n")
                append("THREAD:      ").append(Thread.currentThread().name).append("\n")
                append("MESSAGE:     ").append(message).append("\n")
                if (throwable != null) {
                    append("ERROR TYPE:  ").append(throwable.javaClass.name).append("\n")
                    append("STACKTRACE:\n").append(stackTraceStr).append("\n")
                }
                append("SYSTEM METRICS:\n")
                append("  ").append(SystemInfoHelper.getDiagnosticInfoMarkdown(context).replace("\n", "\n  ")).append("\n")
                append("================================================================\n\n")
            }

            // Sanitize sensitive info
            val logContent = sanitizeLogText(rawLogContent)

            // Write individual timestamped log file
            val logFile = File(logsDir, "error_${fileDate}.log")
            logFile.writeText(logContent, Charsets.UTF_8)

            // Append to cumulative latest_errors.log
            val cumulativeLog = File(logsDir, LATEST_LOG_NAME)
            cumulativeLog.appendText(logContent, Charsets.UTF_8)

            // Clean up older logs if needed
            cleanOldLogs(logsDir)

            logFile
        } catch (e: Exception) {
            Log.e(TAG, "Error writing log file", e)
            null
        }
    }

    /**
     * Returns the most relevant error log file to send with feedback/support requests.
     */
    fun getLatestLogFile(context: Context): File? {
        val logsDir = File(context.filesDir, LOGS_DIR_NAME)
        if (!logsDir.exists()) return null

        val cumulative = File(logsDir, LATEST_LOG_NAME)
        if (cumulative.exists() && cumulative.length() > 0) {
            return cumulative
        }

        return logsDir.listFiles { file -> file.extension == "log" }
            ?.maxByOrNull { it.lastModified() }
    }

    /**
     * Generates a comprehensive, self-contained diagnostic report file containing
     * system metrics, device specs, recent recorded errors, and recent app logcat.
     * All sensitive tokens and credentials are scrubbed (KA-03).
     */
    fun getOrCreateDiagnosticLogFile(context: Context): File {
        val logsDir = File(context.filesDir, LOGS_DIR_NAME).apply {
            if (!exists()) mkdirs()
        }

        val fileDate = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
        val diagFile = File(logsDir, "diagnostics_${fileDate}.log")
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val deviceName = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL} (${Build.DEVICE})"
        val osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        val appVersion = "${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})"

        // Collect recent logcat for this app process and sanitize
        val logcatSnippet = try {
            val pid = android.os.Process.myPid()
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "--pid=$pid", "-t", "$MAX_LOGCAT_LINES"))
            val lines = process.inputStream.bufferedReader(Charsets.UTF_8).readLines()
            if (lines.isNotEmpty()) {
                sanitizeLogText(lines.joinToString("\n"))
            } else {
                "No recent logcat output available"
            }
        } catch (e: Exception) {
            "Failed to collect logcat: ${e.message}"
        }

        val cumulative = File(logsDir, LATEST_LOG_NAME)

        val content = buildString {
            append("================================================================\n")
            append("SIB SUTIS EJOURNAL - APPLICATION & DEVICE DIAGNOSTIC LOG\n")
            append("TIMESTAMP:    ").append(timestamp).append("\n")
            append("APP VERSION:  ").append(appVersion).append("\n")
            append("OS VERSION:   ").append(osVersion).append("\n")
            append("DEVICE:       ").append(deviceName).append("\n")
            append("PACKAGE:      ").append(context.packageName).append("\n")
            append("SYSTEM METRICS:\n")
            append("  ").append(SystemInfoHelper.getDiagnosticInfoMarkdown(context).replace("\n", "\n  ")).append("\n")
            if (cumulative.exists() && cumulative.length() > 0) {
                append("\n--- RECENT RECORDED APP ERRORS (CUMULATIVE LOG) ---\n")
                append(sanitizeLogText(cumulative.readText(Charsets.UTF_8).takeLast(6000)))
                append("\n")
            }
            append("\n--- RECENT LOGCAT OUTPUT (LAST 300 ENTRIES - SANITIZED) ---\n")
            append(logcatSnippet)
            append("\n================================================================\n")
        }

        diagFile.writeText(content, Charsets.UTF_8)
        cleanOldLogs(logsDir)
        return diagFile
    }

    /**
     * Returns all error log files.
     */
    fun getAllLogFiles(context: Context): List<File> {
        val logsDir = File(context.filesDir, LOGS_DIR_NAME)
        if (!logsDir.exists()) return emptyList()
        return logsDir.listFiles { file -> file.extension == "log" }?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /**
     * Keeps only the newest MAX_LOG_FILES to prevent unbounded disk usage.
     */
    private fun cleanOldLogs(logsDir: File) {
        try {
            val files = logsDir.listFiles { file -> file.extension == "log" && file.name != LATEST_LOG_NAME }
                ?.sortedByDescending { it.lastModified() }
                ?: return

            if (files.size > MAX_LOG_FILES) {
                files.drop(MAX_LOG_FILES).forEach { it.delete() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning old logs", e)
        }
    }
}
