package com.example.kotlinroomdatabase

import com.example.kotlinroomdatabase.mascot.MascotEmotion
import com.example.kotlinroomdatabase.mascot.MascotPhrases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreakAndMascotTest {

    @Test
    fun testAllEmotionsHaveValidDrawables() {
        for (mascot in com.example.kotlinroomdatabase.mascot.MascotType.values()) {
            for (emotion in MascotEmotion.values()) {
                val icon = mascot.getIconResId(emotion)
                assertTrue("Mascot $mascot must have valid icon for $emotion", icon != 0)
                assertNotNull(emotion.title)
                assertTrue(emotion.title.isNotBlank())
            }
        }
    }

    @Test
    fun testPhrasesForEveryEmotion() {
        for (mascot in com.example.kotlinroomdatabase.mascot.MascotType.values()) {
            for (emotion in MascotEmotion.values()) {
                val phrase = MascotPhrases.getPhrase(mascot, emotion, streak = 5)
                assertNotNull(phrase)
                assertTrue("Phrase should not be blank for mascot $mascot and emotion $emotion", phrase.isNotBlank())
            }
        }
    }

    @Test
    fun testCelebrationPushes() {
        val push3 = MascotPhrases.getCelebrationPush(3)
        assertTrue(push3.second.contains("3") || push3.first.isNotBlank())
        assertTrue(push3.first.isNotBlank())

        val push5 = MascotPhrases.getCelebrationPush(5)
        assertTrue(push5.first.isNotBlank())
        assertTrue(push5.second.isNotBlank())
    }

    @Test
    fun testMissedLessonPush() {
        val (title, body) = MascotPhrases.getMissedLessonPush()
        assertTrue(title.isNotBlank())
        assertTrue(body.isNotBlank())
    }

    @Test
    fun testWarningPush() {
        val (title, body) = MascotPhrases.getWarningPush("Высшая математика", 15)
        assertTrue(title.isNotBlank())
        assertTrue(body.contains("15 мин"))
        assertTrue(body.contains("Высшая математика"))
    }
}
