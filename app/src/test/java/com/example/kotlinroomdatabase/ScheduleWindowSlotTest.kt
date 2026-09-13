package com.example.kotlinroomdatabase

import com.example.kotlinroomdatabase.fragments.schedule.ScheduleAdapter
import com.example.kotlinroomdatabase.model.LessonScheduleItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleWindowSlotTest {

    @Test
    fun testStandardLessonTimes() {
        val (s1, e1) = ScheduleAdapter.getStandardLessonTimes(1)
        assertEquals("08:00", s1)
        assertEquals("09:35", e1)

        val (s2, e2) = ScheduleAdapter.getStandardLessonTimes(2)
        assertEquals("09:50", s2)
        assertEquals("11:25", e2)

        val (s3, e3) = ScheduleAdapter.getStandardLessonTimes(3)
        assertEquals("11:40", s3)
        assertEquals("13:15", e3)

        val (s4, e4) = ScheduleAdapter.getStandardLessonTimes(4)
        assertEquals("13:45", s4)
        assertEquals("15:20", e4)
    }

    @Test
    fun testWindowSlotCreationBetweenPair2And4() {
        val lessons = listOf(
            LessonScheduleItem(
                lesson_num = 2,
                start_time = "09:50",
                end_time = "11:25",
                subject_name = "Технологии параллелизации",
                lesson_type = "Практические занятия"
            ),
            LessonScheduleItem(
                lesson_num = 4,
                start_time = "13:45",
                end_time = "15:20",
                subject_name = "Архитектура распределенных приложений",
                lesson_type = "Практические занятия"
            )
        )

        val slots = ScheduleAdapter.buildScheduleSlots(lessons, "student")

        // 3 slots: 2-я пара, 3-я пара (Окно), 4-я пара
        assertEquals(3, slots.size)

        // Slot 0: Pair 2
        assertEquals(2, slots[0].lessonNum)
        assertEquals("09:50", slots[0].startTime)
        assertEquals("11:25", slots[0].endTime)
        assertEquals(false, slots[0].isWindow)

        // Slot 1: Pair 3 (Window / Окно)
        assertEquals(3, slots[1].lessonNum)
        assertEquals("11:40", slots[1].startTime)
        assertEquals("13:15", slots[1].endTime)
        assertEquals(true, slots[1].isWindow)
        val windowLesson = slots[1].lessons.first()
        assertEquals("Окно", windowLesson.subject_name)
        assertEquals("Свободное время", windowLesson.lesson_type)
        assertEquals(true, windowLesson.is_window)
        assertEquals(true, windowLesson.is_other_subgroup)

        // Slot 2: Pair 4
        assertEquals(4, slots[2].lessonNum)
        assertEquals("13:45", slots[2].startTime)
        assertEquals("15:20", slots[2].endTime)
        assertEquals(false, slots[2].isWindow)
    }
}
