package com.example.kotlinroomdatabase.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.kotlinroomdatabase.MainActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LessonReminderReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_LESSON_REMINDER = "ru.sibsutis.ejournal.ACTION_LESSON_REMINDER"
        const val CHANNEL_ID = "lms_notifications_channel"
        const val EXTRA_SUBJECT = "extra_subject"
        const val EXTRA_TYPE = "extra_type"
        const val EXTRA_ROOM = "extra_room"
        const val EXTRA_START_TIME = "extra_start_time"
        const val EXTRA_MINUTES = "extra_minutes"
        const val EXTRA_START_MILLIS = "extra_start_millis"
        const val EXTRA_IS_TEST = "extra_is_test"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val subject = intent.getStringExtra(EXTRA_SUBJECT) ?: "Занятие"
        val lessonType = intent.getStringExtra(EXTRA_TYPE) ?: "Пара"
        val room = intent.getStringExtra(EXTRA_ROOM) ?: ""
        val startTime = intent.getStringExtra(EXTRA_START_TIME) ?: ""
        val minutes = intent.getIntExtra(EXTRA_MINUTES, 5)
        val isTest = intent.getBooleanExtra(EXTRA_IS_TEST, false) || subject.contains("Тест", ignoreCase = true)

        if (!isTest && !LessonReminderScheduler.isRemindersEnabled(context)) {
            android.util.Log.d("LessonReminderReceiver", "Reminders are disabled in settings, ignoring alarm for $subject")
            return
        }

        val startMillis = intent.getLongExtra(EXTRA_START_MILLIS, 0L).let {
            if (it > 0) it else {
                try {
                    val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                    val cleanTime = if (startTime.count { c -> c == ':' } >= 2) startTime.substringBeforeLast(":") else startTime
                    val parsed = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).parse("$todayStr $cleanTime")
                    parsed?.time ?: (System.currentTimeMillis() + (minutes * 60 * 1000L))
                } catch (e: Exception) {
                    System.currentTimeMillis() + (minutes * 60 * 1000L)
                }
            }
        }

        android.util.Log.d("LessonReminderReceiver", "onReceive triggered for subject=$subject, startTime=$startTime, minutes=$minutes, startMillis=$startMillis, isTest=$isTest")

        // 1. Trigger Vibration ONLY ONCE on initial alarm trigger
        triggerVibration(context)

        // 2. Show Initial Notification with countdown features
        val notificationId = (subject.hashCode() + startTime.hashCode()).let { 
            val abs = if (it < 0) -it else it
            if (abs == 0) 1001 else abs
        }

        showNotification(context, notificationId, subject, lessonType, room, startTime, minutes, startMillis, isTest)

        // 3. Start silent countdown ticker (only for real lessons)
        if (!isTest) {
            LessonCountdownManager.startOrUpdateCountdown(
                context,
                notificationId,
                subject,
                lessonType,
                room,
                startTime,
                startMillis
            )
        }
    }

    private fun triggerVibration(context: Context) {
        try {
            val pattern = longArrayOf(0, 600, 250, 600, 250, 900)
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator ?: (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, -1)
            }
            android.util.Log.d("LessonReminderReceiver", "Vibration executed")
        } catch (e: Exception) {
            android.util.Log.e("LessonReminderReceiver", "Vibration failed", e)
        }
    }

    private fun showNotification(
        context: Context,
        notificationId: Int,
        subject: String,
        lessonType: String,
        room: String,
        startTime: String,
        minutes: Int,
        startMillis: Long,
        isTest: Boolean = false
    ) {
        val sysNotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val soundUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Оповещения о начале пар",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Предупреждение за несколько минут до начала занятия"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 600, 250, 600, 250, 900)
                setSound(soundUri, android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                )
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                setShowBadge(true)
                setBypassDnd(true)
            }
            sysNotificationManager.createNotificationChannel(channel)
        }

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val title = if (isTest) "Тестовое оповещение: $subject" else "Скоро пара: $subject"
        val roomText = if (room.isNotBlank()) " в ауд. $room" else ""
        val contentText = "Через $minutes мин ($startTime) начнётся $lessonType$roomText"
        val noteFooter = if (isTest) "Тест оповещения и вибрации выполнен успешно." else "Не забудьте отметиться на занятии!"

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$contentText\n$noteFooter"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setWhen(startMillis)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOnlyAlertOnce(true) // DO NOT buzz/alert on text updates
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setVibrate(longArrayOf(0, 600, 250, 600, 250, 900))
            .build()

        val notificationManager = NotificationManagerCompat.from(context)
        val areEnabled = notificationManager.areNotificationsEnabled()
        android.util.Log.d("LessonReminderReceiver", "Posting notification ID $notificationId (enabled=$areEnabled)")

        try {
            notificationManager.notify(notificationId, notification)
            android.util.Log.d("LessonReminderReceiver", "Notification posted successfully")
        } catch (e: Exception) {
            android.util.Log.e("LessonReminderReceiver", "Failed to post notification", e)
        }
    }
}
