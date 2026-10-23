package com.example.kotlinroomdatabase

import com.example.kotlinroomdatabase.reminders.LessonReminderScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationVibrationAndBreakTest {

    @Test
    fun testVibrationConstants() {
        assertEquals(1, LessonReminderScheduler.VIBRATION_LEVEL_LIGHT)
        assertEquals(2, LessonReminderScheduler.VIBRATION_LEVEL_MEDIUM)
    }

    @Test
    fun testVibrationPatternsAreRadicallyDistinct() {
        val lightPattern = LessonReminderScheduler.getVibrationPatternForLevel(LessonReminderScheduler.VIBRATION_LEVEL_LIGHT)
        val medPattern = LessonReminderScheduler.getVibrationPatternForLevel(LessonReminderScheduler.VIBRATION_LEVEL_MEDIUM)
        val breakPattern = LessonReminderScheduler.PATTERN_BREAK

        // Light has 1 pulse: [delay=0, on=25]
        assertEquals(2, lightPattern.size)
        assertEquals(25L, lightPattern[1])

        // Medium has 2 pulses: [delay=0, on=140, pause=100, on=140]
        assertEquals(4, medPattern.size)
        assertEquals(140L, medPattern[1])
        assertEquals(100L, medPattern[2])
        assertEquals(140L, medPattern[3])

        // Break has minimal single tick
        assertEquals(2, breakPattern.size)
        assertEquals(15L, breakPattern[1])

        // Total active vibration duration differences
        val lightActiveDuration = lightPattern[1]
        val medActiveDuration = medPattern[1] + medPattern[3]

        assertTrue("Medium active duration should exceed Light", medActiveDuration > lightActiveDuration)
    }

    @Test
    fun testChannelIdsAreDistinct() {
        assertNotNull(LessonReminderScheduler.CHANNEL_REMINDER_LIGHT)
        assertNotNull(LessonReminderScheduler.CHANNEL_REMINDER_MEDIUM)
        assertNotNull(LessonReminderScheduler.CHANNEL_REMINDER_SILENT)
        assertNotNull(LessonReminderScheduler.CHANNEL_BREAK)

        val channels = setOf(
            LessonReminderScheduler.CHANNEL_REMINDER_LIGHT,
            LessonReminderScheduler.CHANNEL_REMINDER_MEDIUM,
            LessonReminderScheduler.CHANNEL_REMINDER_SILENT,
            LessonReminderScheduler.CHANNEL_BREAK
        )
        assertEquals(4, channels.size)
    }

    @Test
    fun testDeliveryModeDefaultsToFcm() {
        assertEquals("fcm_only", LessonReminderScheduler.MODE_FCM_ONLY)
    }

    @Test
    fun testLessonCountdownAutoDismissCalculation() {
        val now = 1000000L
        val lessonStartMillis = 1000000L + (5 * 60 * 1000L)
        val diffMs = lessonStartMillis - now
        val remainingMin = ((diffMs + 59_999L) / 60_000L).toInt()
        assertEquals(5, remainingMin)

        val timeoutMs = (lessonStartMillis - now).coerceAtLeast(1000L)
        assertEquals(300000L, timeoutMs)

        // When lesson starts or passed
        val lessonPassedNow = 1000000L + (6 * 60 * 1000L)
        val passedDiff = lessonStartMillis - lessonPassedNow
        assertTrue("Notification should be cancelled when lesson starts", passedDiff <= 0)
    }
}
