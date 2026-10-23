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

    companion object {
        private const val CHANNEL_ID = "lms_bg_service_channel_silent"
        private const val NOTIFICATION_ID = 9999

        fun startService(context: Context) {
            // Disabled: The app must not hang as an eternal foreground service.
            // Notifications are exclusively delivered via FCM.
            stopService(context)
        }

        fun stopService(context: Context) {
            try {
                val intent = Intent(context, NotificationForegroundService::class.java)
                context.stopService(intent)
            } catch (e: Exception) {
                Log.w("BgService", "Error stopping foreground service: ${e.message}")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Immediately terminate to avoid running as a permanent background service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ECLAIR) {
            stopForeground(true)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        try {
            manager?.cancel(NOTIFICATION_ID)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager?.deleteNotificationChannel(CHANNEL_ID)
                manager?.deleteNotificationChannel("lms_bg_service_channel")
            }
        } catch (_: Exception) {}
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        try {
            manager?.cancel(NOTIFICATION_ID)
        } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
