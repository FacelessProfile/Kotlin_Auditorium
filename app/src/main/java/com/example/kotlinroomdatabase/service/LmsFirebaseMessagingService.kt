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
        Log.d(TAG, "New FCM Registration Token: $token")

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

        // 1. Extract title and body from either Notification payload or Data payload
        val title = remoteMessage.notification?.title
            ?: remoteMessage.data["title"]
            ?: "СибГУТИ Журнал"

        val body = remoteMessage.notification?.body
            ?: remoteMessage.data["body"]
            ?: remoteMessage.data["message"]
            ?: ""

        if (title.isBlank() && body.isBlank()) {
            Log.w(TAG, "Empty FCM message payload received")
            return
        }

        showCloudNotification(title, body, remoteMessage.data)
    }

    private fun showCloudNotification(title: String, body: String, data: Map<String, String>) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create high-priority notification channel for Android O+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Облачные уведомления СибГУТИ",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Важные уведомления о расписании, оценках и безопасности"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400)
                setSound(
                    soundUri,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            for ((key, value) in data) {
                putExtra("fcm_extra_$key", value)
            }
        }

        val pendingIntentId = (System.currentTimeMillis() % 100000).toInt()
        val pendingIntent = PendingIntent.getActivity(
            this,
            pendingIntentId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.example.kotlinroomdatabase.R.drawable.ic_notifications)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setDefaults(NotificationCompat.DEFAULT_ALL)

        try {
            NotificationManagerCompat.from(this).notify(pendingIntentId, notificationBuilder.build())
            Log.d(TAG, "Displayed cloud push notification: $title - $body")
        } catch (e: SecurityException) {
            Log.e(TAG, "Notification permission missing to show push", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to display cloud push notification", e)
        }
    }
}
