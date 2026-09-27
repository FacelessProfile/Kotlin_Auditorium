package com.example.kotlinroomdatabase.model

import kotlinx.serialization.Serializable

@Serializable
data class NfcPayloadResult(
    val payload: String = "",
    val signature: String = "",
    val student_id: Int = 0,
    val tag_uid: String = "",
    val issued_at: Long = 0L
)

@Serializable
data class NfcBindResult(
    val student_id: Int = 0,
    val tag_uid: String = "",
    val payload: String = "",
    val signature: String = "",
    val issued_at: String = ""
)

@Serializable
data class NfcReplaceResult(
    val student_id: Int = 0,
    val old_tag_uid: String = "",
    val new_tag_uid: String = "",
    val payload: String = "",
    val signature: String = "",
    val reason: String = "",
    val replaced_at: String = ""
)

@Serializable
data class NfcRevokeResult(
    val tag_uid: String = "",
    val revoked_at: String = "",
    val reason: String = ""
)

@Serializable
data class DossierStudent(
    val student_id: Int = 0,
    val student_name: String = "",
    val group_id: Int = 0,
    val group_name: String = "",
    val user_id: Int = 0,
    val login: String = "",
    val email: String = "",
    val status: String = "active",
    val avatar_url: String = "",
    val total_cheat_attempts: Int = 0
)

@Serializable
data class DossierNfc(
    val current_tag_uid: String = "",
    val is_revoked_tag: Boolean = false,
    val revoked_reason: String = "",
    val issued_at: String = ""
)

@Serializable
data class DossierLastMark(
    val marked_at: String = "",
    val teacher_name: String = "",
    val subject_name: String = ""
)

@Serializable
data class DossierAttendance(
    val total_sessions: Long = 0,
    val present_sessions: Long = 0,
    val absent_sessions: Long = 0,
    val attendance_percent: Double = 0.0,
    val last_mark: DossierLastMark? = null
)

@Serializable
data class DossierSubjectGrade(
    val subject_id: Int = 0,
    val name: String = "",
    val average: Double = 0.0
)

@Serializable
data class DossierTagHistoryItem(
    val tag_uid: String = "",
    val status: String = "",
    val issued_at: String = "",
    val revoked_at: String = "",
    val reason: String = ""
)

@Serializable
data class DossierGradeItem(
    val item_id: Int = 0,
    val title: String = "",
    val max_score: Double = 0.0,
    val item_type: String = "current",
    val has_grade: Boolean = false,
    val grade_id: Int = 0,
    val score: Double = 0.0,
    val comment: String = "",
    val updated_at: String = ""
)

@Serializable
data class DossierTeacherSubject(
    val subject_id: Int = 0,
    val subject_name: String = "",
    val total_sessions: Long = 0,
    val present_sessions: Long = 0,
    val absent_sessions: Long = 0,
    val attendance_percent: Double = 0.0,
    val average_grade: Double = 0.0,
    val grades_count: Int = 0,
    val last_marked_at: String = "",
    val last_status: String = "",
    val grade_items: List<DossierGradeItem> = emptyList()
)

@Serializable
data class NfcConveyorStudent(
    val student_id: Int = 0,
    val student_name: String = "",
    val group_name: String = "",
    val nfc_id: String = "",
    val user_id: Int? = null,
    val login: String = "",
    val status: String = "",
    val avatar_url: String = ""
)

@Serializable
data class StudentDossier(
    val student: DossierStudent = DossierStudent(),
    val nfc: DossierNfc = DossierNfc(),
    val attendance: DossierAttendance = DossierAttendance(),
    val gpa: Double = 0.0,
    val grades: List<DossierSubjectGrade> = emptyList(),
    val tag_history: List<DossierTagHistoryItem> = emptyList(),
    val is_caller_teacher: Boolean = false,
    val teacher_name: String = "",
    val teacher_subjects: List<DossierTeacherSubject> = emptyList()
)
