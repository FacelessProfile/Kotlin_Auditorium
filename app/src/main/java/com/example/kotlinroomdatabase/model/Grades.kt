package com.example.kotlinroomdatabase.model

import kotlinx.serialization.Serializable

@Serializable
data class GradeItem(
    val item_id: Int,
    val subject_id: Int,
    val title: String,
    val max_score: Int,
    val item_type: String,
    val deadline: String? = null,
    val created_at: String? = null
)

@Serializable
data class StudentGradePoint(
    val grade_id: Long? = null,
    val item_id: Int,
    val title: String,
    val max_score: Int,
    val item_type: String,
    val deadline: String? = null,
    val score: Int,
    val graded_at: String? = null,
    val comment: String? = null
)

@Serializable
data class SubjectPerformancePoint(
    val subject_id: Int,
    val subject_name: String,
    val score: Int,
    val max_score: Int,
    val percent: Int
)

@Serializable
data class GroupSubjectPerformanceRow(
    val student_id: Int,
    val student_name: String,
    val total_sessions: Int,
    val attended_sessions: Int,
    val total_max: Int,
    val passed_max: Int,
    val current_score: Int
) {
    val percent: Int
        get() = if (total_max > 0) ((current_score * 100) / total_max).coerceAtMost(100) else 0
}

@Serializable
data class TeacherStudentGradesSummary(
    val total_max: Int,
    val passed_max: Int,
    val current_score: Int
)

@Serializable
data class RewardPunishment(
    val id: Int = 0,
    val student_id: Int,
    val subject_id: Int,
    val teacher_id: Int? = null,
    val score: Int,
    val reason: String,
    val created_at: String? = null
)

@Serializable
data class TeacherStudentGradesResponse(
    val student_id: Int,
    val subject_id: Int,
    val grades: List<StudentGradePoint>,
    val rewards: List<RewardPunishment> = emptyList(),
    val summary: TeacherStudentGradesSummary? = null
)

@Serializable
data class StudentSubjectPerformance(
    val subject_id: Int,
    val subject_name: String,
    val percent: Int,
    val current_score: Int,
    val total_max: Int,
    val passed_max: Int,
    val grades: List<StudentGradePoint> = emptyList(),
    val rewards: List<RewardPunishment> = emptyList()
) {
    val displayPercent: Int
        get() = if (total_max > 0) ((current_score * 100) / total_max).coerceAtMost(100) else 0
}

@Serializable
data class StudentAllGradesSummary(
    val current_score: Int,
    val total_max: Int,
    val passed_max: Int,
    val graded_works: Int,
    val total_works: Int
)

@Serializable
data class StudentAllGradesResponse(
    val student_id: Int,
    val subjects: List<StudentSubjectPerformance>,
    val summary: StudentAllGradesSummary? = null
)
