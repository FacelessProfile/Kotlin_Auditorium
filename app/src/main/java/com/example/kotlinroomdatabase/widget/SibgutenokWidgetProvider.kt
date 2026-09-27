package com.example.kotlinroomdatabase.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.kotlinroomdatabase.mascot.MascotEmotion
import com.example.kotlinroomdatabase.mascot.MascotType
import com.example.kotlinroomdatabase.streak.StreakManager

/**
 * AppWidgetProvider для виджета СибгУтёнка на рабочем столе.
 */
class SibgutenokWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            try {
                SibgutenokWidgetUpdater.updateWidget(context, appWidgetManager, appWidgetId)
            } catch (e: Exception) {
                Log.e("SibgutenokWidget", "Error updating widget id $appWidgetId", e)
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        when (intent.action) {
            ACTION_CYCLE_EMOTION -> {
                val allEmotions = MascotEmotion.values()
                val currentEmotion = StreakManager.getMascotEmotion(context)
                val currentIndex = allEmotions.indexOf(currentEmotion)
                val nextIndex = (currentIndex + 1) % allEmotions.size
                val nextEmotion = allEmotions[nextIndex]

                // Чередуем маскотов (СибгУтёнок <-> Белка-Связист) при кликах
                val currentMascot = StreakManager.getCurrentMascotType(context)
                val nextMascot = if (currentMascot == MascotType.SIB_GUTENOK) {
                    MascotType.SQUIRREL_COMM
                } else {
                    MascotType.SIB_GUTENOK
                }
                StreakManager.setMascotType(context, nextMascot)

                val mockStreak = when (nextEmotion) {
                    MascotEmotion.ON_FIRE -> 12
                    MascotEmotion.PROUD -> 4
                    MascotEmotion.WATCHFUL -> 3
                    MascotEmotion.SAD -> 0
                    MascotEmotion.UNHINGED -> 0
                    MascotEmotion.GHOST -> 0
                    MascotEmotion.DOOM -> 0
                }
                Log.i("SibgutenokWidget", "Cycling emotion: $currentEmotion -> $nextEmotion (streak: $mockStreak, mascot: ${nextMascot.displayName})")
                StreakManager.setDemoStreak(context, mockStreak)
                StreakManager.setDemoEmotion(context, nextEmotion.name)
            }
            ACTION_FORCE_REFRESH -> {
                val emotionName = intent.getStringExtra(EXTRA_EMOTION)
                val streak = if (intent.hasExtra(EXTRA_STREAK)) intent.getIntExtra(EXTRA_STREAK, 0) else null
                val mascotName = intent.getStringExtra(EXTRA_MASCOT)
                val reset = intent.getBooleanExtra("reset_demo", false)

                if (reset) {
                    StreakManager.setDemoEmotion(context, null)
                    StreakManager.setDemoStreak(context, null)
                    StreakManager.setMascotType(context, null)
                } else {
                    if (mascotName != null) {
                        try {
                            val m = MascotType.valueOf(mascotName.uppercase())
                            StreakManager.setMascotType(context, m)
                        } catch (_: Exception) {}
                    }
                    if (emotionName != null) {
                        StreakManager.setDemoEmotion(context, emotionName)
                    }
                    if (streak != null) {
                        StreakManager.setDemoStreak(context, streak)
                    }
                    if (emotionName == null && streak == null && mascotName == null) {
                        StreakManager.notifyWidgetUpdate(context)
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_FORCE_REFRESH = "com.example.kotlinroomdatabase.widget.ACTION_REFRESH_SIB_WIDGET"
        const val ACTION_CYCLE_EMOTION = "com.example.kotlinroomdatabase.widget.ACTION_CYCLE_EMOTION"
        const val EXTRA_EMOTION = "extra_emotion"
        const val EXTRA_STREAK = "extra_streak"
        const val EXTRA_STATUS = "extra_status"
        const val EXTRA_MASCOT = "extra_mascot"
    }
}
