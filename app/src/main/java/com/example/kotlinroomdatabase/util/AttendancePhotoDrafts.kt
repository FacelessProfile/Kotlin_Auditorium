package com.example.kotlinroomdatabase.util

import com.example.kotlinroomdatabase.model.AttendancePhotoDecision
import com.example.kotlinroomdatabase.model.AttendancePhotoJob
import com.example.kotlinroomdatabase.model.AttendanceRosterStudent

/** Edits survive polling and switching photos; server suggestions remain drafts until confirmed. */
class AttendancePhotoDrafts {
    private val edits = mutableMapOf<String, MutableMap<Int, Int>>()

    fun choose(jobId: String, faceId: Int, studentId: Int) {
        edits.getOrPut(jobId) { mutableMapOf() }[faceId] = studentId
    }

    fun clear(jobId: String) { edits.remove(jobId) }

    fun decisions(job: AttendancePhotoJob): List<AttendancePhotoDecision> {
        if (job.state == "confirmed") return job.decisions
        return job.faces.map { face ->
            AttendancePhotoDecision(face.face_id, edits[job.job_id]?.get(face.face_id) ?: face.student_id)
        }
    }

    fun validationError(job: AttendancePhotoJob, roster: List<AttendanceRosterStudent>): String? {
        if (job.state != "ready" || job.faces.size != job.face_count || job.faces.isEmpty()) {
            return "Дождитесь обработки фотографии"
        }
        val selected = decisions(job).map { it.student_id }.filter { it != 0 }
        if (selected.size != selected.distinct().size) {
            return "Один студент выбран для нескольких лиц. Исправьте выбор или пропустите лишнее лицо."
        }
        val allowed = roster.filter { it.group_id == job.group_id }.map { it.student_id }.toSet()
        if (selected.any { it !in allowed }) return "Выберите студентов из группы на фотографии"
        return null
    }

    fun alreadyRecorded(roster: List<AttendanceRosterStudent>, jobs: List<AttendancePhotoJob>): Set<Int> =
        roster.filter { it.status.lowercase() in setOf("present", "ontime", "late", "excused", "valid") }
            .map { it.student_id }.toSet() +
            jobs.filter { it.state == "confirmed" }.flatMap { it.decisions }
                .map { it.student_id }.filter { it > 0 }
}
