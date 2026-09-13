package com.example.kotlinroomdatabase.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.kotlinroomdatabase.MainActivity

object LocalNotificationHelper {

    private const val CHANNEL_ID = "lms_notifications_channel"
    private const val CHANNEL_NAME = "LMS System & 2FA Notifications"
    private const val CHANNEL_DESC = "Push notifications for 2FA codes and LMS updates"
    private const val PREFS_NOTIFIED = "notified_ids_prefs"

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, importance).apply {
                description = CHANNEL_DESC
                enableVibration(true)
                enableLights(true)
            }
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)

            val cloudChannel = NotificationChannel(
                "lms_cloud_notifications_channel",
                "Облачные уведомления СибГУТИ",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Важные уведомления о расписании, оценках и безопасности"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400)
                setShowBadge(true)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(cloudChannel)
        }
    }

    fun showSystemNotificationOnce(
        context: Context,
        notificationId: Int,
        title: String,
        message: String,
        totpCode: String? = null
    ) {
        val prefs = context.getSharedPreferences(PREFS_NOTIFIED, Context.MODE_PRIVATE)
        val key = "notified_$notificationId"
        if (prefs.getBoolean(key, false)) {
            // Already notified for this notification ID, do not spam
            return
        }

        showSystemNotification(context, notificationId, title, message, totpCode)
        prefs.edit().putBoolean(key, true).apply()
    }

    fun showSystemNotification(
        context: Context,
        notificationId: Int,
        title: String,
        message: String,
        totpCode: String? = null
    ) {
        val mode = com.example.kotlinroomdatabase.reminders.LessonReminderScheduler.getNotificationMode(context)
        if (mode == com.example.kotlinroomdatabase.reminders.LessonReminderScheduler.MODE_DISABLED ||
            mode == com.example.kotlinroomdatabase.reminders.LessonReminderScheduler.MODE_FCM_ONLY
        ) {
            return
        }
        if (!com.example.kotlinroomdatabase.reminders.LessonReminderScheduler.isNotificationsEnabled(context)) {
            return
        }

        createNotificationChannel(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (totpCode != null && totpCode.trim().isNotEmpty()) {
                putExtra("COPY_TOTP_CODE", totpCode)
            }
        }

        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isVibrationOn = com.example.kotlinroomdatabase.reminders.LessonReminderScheduler.isVibrationEnabled(context)
        val isSoundOn = com.example.kotlinroomdatabase.reminders.LessonReminderScheduler.isSoundEnabled(context)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        if (isVibrationOn) {
            builder.setVibrate(longArrayOf(0, 300, 200, 300))
        } else {
            builder.setVibrate(longArrayOf(0))
        }

        if (!isSoundOn) {
            builder.setSilent(true)
        }

        val notificationManager: NotificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, builder.build())
    }
}
