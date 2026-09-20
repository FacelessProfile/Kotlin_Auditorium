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

object AppErrorLogger {

    private const val TAG = "AppErrorLogger"
    private const val LOGS_DIR_NAME = "logs"
    private const val LATEST_LOG_NAME = "latest_errors.log"
    private const val MAX_LOG_FILES = 15
    private var isHandlerInstalled = false
    private var appContext: Context? = null

    /**
     * Initializes AppErrorLogger with application context and installs crash handler.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        installCrashHandler(context.applicationContext)
    }

    /**
     * Overload to log errors without passing context directly.
     */
    fun logError(
        tag: String,
        message: String,
        throwable: Throwable? = null
    ): File? {
        val ctx = appContext ?: return null
        return logError(ctx, tag, message, throwable)
    }

    /**
     * Installs global uncaught exception handler to capture crashes into .log files.
     */
    fun installCrashHandler(context: Context) {
        appContext = context.applicationContext
        if (isHandlerInstalled) return
        isHandlerInstalled = true

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

            val logContent = buildString {
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
     * Generates and returns a comprehensive diagnostic .log file containing
     * system metrics, device specs, recent recorded errors, and recent app logcat.
     * This guarantees developers ALWAYS receive a full actionable .log file.
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

        // Collect recent logcat for this app process
        val logcatSnippet = try {
            val pid = android.os.Process.myPid()
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "--pid=$pid", "-t", "300"))
            val lines = process.inputStream.bufferedReader(Charsets.UTF_8).readLines()
            if (lines.isNotEmpty()) lines.joinToString("\n") else "No recent logcat output available"
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
                append(cumulative.readText(Charsets.UTF_8).takeLast(6000))
                append("\n")
            }
            append("\n--- RECENT LOGCAT OUTPUT (LAST 300 ENTRIES) ---\n")
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

    private fun cleanOldLogs(logsDir: File) {
        val files = logsDir.listFiles { file -> file.name != LATEST_LOG_NAME && file.extension == "log" }
            ?.sortedByDescending { it.lastModified() } ?: return

        if (files.size > MAX_LOG_FILES) {
            for (i in MAX_LOG_FILES until files.size) {
                try {
                    files[i].delete()
                } catch (_: Exception) {}
            }
        }
    }
}
