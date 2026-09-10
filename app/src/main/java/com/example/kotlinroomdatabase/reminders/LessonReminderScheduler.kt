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
    const val DEFAULT_REMINDER_MINUTES = 5

    fun getReminderMinutes(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_REMINDER_MINUTES, DEFAULT_REMINDER_MINUTES).coerceIn(1, 10)
    }

    fun setReminderMinutes(context: Context, minutes: Int) {
        val validMinutes = minutes.coerceIn(1, 10)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_REMINDER_MINUTES, validMinutes).apply()
    }

    fun scheduleAlarmsForDay(context: Context, schedule: DayScheduleResult) {
        val reminderMinutes = getReminderMinutes(context)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val dateStr = schedule.date // YYYY-MM-DD
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        val now = System.currentTimeMillis()

        for (lesson in schedule.lessons) {
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
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerMillis, pendingIntent)
                            alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
                        } else {
                            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
                        }
                    } catch (e: Exception) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
                        } else {
                            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
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
