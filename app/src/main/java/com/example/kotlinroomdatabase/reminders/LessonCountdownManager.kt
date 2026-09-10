package com.example.kotlinroomdatabase.reminders

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.kotlinroomdatabase.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

object LessonCountdownManager {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val activeCountdowns = ConcurrentHashMap<Int, Job>()

    fun startOrUpdateCountdown(
        context: Context,
        notificationId: Int,
        subject: String,
        lessonType: String,
        room: String,
        startTime: String,
        lessonStartMillis: Long
    ) {
        val appContext = context.applicationContext

        // Cancel previous countdown job for this notificationId if any
        activeCountdowns[notificationId]?.cancel()

        val job = scope.launch {
            val notificationManager = NotificationManagerCompat.from(appContext)
            val openIntent = Intent(appContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                appContext,
                notificationId,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            )

            val roomText = if (room.isNotBlank()) " в ауд. $room" else ""

            // Initial wait before starting minute-by-minute updates
            val firstDelay = (60_000L - (System.currentTimeMillis() % 60_000L)).coerceIn(5_000L, 60_000L)
            delay(firstDelay)

            while (isActive) {
                val now = System.currentTimeMillis()
                val diffMs = lessonStartMillis - now
                val remainingMin = if (diffMs > 0) ((diffMs + 59_999L) / 60_000L).toInt() else 0

                val contentText = if (remainingMin > 0) {
                    "Через $remainingMin мин ($startTime) начнётся $lessonType$roomText"
                } else {
                    "Пара началась! ($startTime) $lessonType$roomText"
                }

                val notification = NotificationCompat.Builder(appContext, LessonReminderReceiver.CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("Скоро пара: $subject")
                    .setContentText(contentText)
                    .setStyle(NotificationCompat.BigTextStyle().bigText("$contentText\nНе забудьте отметиться на занятии!"))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setWhen(lessonStartMillis)
                    .setUsesChronometer(true)
                    .setChronometerCountDown(true)
                    .setOnlyAlertOnce(true) // CRITICAL: NEVER buzz or play sound on countdown updates
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .build()

                try {
                    notificationManager.notify(notificationId, notification)
                    Log.d("LessonCountdownManager", "Updated countdown for '$subject': $remainingMin min left (onlyAlertOnce=true)")
                } catch (e: Exception) {
                    Log.e("LessonCountdownManager", "Failed to update countdown notification", e)
                }

                if (remainingMin <= 0) {
                    // Keep "Пара началась" notification for 3 minutes, then exit countdown loop
                    delay(180_000L)
                    break
                }

                // Wait until the next minute boundary (:00 seconds)
                val delayMs = 60_000L - (System.currentTimeMillis() % 60_000L)
                delay(delayMs.coerceIn(5_000L, 60_000L))
            }

            activeCountdowns.remove(notificationId)
        }

        activeCountdowns[notificationId] = job
    }
}
