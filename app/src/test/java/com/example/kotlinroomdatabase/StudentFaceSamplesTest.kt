package com.example.kotlinroomdatabase

import com.example.kotlinroomdatabase.model.StudentFaceSampleSlot
import com.example.kotlinroomdatabase.model.StudentFaceSamplesStatus
import com.example.kotlinroomdatabase.util.StudentFacePhotoManager
import org.junit.Assert.*
import org.junit.Test

class StudentFaceSamplesTest {

    @Test
    fun testInitialStatusContainsThreeAngles() {
        val status = StudentFaceSamplesStatus.initial()
        assertEquals(3, status.samples.size)
        assertEquals(0, status.uploadedCount())
        assertFalse(status.isComplete())

        val angles = status.samples.map { it.angle }
        assertTrue(angles.contains(StudentFaceSamplesStatus.ANGLE_FRONTAL))
        assertTrue(angles.contains(StudentFaceSamplesStatus.ANGLE_THREE_QUARTER))
        assertTrue(angles.contains(StudentFaceSamplesStatus.ANGLE_PROFILE))
    }

    @Test
    fun testUploadedCountAndCompletion() {
        val sampleList = listOf(
            StudentFaceSampleSlot(angle = StudentFaceSamplesStatus.ANGLE_FRONTAL, uploaded = true),
            StudentFaceSampleSlot(angle = StudentFaceSamplesStatus.ANGLE_THREE_QUARTER, uploaded = true),
            StudentFaceSampleSlot(angle = StudentFaceSamplesStatus.ANGLE_PROFILE, uploaded = false)
        )
        val status = StudentFaceSamplesStatus(samples = sampleList)
        assertEquals(2, status.uploadedCount())
        assertFalse(status.isComplete())
        assertTrue(status.isAngleUploaded("frontal"))
        assertTrue(status.isAngleUploaded("three_quarter"))
        assertFalse(status.isAngleUploaded("profile"))

        val completeStatus = StudentFaceSamplesStatus(
            samples = listOf(
                StudentFaceSampleSlot(angle = StudentFaceSamplesStatus.ANGLE_FRONTAL, uploaded = true),
                StudentFaceSampleSlot(angle = StudentFaceSamplesStatus.ANGLE_THREE_QUARTER, uploaded = true),
                StudentFaceSampleSlot(angle = StudentFaceSamplesStatus.ANGLE_PROFILE, uploaded = true)
            )
        )
        assertEquals(3, completeStatus.uploadedCount())
        assertTrue(completeStatus.isComplete())
    }

    @Test
    fun testAngleDisplayTitlesAndInstructions() {
        assertEquals("Анфас", StudentFaceSamplesStatus.titleForAngle(StudentFaceSamplesStatus.ANGLE_FRONTAL))
        assertEquals("Поворот ¾", StudentFaceSamplesStatus.titleForAngle(StudentFaceSamplesStatus.ANGLE_THREE_QUARTER))
        assertEquals("Профиль", StudentFaceSamplesStatus.titleForAngle(StudentFaceSamplesStatus.ANGLE_PROFILE))

        assertEquals("Смотрите прямо в камеру.", StudentFaceSamplesStatus.instructionForAngle(StudentFaceSamplesStatus.ANGLE_FRONTAL))
        assertEquals("Поверните голову примерно на 45°.", StudentFaceSamplesStatus.instructionForAngle(StudentFaceSamplesStatus.ANGLE_THREE_QUARTER))
        assertEquals("Поверните лицо боком к камере.", StudentFaceSamplesStatus.instructionForAngle(StudentFaceSamplesStatus.ANGLE_PROFILE))
    }

    @Test
    fun testFileConstraintsMatchRequirements() {
        assertEquals(15 * 1024 * 1024, StudentFacePhotoManager.MAX_BYTES)
        assertEquals(20_000_000L, StudentFacePhotoManager.MAX_PIXELS)
    }
}
