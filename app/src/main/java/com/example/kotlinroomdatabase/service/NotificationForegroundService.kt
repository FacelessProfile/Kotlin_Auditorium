package com.example.kotlinroomdatabase.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.kotlinroomdatabase.data.StudentDatabase
import com.example.kotlinroomdatabase.repository.StudentRepositoryHTTPS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class NotificationForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var pollingJob: Job? = null

    companion object {
        private const val CHANNEL_ID = "lms_bg_service_channel_silent"
        private const val NOTIFICATION_ID = 9999

        fun startService(context: Context) {
            val intent = Intent(context, NotificationForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, NotificationForegroundService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createServiceNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildForegroundNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, buildForegroundNotification())
        }
        startPolling()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun createServiceNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            try {
                manager.deleteNotificationChannel("lms_bg_service_channel")
            } catch (e: Exception) {}

            val channel = NotificationChannel(
                CHANNEL_ID,
                "Фоновая служба LMS",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Фоновая синхронизация данных"
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
                setSound(null, null)
                lockscreenVisibility = NotificationCompat.VISIBILITY_SECRET
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("LMS")
        .setContentText("Фоновая синхронизация активна")
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setOngoing(true)
        .setSilent(true)
        .setShowWhen(false)
        .setVisibility(NotificationCompat.VISIBILITY_SECRET)
        .build()

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = serviceScope.launch {
            val repo = StudentRepositoryHTTPS(
                applicationContext,
                StudentDatabase.getInstance(applicationContext).studentDao()
            )
            while (isActive) {
                val mode = com.example.kotlinroomdatabase.reminders.LessonReminderScheduler.getNotificationMode(applicationContext)
                val isEnabled = com.example.kotlinroomdatabase.reminders.LessonReminderScheduler.isNotificationsEnabled(applicationContext)
                if (!isEnabled || mode == com.example.kotlinroomdatabase.reminders.LessonReminderScheduler.MODE_DISABLED ||
                    mode == com.example.kotlinroomdatabase.reminders.LessonReminderScheduler.MODE_FCM_ONLY
                ) {
                    Log.d("BgService", "Notification polling stopped due to mode=$mode / enabled=$isEnabled")
                    stopSelf()
                    break
                }

                try {
                    val result = repo.getUserNotifications()
                    if (result is com.example.kotlinroomdatabase.repository.GenericResult.Success) {
                        Log.d("BgService", "Background notifications checked: count=${result.data.size}")
                    }
                } catch (e: Exception) {
                    Log.e("BgService", "Polling error: ${e.message}")
                }
                delay(8000) // check every 8 seconds
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pollingJob?.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
