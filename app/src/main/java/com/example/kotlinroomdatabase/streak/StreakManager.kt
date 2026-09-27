package com.example.kotlinroomdatabase.streak

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.kotlinroomdatabase.mascot.MascotEmotion
import com.example.kotlinroomdatabase.mascot.MascotPhrases
import com.example.kotlinroomdatabase.mascot.MascotType
import com.example.kotlinroomdatabase.widget.SibgutenokWidgetProvider
import com.example.kotlinroomdatabase.widget.SibgutenokWidgetUpdater
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Синглтон учёта ударного режима (стрика) студента и оценки эмоционального
 * состояния маскота СибгУтёнка в стиле Duolingo.
 */
object StreakManager {

    private const val TAG = "StreakManager"
    private const val PREFS_NAME = "student_streak_prefs"

    private const val KEY_CURRENT_STREAK = "current_streak"
    private const val KEY_BEST_STREAK = "best_streak"
    private const val KEY_TOTAL_ATTENDED = "total_attended_pairs"
    private const val KEY_LAST_DATE = "last_attended_date"
    private const val KEY_LAST_LESSON_ID = "last_attended_lesson_id"
    private const val KEY_CONSECUTIVE_MISSED = "consecutive_missed_count"
    private const val KEY_FREEZES_REMAINING = "freezes_remaining"
    private const val KEY_LAST_FREEZE_WEEK = "last_freeze_week"
    private const val KEY_NOTIF_MODE = "duolingo_notif_mode" // "toxic", "supportive", "off"

    const val MODE_TOXIC = "toxic"
    const val MODE_SUPPORTIVE = "supportive"
    const val MODE_OFF = "off"

    private fun getPrefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun getTodayDateStr(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }

    private const val KEY_MASCOT_TYPE = "active_mascot_type"

    fun getCurrentMascotType(context: Context): MascotType {
        val prefs = getPrefs(context)
        val saved = prefs.getString(KEY_MASCOT_TYPE, null)
        if (saved != null) {
            try {
                return MascotType.valueOf(saved)
            } catch (_: Exception) {}
        }
        return rollRandomMascotType(context)
    }

    fun rollRandomMascotType(context: Context): MascotType {
        val chosen = MascotType.getRandom()
        getPrefs(context).edit().putString(KEY_MASCOT_TYPE, chosen.name).apply()
        return chosen
    }

    fun setMascotType(context: Context, type: MascotType?) {
        val prefs = getPrefs(context)
        if (type == null) {
            prefs.edit().remove(KEY_MASCOT_TYPE).apply()
        } else {
            prefs.edit().putString(KEY_MASCOT_TYPE, type.name).apply()
        }
        notifyWidgetUpdate(context)
    }

    fun setDemoEmotion(context: Context, emotionName: String?) {
        val prefs = getPrefs(context)
        if (emotionName == null) {
            prefs.edit().remove("demo_emotion").apply()
        } else {
            prefs.edit().putString("demo_emotion", emotionName).apply()
        }
        notifyWidgetUpdate(context)
    }

    fun setDemoStreak(context: Context, streak: Int?) {
        val prefs = getPrefs(context)
        if (streak == null) {
            prefs.edit().remove("demo_streak").apply()
        } else {
            prefs.edit().putInt("demo_streak", streak).apply()
        }
        notifyWidgetUpdate(context)
    }

    /**
     * Возвращает текущий стрик пар подряд.
     */
    fun getCurrentStreak(context: Context): Int {
        val prefs = getPrefs(context)
        if (prefs.contains("demo_streak")) {
            return prefs.getInt("demo_streak", 0)
        }
        return prefs.getInt(KEY_CURRENT_STREAK, 0)
    }

    /**
     * Возвращает рекордный стрик.
     */
    fun getBestStreak(context: Context): Int {
        return getPrefs(context).getInt(KEY_BEST_STREAK, 0)
    }

    /**
     * Возвращает общее количество посещённых занятий.
     */
    fun getTotalAttended(context: Context): Int {
        return getPrefs(context).getInt(KEY_TOTAL_ATTENDED, 0)
    }

    /**
     * Возвращает количество доступных «заморозок стрика» (справок).
     */
    fun getFreezesRemaining(context: Context): Int {
        checkAndRefreshWeeklyFreezes(context)
        return getPrefs(context).getInt(KEY_FREEZES_REMAINING, 1)
    }

    private fun checkAndRefreshWeeklyFreezes(context: Context) {
        val prefs = getPrefs(context)
        val currentWeek = Calendar.getInstance().get(Calendar.WEEK_OF_YEAR)
        val lastWeek = prefs.getInt(KEY_LAST_FREEZE_WEEK, -1)
        if (currentWeek != lastWeek) {
            prefs.edit()
                .putInt(KEY_FREEZES_REMAINING, 1)
                .putInt(KEY_LAST_FREEZE_WEEK, currentWeek)
                .apply()
        }
    }

    /**
     * Фиксирует успешную отметку на занятии (QR, NFC или вручную).
     * Увеличивает стрик, сбрасывает пропуски и обновляет виджет СибгУтёнка.
     */
    fun recordAttendance(context: Context, lessonId: Int? = null, lessonName: String? = null): Int {
        val prefs = getPrefs(context)
        val lastLessonId = prefs.getInt(KEY_LAST_LESSON_ID, -1)

        // Предотвращаем дублирование отметки на одном и том же занятии
        if (lessonId != null && lessonId > 0 && lessonId == lastLessonId) {
            Log.d(TAG, "Lesson $lessonId already counted in streak today")
            return getCurrentStreak(context)
        }

        val oldStreak = prefs.getInt(KEY_CURRENT_STREAK, 0)
        val newStreak = oldStreak + 1
        val bestStreak = maxOf(newStreak, prefs.getInt(KEY_BEST_STREAK, 0))
        val totalAttended = prefs.getInt(KEY_TOTAL_ATTENDED, 0) + 1

        prefs.edit()
            .putInt(KEY_CURRENT_STREAK, newStreak)
            .putInt(KEY_BEST_STREAK, bestStreak)
            .putInt(KEY_TOTAL_ATTENDED, totalAttended)
            .putInt(KEY_CONSECUTIVE_MISSED, 0)
            .putString(KEY_LAST_DATE, getTodayDateStr())
            .apply {
                if (lessonId != null) putInt(KEY_LAST_LESSON_ID, lessonId)
            }
            .apply()

        Log.i(TAG, "Attendance recorded! Streak increased: $oldStreak -> $newStreak (Best: $bestStreak)")

        // Рандомим маскота при успешной отметке для разнообразия
        rollRandomMascotType(context)

        // Мгновенное обновление виджета на домашнем экране
        notifyWidgetUpdate(context)

        // Праздничный мотивирующий пуш
        sendCelebrationNotification(context, newStreak)

        return newStreak
    }

    /**
     * Фиксирует факт пропуска пары по расписанию.
     * Если доступна заморозка («справка»), стрик спасается.
     * Иначе стрик сгорает, а счётчик пропусков увеличивается.
     */
    fun recordMissedLesson(context: Context, lessonName: String? = null) {
        val prefs = getPrefs(context)
        val freezes = getFreezesRemaining(context)
        val oldStreak = prefs.getInt(KEY_CURRENT_STREAK, 0)

        if (freezes > 0 && oldStreak > 0) {
            // Активируем заморозку стрика
            prefs.edit()
                .putInt(KEY_FREEZES_REMAINING, freezes - 1)
                .apply()
            Log.i(TAG, "Streak freeze shield activated! Streak preserved: $oldStreak")
            sendFreezeSavedNotification(context, oldStreak)
        } else {
            val missedCount = prefs.getInt(KEY_CONSECUTIVE_MISSED, 0) + 1
            prefs.edit()
                .putInt(KEY_CURRENT_STREAK, 0)
                .putInt(KEY_CONSECUTIVE_MISSED, missedCount)
                .apply()
            Log.w(TAG, "Lesson missed! Streak broken (was $oldStreak). Consecutive missed: $missedCount")
            sendMissedNotification(context)
        }

        notifyWidgetUpdate(context)
    }

    /**
     * Вычисляет текущую эмоцию маскота СибгУтёнка на основе стрика,
     * истории и времени до следующего занятия.
     */
    fun getMascotEmotion(context: Context, minutesUntilNextLesson: Int? = null): MascotEmotion {
        val prefs = getPrefs(context)
        val demo = prefs.getString("demo_emotion", null)
        if (demo != null) {
            try {
                return MascotEmotion.valueOf(demo.uppercase())
            } catch (_: Exception) {}
        }
        val missedCount = prefs.getInt(KEY_CONSECUTIVE_MISSED, 0)
        val streak = getCurrentStreak(context)

        // 1. Критическая угроза отчисления (5+ пропусков) -> Катастрофа / приказ об отчислении
        if (missedCount >= 5) {
            return MascotEmotion.DOOM
        }

        // 2. Длительное отсутствие (3-4 пропуска подряд) -> Призрак в аудитории
        if (missedCount in 3..4) {
            return MascotEmotion.GHOST
        }

        // 3. Злостный прогул (2 пропущенных подряд) -> Безумие / пассивная агрессия
        if (missedCount == 2) {
            return MascotEmotion.UNHINGED
        }

        // 4. Одиночный пропуск -> Слёзы и драма
        if (missedCount == 1) {
            return MascotEmotion.SAD
        }

        // 5. Пара начнётся в течение 45 минут -> Подозрительное наблюдение
        if (minutesUntilNextLesson != null && minutesUntilNextLesson in 1..45) {
            return MascotEmotion.WATCHFUL
        }

        // 6. Огненный стрик (5+ пар подряд) -> Режим Гигачада / огонь
        if (streak >= 5) {
            return MascotEmotion.ON_FIRE
        }

        // 7. Хороший темп или отдых после посещения -> Гордость / радость
        return MascotEmotion.PROUD
    }

    /**
     * Непосредственно обновляет все активные виджеты СибгУтёнка на рабочем столе.
     */
    fun notifyWidgetUpdate(context: Context) {
        try {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, SibgutenokWidgetProvider::class.java)
            val ids = appWidgetManager.getAppWidgetIds(thisWidget)
            for (id in ids) {
                SibgutenokWidgetUpdater.updateWidget(context, appWidgetManager, id)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating widgets directly", e)
        }
    }

    fun getNotificationMode(context: Context): String {
        return getPrefs(context).getString(KEY_NOTIF_MODE, MODE_TOXIC) ?: MODE_TOXIC
    }

    fun setNotificationMode(context: Context, mode: String) {
        getPrefs(context).edit().putString(KEY_NOTIF_MODE, mode).apply()
    }

    private fun sendCelebrationNotification(context: Context, streak: Int) {
        val mode = getNotificationMode(context)
        if (mode == MODE_OFF) return
        val mascot = getCurrentMascotType(context)
        val (title, body) = MascotPhrases.getCelebrationPush(streak, mascot)
        com.example.kotlinroomdatabase.util.LocalNotificationHelper.showSystemNotification(
            context = context,
            notificationId = 7001,
            title = title,
            message = body
        )
    }

    private fun sendFreezeSavedNotification(context: Context, streak: Int) {
        val mode = getNotificationMode(context)
        if (mode == MODE_OFF) return
        com.example.kotlinroomdatabase.util.LocalNotificationHelper.showSystemNotification(
            context = context,
            notificationId = 7002,
            title = "Справка спасла стрик! 🛡️🔥",
            message = "Ты пропустил занятие, но щит «Заморозка» сохранил твой стрик ($streak пар). Не злоупотребляй добротой маскота!"
        )
    }

    private fun sendMissedNotification(context: Context) {
        val mode = getNotificationMode(context)
        if (mode == MODE_OFF) return
        val mascot = getCurrentMascotType(context)
        val (title, body) = MascotPhrases.getMissedLessonPush(mascot)
        com.example.kotlinroomdatabase.util.LocalNotificationHelper.showSystemNotification(
            context = context,
            notificationId = 7003,
            title = title,
            message = body
        )
    }
}
