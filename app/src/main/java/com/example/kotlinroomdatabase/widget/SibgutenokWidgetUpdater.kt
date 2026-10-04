package com.example.kotlinroomdatabase.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.kotlinroomdatabase.MainActivity
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.mascot.MascotEmotion
import com.example.kotlinroomdatabase.mascot.MascotPhrases
import com.example.kotlinroomdatabase.mascot.MascotType
import com.example.kotlinroomdatabase.qr.CustomScannerActivity
import com.example.kotlinroomdatabase.streak.StreakManager

/**
 * Отвечает за генерацию и обновление RemoteViews для виджета СибгУтёнка и Белки-Связиста.
 */
object SibgutenokWidgetUpdater {

    fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        overrideEmotion: MascotEmotion? = null,
        overrideStreak: Int? = null,
        overrideStatus: String? = null,
        overrideMascot: MascotType? = null
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_sibgutenok_4x2)

        // 1. Получаем стрик, эмоцию и маскота
        val streak = overrideStreak ?: StreakManager.getCurrentStreak(context)
        val emotion = overrideEmotion ?: StreakManager.getMascotEmotion(context)
        val mascotType = overrideMascot ?: StreakManager.getCurrentMascotType(context)
        val quote = MascotPhrases.getPhrase(mascotType, emotion, streak)

        // 2. Устанавливаем визуал маскота и текст
        views.setImageViewResource(R.id.ivWidgetMascot, mascotType.getIconResId(emotion))
        views.setTextViewText(R.id.tvWidgetMascotTitle, "${mascotType.displayName}:")
        val streakText = when {
            streak <= 0 -> "😴 0 пар"
            streak in 1..4 -> "🔥 $streak ${getPluralPairs(streak)}"
            else -> "⚡ $streak ${getPluralPairs(streak)}"
        }
        views.setTextViewText(R.id.tvWidgetStreakCount, streakText)
        views.setTextViewText(R.id.tvWidgetQuote, quote)

        // 3. Статус текущего или следующего занятия
        val statusText = if (overrideStatus != null) {
            overrideStatus
        } else {
            val attPrefs = context.getSharedPreferences("student_attendance_state_prefs", Context.MODE_PRIVATE)
            val isAttended = attPrefs.getBoolean("is_attended", false)
            val lessonName = attPrefs.getString("active_lesson_name", "")
            val expiresAt = attPrefs.getLong("lesson_expires_at", 0L)
            val now = System.currentTimeMillis()

            when {
                isAttended && !lessonName.isNullOrBlank() && expiresAt > now -> {
                    "✅ На паре: $lessonName"
                }
                !lessonName.isNullOrBlank() && expiresAt > now -> {
                    "⚡ Идёт пара: $lessonName"
                }
                else -> {
                    "Пары окончены 🎉"
                }
            }
        }
        views.setTextViewText(R.id.tvWidgetNextLesson, statusText)

        // 4. Клик по кнопке «Отметиться» -> Прямой запуск QR-сканера
        val scanIntent = Intent(context, CustomScannerActivity::class.java).apply {
            putExtra(CustomScannerActivity.EXTRA_PROMPT, "Наведите камеру на QR-код занятия")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val scanPendingIntent = PendingIntent.getActivity(
            context,
            appWidgetId * 10 + 1,
            scanIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.btnWidgetScan, scanPendingIntent)

        // 5. Клик по самому виджету и маскоту -> Открытие главного экрана приложения
        val appIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val appPendingIntent = PendingIntent.getActivity(
            context,
            appWidgetId * 10 + 2,
            appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widgetRoot, appPendingIntent)
        views.setOnClickPendingIntent(R.id.ivWidgetMascot, appPendingIntent)

        // Применяем обновление
        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    private fun getPluralPairs(n: Int): String {
        val n100 = n % 100
        val n10 = n % 10
        return when {
            n100 in 11..19 -> "пар"
            n10 == 1 -> "пара"
            n10 in 2..4 -> "пары"
            else -> "пар"
        }
    }
}
