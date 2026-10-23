package com.example.kotlinroomdatabase.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.kotlinroomdatabase.MainActivity
import com.example.kotlinroomdatabase.data.StudentDatabase
import com.example.kotlinroomdatabase.repository.StudentRepositoryHTTPS
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class LmsFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "LmsFCM"
        const val CHANNEL_ID = "lms_cloud_notifications_channel"
        private const val PREFS_FCM = "fcm_prefs"
        private const val KEY_FCM_TOKEN = "fcm_token"
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM Registration Token received (length: ${token.length})")

        val fcmPrefs = getSharedPreferences(PREFS_FCM, Context.MODE_PRIVATE)
        fcmPrefs.edit().putString(KEY_FCM_TOKEN, token).apply()

        // If user is currently logged in, sync this new token with our backend
        val authPrefs = getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
        val authToken = authPrefs.getString("auth_token", "") ?: ""
        if (authToken.isNotEmpty()) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val dao = StudentDatabase.getInstance(applicationContext).studentDao()
                    val httpsRepo = StudentRepositoryHTTPS(applicationContext, dao)
                    val res = httpsRepo.registerDeviceToken(token, "android")
                    Log.d(TAG, "Device token registration result: $res")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to register new FCM token on server", e)
                }
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "Received Cloud Push from: ${remoteMessage.from}")

        val mode = com.example.kotlinroomdatabase.reminders.LessonReminderScheduler.getNotificationMode(applicationContext)
        if (mode == com.example.kotlinroomdatabase.reminders.LessonReminderScheduler.MODE_DISABLED) {
            Log.d(TAG, "Cloud push ignored due to notification mode=$mode")
            return
        }

        if (!com.example.kotlinroomdatabase.reminders.LessonReminderScheduler.isNotificationsEnabled(applicationContext)) {
            Log.d(TAG, "Cloud push ignored because notifications are disabled in settings")
            return
        }

        val eventType = remoteMessage.data["event_type"] ?: remoteMessage.data["type"] ?: ""
        if (eventType == "lesson_break") {
            if (!com.example.kotlinroomdatabase.reminders.LessonReminderScheduler.isMidLessonBreakEnabled(applicationContext)) {
                Log.d(TAG, "Lesson break notification ignored: mid-lesson break switch is OFF in student settings")
                return
            }
        }

        // 1. Extract title and body from either Notification payload or Data payload
        val title = remoteMessage.notification?.title
            ?: remoteMessage.data["title"]
            ?: (if (eventType == "lesson_break") "Перемена (экватор пары)" else "СибГУТИ Журнал")

        val body = remoteMessage.notification?.body
            ?: remoteMessage.data["body"]
            ?: remoteMessage.data["message"]
            ?: (if (eventType == "lesson_break") "Преподаватель разрешил перерыв на занятии." else "")

        if (title.isBlank() && body.isBlank()) {
            Log.w(TAG, "Empty FCM message payload received")
            return
        }

        showCloudNotification(title, body, remoteMessage.data, eventType)
    }

    private fun showCloudNotification(title: String, body: String, data: Map<String, String>, eventType: String = "") {
        val isBreak = eventType == "lesson_break"
        val isVibrationOn = com.example.kotlinroomdatabase.reminders.LessonReminderScheduler.isVibrationEnabled(this)
        val isSoundOn = !isBreak && com.example.kotlinroomdatabase.reminders.LessonReminderScheduler.isSoundEnabled(this)

        com.example.kotlinroomdatabase.reminders.LessonReminderScheduler.ensureNotificationChannels(this)
        val channelId = com.example.kotlinroomdatabase.reminders.LessonReminderScheduler.getCloudChannelId(this, isBreak)

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            for ((key, value) in data) {
                putExtra("fcm_extra_$key", value)
            }
        }

        // Stable ID calculation to prevent stacking spam
        val notifId = when {
            isBreak -> 8888
            data["notification_id"] != null -> {
                data["notification_id"]!!.toIntOrNull() ?: 5000 + (title.hashCode() % 1000).let { if (it < 0) -it else it }
            }
            eventType == "security_login_alert" || data["category"] == "system" -> 7777
            data["category"] == "grades" -> 6000 + (title.hashCode() % 1000).let { if (it < 0) -it else it }
            data["category"] == "schedule" -> 5000 + (title.hashCode() % 1000).let { if (it < 0) -it else it }
            else -> 4000 + (title.hashCode() % 1000).let { if (it < 0) -it else it }
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            notifId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(com.example.kotlinroomdatabase.R.drawable.ic_notifications)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(if (isBreak) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)
            .setGroup("lms_notifications_group")
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        if (isBreak) {
            notificationBuilder.setTimeoutAfter(5 * 60 * 1000L) // auto-dismiss after 5 mins
            notificationBuilder.setSilent(true)
            com.example.kotlinroomdatabase.reminders.LessonReminderScheduler.performMinimalBreakVibration(this)
        } else {
            if (isVibrationOn) {
                notificationBuilder.setVibrate(com.example.kotlinroomdatabase.reminders.LessonReminderScheduler.getVibrationPattern(this))
                com.example.kotlinroomdatabase.reminders.LessonReminderScheduler.performVibration(this)
            } else {
                notificationBuilder.setVibrate(longArrayOf(0))
            }
            if (!isSoundOn) {
                notificationBuilder.setSilent(true)
            }
        }

        try {
            NotificationManagerCompat.from(this).notify(notifId, notificationBuilder.build())
            Log.d(TAG, "Displayed cloud push notification: id=$notifId title=$title - body=$body")
        } catch (e: SecurityException) {
            Log.e(TAG, "Notification permission missing to show push", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to display cloud push notification", e)
        }
    }
}
