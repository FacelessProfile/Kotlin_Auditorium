package com.example.kotlinroomdatabase

import com.example.kotlinroomdatabase.model.*
import com.example.kotlinroomdatabase.util.AttendancePhotoDrafts
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AttendancePhotoDraftsTest {
    private val drafts = AttendancePhotoDrafts()
    private val roster = listOf(
        AttendanceRosterStudent(student_id = 11, student_name = "Иван Иванов", group_id = 1),
        AttendanceRosterStudent(student_id = 12, student_name = "Иван Иванов", group_id = 1),
        AttendanceRosterStudent(student_id = 21, student_name = "Анна Петрова", group_id = 2)
    )

    private fun photo(id: String = "photo1", vararg ids: Int) = AttendancePhotoJob(
        job_id = id, group_id = 1, state = "ready",
        faces = ids.mapIndexed { index, student -> AttendancePhotoFace(face_id = index, student_id = student) },
        face_count = ids.size
    )

    @Test fun repeatedStudentAcrossPhotosIsCountedOnceById() {
        val confirmed = photo("photo1", 11).copy(state = "confirmed", decisions = listOf(AttendancePhotoDecision(0, 11)))
        val second = photo("photo2", 11, 12)
        val recorded = drafts.alreadyRecorded(roster, listOf(confirmed, second))
        val chosen = drafts.decisions(second).map { it.student_id }.toSet()
        assertEquals(setOf(11), recorded)
        assertEquals(setOf(12), chosen - recorded)
        assertNull(drafts.validationError(second, roster))
        // Send both identities to the canonical confirm endpoint: it keeps a single attendance record
        // per session/student while still allowing the second photo to improve recognition.
        assertEquals(listOf(11, 12), drafts.decisions(second).map { it.student_id })
    }

    @Test fun pollingDoesNotOverwriteTeacherCorrectionsIncludingSkip() {
        drafts.choose("photo1", 0, 12)
        drafts.choose("photo1", 1, 0)
        assertEquals(listOf(12, 0), drafts.decisions(photo("photo1", 11, 11)).map { it.student_id })
        assertEquals(11, drafts.decisions(photo("photo2", 11)).single().student_id)
        assertEquals(listOf(12, 0), drafts.decisions(photo("photo1", 0, 12)).map { it.student_id })
    }

    @Test fun duplicateAssignmentInsideOnePhotoMustBeCorrected() {
        val job = photo("photo1", 11, 11)
        assertNotNull(drafts.validationError(job, roster))
        drafts.choose(job.job_id, 1, 0)
        assertNull(drafts.validationError(job, roster))
    }

    @Test fun sameNamesDoNotMergeDifferentStudents() {
        assertNull(drafts.validationError(photo("photo1", 11, 12), roster))
    }

    @Test fun studentsOutsidePhotoGroupCannotBeConfirmed() {
        assertNotNull(drafts.validationError(photo("photo1", 21), roster))
        assertNotNull(drafts.validationError(photo("photo1", -1), roster))
    }

    @Test fun incompleteProcessingCannotBeConfirmed() {
        assertNotNull(drafts.validationError(photo("photo1", 11).copy(face_count = 2), roster))
        assertNotNull(drafts.validationError(photo("photo1", 11).copy(state = "processing"), roster))
        assertNotNull(drafts.validationError(photo(), roster))
    }

    @Test fun manualNfcQrAndLateMarksAlreadyCountAsRecorded() {
        val recorded = drafts.alreadyRecorded(listOf(
            roster[0].copy(status = "present", marked_by = "nfc_tag"),
            roster[1].copy(status = "late", marked_by = "qr"),
            roster[2].copy(status = "excused", marked_by = "teacher")
        ), emptyList())
        assertEquals(setOf(11, 12, 21), recorded)
    }

    @Test fun retryDiscardsPreviousDraftAndConfirmedPhotosUseServerDecisions() {
        val job = photo("photo1", 11)
        drafts.choose(job.job_id, 0, 12)
        assertEquals(11, drafts.decisions(job.copy(state = "confirmed", decisions = listOf(AttendancePhotoDecision(0, 11)))).single().student_id)
        drafts.clear(job.job_id)
        assertEquals(11, drafts.decisions(job).single().student_id)
    }

    @Test fun responseAcceptsQueueSummariesNullFacesAndNewServerFields() {
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        val photos = json.decodeFromString<AttendancePhotos>("""
            {"enabled":true,"allowed_group_ids":[1],"sample_limit":30,"jobs":[
                {"job_id":"queued","group_id":1,"state":"queued","faces":null,"face_count":0},
                {"job_id":"ready","group_id":1,"state":"ready","face_count":1,"faces":[
                    {"face_id":0,"student_id":11,"bbox":[0,0,80,80],"crop":"jpeg","preview_crop":"preview","quality":{"sharpness":123}}
                ]},
                {"job_id":"confirmed","group_id":1,"state":"confirmed","face_count":1,"faces":[],"decisions":[{"face_id":0,"student_id":11}],"learning":[{"reason":"duplicate"}]}
            ]}
        """.trimIndent())
        assertTrue(photos.jobs[0].faces.isEmpty())
        assertEquals("preview", photos.jobs[1].faces.single().preview_crop)
        assertEquals(setOf(11), drafts.alreadyRecorded(emptyList(), photos.jobs))
    }
}
