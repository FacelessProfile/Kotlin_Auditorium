package com.example.kotlinroomdatabase.model

import kotlinx.serialization.Serializable

@Serializable
data class AttendancePhotos(
    val enabled: Boolean = false,
    val allowed_group_ids: List<Int> = emptyList(),
    val jobs: List<AttendancePhotoJob> = emptyList()
)

@Serializable
data class AttendancePhotoJob(
    val job_id: String,
    val group_id: Int,
    val state: String,
    val faces: List<AttendancePhotoFace> = emptyList(),
    val face_count: Int = 0,
    val decisions: List<AttendancePhotoDecision> = emptyList(),
    val error: String = "",
    val created_at: String = ""
)

@Serializable
data class AttendancePhotoFace(
    val face_id: Int,
    val bbox: List<Int> = emptyList(),
    val crop: String = "",
    val preview_crop: String = "",
    val student_id: Int = 0,
    val score: Double = 0.0,
    val trainable: Boolean = false,
    val learning_allowed: Boolean = false,
    val quality_reason: String = ""
)

@Serializable
data class AttendancePhotoDecision(val face_id: Int, val student_id: Int)

@Serializable
data class AttendancePhotoUpload(val job_id: String, val duplicate: Boolean = false)
