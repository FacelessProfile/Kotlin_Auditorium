package com.example.kotlinroomdatabase.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.kotlinroomdatabase.model.DayScheduleResult
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object LessonReminderScheduler {

    private const val PREFS_NAME = "app_settings"
    private const val KEY_REMINDER_MINUTES = "lesson_reminder_minutes"
    private const val KEY_REMINDERS_ENABLED = "lesson_reminders_enabled"
    private const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"
    private const val KEY_VIBRATION_ENABLED = "notifications_vibration_enabled"
    private const val KEY_SOUND_ENABLED = "notifications_sound_enabled"
    private const val KEY_NOTIFICATION_MODE = "notification_delivery_mode"

    const val KEY_VIBRATION_LEVEL = "notification_vibration_level"
    const val KEY_MID_LESSON_BREAK_ENABLED = "mid_lesson_break_enabled"

    const val VIBRATION_LEVEL_LIGHT = 1
    const val VIBRATION_LEVEL_MEDIUM = 2

    // Distinct vibration patterns based on pulse count and cadence:
    // Light: single crisp haptic tap (25 ms)
    val PATTERN_LIGHT = longArrayOf(0, 25)
    // Medium / Standard: distinct double pulse (140ms on, 100ms off, 140ms on)
    val PATTERN_MEDIUM = longArrayOf(0, 140, 100, 140)
    // Break: minimal whisper tick (15 ms) - does not disrupt classroom
    val PATTERN_BREAK = longArrayOf(0, 15)

    // Versioned channel IDs to avoid Android OS caching immutable channel vibration settings
    const val CHANNEL_REMINDER_LIGHT = "lms_reminder_vibe_light_v2"
    const val CHANNEL_REMINDER_MEDIUM = "lms_reminder_vibe_med_v2"
    const val CHANNEL_REMINDER_SILENT = "lms_reminder_vibe_none_v2"

    const val CHANNEL_CLOUD_LIGHT = "lms_cloud_vibe_light_v2"
    const val CHANNEL_CLOUD_MEDIUM = "lms_cloud_vibe_med_v2"
    const val CHANNEL_CLOUD_SILENT = "lms_cloud_vibe_none_v2"

    const val CHANNEL_BREAK = "lms_break_notifications_v2"

    const val MODE_FCM_AND_LOCAL = "fcm_and_local"
    const val MODE_FCM_ONLY = "fcm_only"
    const val MODE_LOCAL_ONLY = "local_only"
    const val MODE_DISABLED = "disabled"

    const val DEFAULT_REMINDER_MINUTES = 5

    fun getVibrationLevel(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_VIBRATION_LEVEL, VIBRATION_LEVEL_MEDIUM).coerceIn(1, 2)
    }

    fun setVibrationLevel(context: Context, level: Int) {
        val valid = level.coerceIn(1, 2)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_VIBRATION_LEVEL, valid).apply()
    }

    fun getVibrationPattern(context: Context): LongArray {
        if (!isVibrationEnabled(context)) return longArrayOf(0)
        return getVibrationPatternForLevel(getVibrationLevel(context))
    }

    fun getVibrationPatternForLevel(level: Int): LongArray {
        return when (level) {
            VIBRATION_LEVEL_LIGHT -> PATTERN_LIGHT
            else -> PATTERN_MEDIUM
        }
    }

    fun getReminderChannelId(context: Context): String {
        if (!isVibrationEnabled(context)) return CHANNEL_REMINDER_SILENT
        return when (getVibrationLevel(context)) {
            VIBRATION_LEVEL_LIGHT -> CHANNEL_REMINDER_LIGHT
            else -> CHANNEL_REMINDER_MEDIUM
        }
    }

    fun getCloudChannelId(context: Context, isBreak: Boolean = false): String {
        if (isBreak) return CHANNEL_BREAK
        if (!isVibrationEnabled(context)) return CHANNEL_CLOUD_SILENT
        return when (getVibrationLevel(context)) {
            VIBRATION_LEVEL_LIGHT -> CHANNEL_CLOUD_LIGHT
            else -> CHANNEL_CLOUD_MEDIUM
        }
    }

    fun ensureNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager ?: return

        val soundUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
        val audioAttributes = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        fun createChannel(id: String, name: String, pattern: LongArray?, importance: Int, sound: Boolean) {
            val channel = android.app.NotificationChannel(id, name, importance).apply {
                enableVibration(pattern != null && pattern.isNotEmpty())
                if (pattern != null && pattern.isNotEmpty()) {
                    vibrationPattern = pattern
                }
                if (sound) {
                    setSound(soundUri, audioAttributes)
                } else {
                    setSound(null, null)
                }
                lockscreenVisibility = androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Reminders channels
        createChannel(CHANNEL_REMINDER_LIGHT, "Оповещения о парах (Слабая вибрация)", PATTERN_LIGHT, android.app.NotificationManager.IMPORTANCE_HIGH, isSoundEnabled(context))
        createChannel(CHANNEL_REMINDER_MEDIUM, "Оповещения о парах (Обычная вибрация)", PATTERN_MEDIUM, android.app.NotificationManager.IMPORTANCE_HIGH, isSoundEnabled(context))
        createChannel(CHANNEL_REMINDER_SILENT, "Оповещения о парах (Без вибрации)", null, android.app.NotificationManager.IMPORTANCE_HIGH, isSoundEnabled(context))

        // Cloud channels
        createChannel(CHANNEL_CLOUD_LIGHT, "Облачные уведомления (Слабая вибрация)", PATTERN_LIGHT, android.app.NotificationManager.IMPORTANCE_HIGH, isSoundEnabled(context))
        createChannel(CHANNEL_CLOUD_MEDIUM, "Облачные уведомления (Обычная вибрация)", PATTERN_MEDIUM, android.app.NotificationManager.IMPORTANCE_HIGH, isSoundEnabled(context))
        createChannel(CHANNEL_CLOUD_SILENT, "Облачные уведомления (Без вибрации)", null, android.app.NotificationManager.IMPORTANCE_HIGH, isSoundEnabled(context))

        // Break channel (Always gentle)
        createChannel(CHANNEL_BREAK, "Оповещение о перемене (Тихое)", PATTERN_BREAK, android.app.NotificationManager.IMPORTANCE_DEFAULT, false)
    }

    fun performVibration(context: Context, level: Int = getVibrationLevel(context)) {
        if (!isVibrationEnabled(context)) return
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                vm?.defaultVibrator ?: (context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator)
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            } ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = when (level) {
                    VIBRATION_LEVEL_LIGHT -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            try {
                                android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_CLICK)
                            } catch (_: Exception) {
                                android.os.VibrationEffect.createWaveform(PATTERN_LIGHT, -1)
                            }
                        } else {
                            android.os.VibrationEffect.createWaveform(PATTERN_LIGHT, -1)
                        }
                    }
                    else -> {
                        // Distinct double pulse: 140ms, 100ms pause, 140ms
                        android.os.VibrationEffect.createWaveform(PATTERN_MEDIUM, -1)
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val attrs = android.os.VibrationAttributes.Builder()
                        .setUsage(android.os.VibrationAttributes.USAGE_NOTIFICATION)
                        .build()
                    vibrator.vibrate(effect, attrs)
                } else {
                    val audioAttrs = android.media.AudioAttributes.Builder()
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                        .build()
                    vibrator.vibrate(effect, audioAttrs)
                }
            } else {
                val pattern = getVibrationPatternForLevel(level)
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        } catch (e: Exception) {
            Log.e("LessonReminder", "Vibration failed: ${e.message}")
        }
    }

    fun isMidLessonBreakEnabled(context: Context): Boolean {
        if (!isNotificationsEnabled(context)) return false
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_MID_LESSON_BREAK_ENABLED, false)
    }

    fun setMidLessonBreakEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_MID_LESSON_BREAK_ENABLED, enabled).apply()
    }

    fun performMinimalBreakVibration(context: Context) {
        if (!isMidLessonBreakEnabled(context)) return
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                vm?.defaultVibrator ?: (context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator)
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            } ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_TICK)
                    } catch (_: Exception) {
                        android.os.VibrationEffect.createWaveform(PATTERN_BREAK, -1)
                    }
                } else {
                    android.os.VibrationEffect.createWaveform(PATTERN_BREAK, -1)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val attrs = android.os.VibrationAttributes.Builder()
                        .setUsage(android.os.VibrationAttributes.USAGE_NOTIFICATION)
                        .build()
                    vibrator.vibrate(effect, attrs)
                } else {
                    val audioAttrs = android.media.AudioAttributes.Builder()
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                        .build()
                    vibrator.vibrate(effect, audioAttrs)
                }
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(15L)
            }
        } catch (e: Exception) {
            Log.e("LessonReminder", "Minimal break vibration failed: ${e.message}")
        }
    }

    fun isNotificationsEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val mode = prefs.getString(KEY_NOTIFICATION_MODE, MODE_FCM_ONLY) ?: MODE_FCM_ONLY
        return mode != MODE_DISABLED && prefs.getBoolean(KEY_NOTIFICATIONS_ENABLED, true)
    }

    fun setNotificationsEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_NOTIFICATIONS_ENABLED, enabled).apply()
        if (!enabled) {
            cancelAllReminders(context)
        }
    }

    fun getNotificationMode(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_NOTIFICATION_MODE, MODE_FCM_ONLY) ?: MODE_FCM_ONLY
    }

    fun setNotificationMode(context: Context, mode: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_NOTIFICATION_MODE, mode).apply()
        if (mode == MODE_DISABLED) {
            cancelAllReminders(context)
        }
    }

    fun isVibrationEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // If notifications are disabled globally, vibration is strictly disabled
        if (!isNotificationsEnabled(context)) return false
        return prefs.getBoolean(KEY_VIBRATION_ENABLED, true)
    }

    fun setVibrationEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_VIBRATION_ENABLED, enabled).apply()
    }

    fun isSoundEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!isNotificationsEnabled(context)) return false
        return prefs.getBoolean(KEY_SOUND_ENABLED, true)
    }

    fun setSoundEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_SOUND_ENABLED, enabled).apply()
    }

    fun isRemindersEnabled(context: Context): Boolean {
        if (!isNotificationsEnabled(context)) return false
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_REMINDERS_ENABLED, true)
    }

    fun setRemindersEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_REMINDERS_ENABLED, enabled).apply()
        if (!enabled) {
            cancelAllReminders(context)
        }
    }

    fun cancelAllReminders(context: Context) {
        LessonCountdownManager.cancelAll(context)
    }

    fun getReminderMinutes(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_REMINDER_MINUTES, DEFAULT_REMINDER_MINUTES).coerceIn(1, 15)
    }

    fun setReminderMinutes(context: Context, minutes: Int) {
        val validMinutes = minutes.coerceIn(1, 15)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_REMINDER_MINUTES, validMinutes).apply()
    }

    fun scheduleAlarmsForDay(context: Context, schedule: DayScheduleResult) {
        if (!isRemindersEnabled(context)) {
            Log.d("LessonReminder", "Reminders disabled in settings, skipping scheduling")
            return
        }
        val reminderMinutes = getReminderMinutes(context)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val dateStr = schedule.date // YYYY-MM-DD
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        val now = System.currentTimeMillis()

        for (lesson in schedule.lessons) {
            if (lesson.is_other_subgroup) continue
            val startTimeStr = lesson.start_time.trim()
            if (startTimeStr.isBlank()) continue

            try {
                val cleanTime = if (startTimeStr.count { it == ':' } >= 2) startTimeStr.substringBeforeLast(":") else startTimeStr
                val fullDateStr = "$dateStr $cleanTime"
                val lessonDate = dateFormat.parse(fullDateStr) ?: continue
                val lessonTimeMillis = lessonDate.time

                // Trigger reminder X minutes before lesson starts
                val triggerMillis = lessonTimeMillis - (reminderMinutes * 60 * 1000L)

                // Only schedule future reminders
                if (triggerMillis > now) {
                    val intent = Intent(context, LessonReminderReceiver::class.java).apply {
                        action = LessonReminderReceiver.ACTION_LESSON_REMINDER
                        putExtra(LessonReminderReceiver.EXTRA_SUBJECT, lesson.subject_name)
                        putExtra(LessonReminderReceiver.EXTRA_TYPE, lesson.lesson_type)
                        putExtra(LessonReminderReceiver.EXTRA_ROOM, lesson.room_info)
                        putExtra(LessonReminderReceiver.EXTRA_START_TIME, lesson.start_time)
                        putExtra(LessonReminderReceiver.EXTRA_MINUTES, reminderMinutes)
                        putExtra(LessonReminderReceiver.EXTRA_START_MILLIS, lessonTimeMillis)
                    }

                    val requestCode = (lesson.subject_id * 1000 + lesson.lesson_num + dateStr.hashCode()).let { if (it < 0) -it else it }
                    val pendingIntent = PendingIntent.getBroadcast(
                        context,
                        requestCode,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
                    )

                    try {
                        // Use setExactAndAllowWhileIdle instead of setAlarmClock, so it DOES NOT create a system alarm clock icon
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            if (alarmManager.canScheduleExactAlarms()) {
                                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
                            } else {
                                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
                            }
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
                        } else {
                            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
                        }
                    } catch (e: Exception) {
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
                            } else {
                                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
                            }
                        } catch (ex: Exception) {
                            Log.e("LessonReminder", "Failed to schedule alarm: ${ex.message}")
                        }
                    }
                    Log.d("LessonReminder", "Scheduled reminder for ${lesson.subject_name} at triggerMillis=$triggerMillis (in ${(triggerMillis - now)/1000}s)")
                }
            } catch (e: Exception) {
                Log.e("LessonReminder", "Error parsing lesson time: $startTimeStr", e)
            }
        }
    }

    fun testReminderNow(context: Context) {
        val reminderMinutes = getReminderMinutes(context)
        val intent = Intent(context, LessonReminderReceiver::class.java).apply {
            action = LessonReminderReceiver.ACTION_LESSON_REMINDER
            putExtra(LessonReminderReceiver.EXTRA_SUBJECT, "Тестовая пара")
            putExtra(LessonReminderReceiver.EXTRA_TYPE, "Тестовое занятие")
            putExtra(LessonReminderReceiver.EXTRA_ROOM, "101")
            putExtra(LessonReminderReceiver.EXTRA_START_TIME, "10:15")
            putExtra(LessonReminderReceiver.EXTRA_MINUTES, reminderMinutes)
            putExtra(LessonReminderReceiver.EXTRA_IS_TEST, true)
        }

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try {
                context.sendBroadcast(intent)
            } catch (e: Exception) {
                Log.e("LessonReminderScheduler", "Failed to send test broadcast", e)
            }
        }, 1500L)
    }
}
