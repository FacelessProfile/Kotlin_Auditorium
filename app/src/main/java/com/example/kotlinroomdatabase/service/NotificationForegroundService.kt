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
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.data.StudentDatabase
import com.example.kotlinroomdatabase.repository.StudentRepositoryHTTPS
import com.example.kotlinroomdatabase.util.LocalNotificationHelper
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
        private const val CHANNEL_ID = "lms_bg_service_channel"
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
            val channel = NotificationChannel(
                CHANNEL_ID,
                "LMS Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors 2FA and LMS notifications in background"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("LMS Служба уведомлений")
        .setContentText("Мониторинг 2FA и системных сообщений...")
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .build()

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = serviceScope.launch {
            val repo = StudentRepositoryHTTPS(
                applicationContext,
                StudentDatabase.getInstance(applicationContext).studentDao()
            )
            while (isActive) {
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
