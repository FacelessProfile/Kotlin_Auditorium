package com.example.kotlinroomdatabase.model

import kotlinx.serialization.Serializable

@Serializable
data class StudentFaceSampleSlot(
    val angle: String, // "frontal", "three_quarter", "profile"
    val uploaded: Boolean = false,
    val url: String? = null,
    val created_at: String? = null,
    val local_path: String? = null
)

@Serializable
data class StudentFaceSamplesStatus(
    val enabled: Boolean = true,
    val group_allowed: Boolean = true,
    val samples: List<StudentFaceSampleSlot> = emptyList(),
    val message: String = ""
) {
    fun isAngleUploaded(angle: String): Boolean =
        samples.firstOrNull { it.angle.equals(angle, ignoreCase = true) }?.uploaded == true

    fun getSample(angle: String): StudentFaceSampleSlot? =
        samples.firstOrNull { it.angle.equals(angle, ignoreCase = true) }

    fun uploadedCount(): Int = samples.count { it.uploaded }
    fun isComplete(): Boolean = uploadedCount() >= 3

    companion object {
        const val ANGLE_FRONTAL = "frontal"
        const val ANGLE_THREE_QUARTER = "three_quarter"
        const val ANGLE_PROFILE = "profile"

        val ALL_ANGLES = listOf(ANGLE_FRONTAL, ANGLE_THREE_QUARTER, ANGLE_PROFILE)

        fun initial(): StudentFaceSamplesStatus = StudentFaceSamplesStatus(
            enabled = true,
            group_allowed = true,
            samples = listOf(
                StudentFaceSampleSlot(angle = ANGLE_FRONTAL, uploaded = false),
                StudentFaceSampleSlot(angle = ANGLE_THREE_QUARTER, uploaded = false),
                StudentFaceSampleSlot(angle = ANGLE_PROFILE, uploaded = false)
            )
        )

        fun titleForAngle(angle: String): String = when (angle) {
            ANGLE_FRONTAL -> "Анфас"
            ANGLE_THREE_QUARTER -> "Поворот ¾"
            ANGLE_PROFILE -> "Профиль"
            else -> "Снимок лица"
        }

        fun instructionForAngle(angle: String): String = when (angle) {
            ANGLE_FRONTAL -> "Смотрите прямо в камеру."
            ANGLE_THREE_QUARTER -> "Поверните голову примерно на 45°."
            ANGLE_PROFILE -> "Поверните лицо боком к камере."
            else -> "Расположите лицо в рамке."
        }

        fun cameraGuidanceForAngle(angle: String): String = when (angle) {
            ANGLE_FRONTAL -> "Смотрите прямо в объектив камеры, держите лицо ровно по центру овальной рамки."
            ANGLE_THREE_QUARTER -> "Поверните голову на 45° в сторону, сохраняя взгляд в направлении лица."
            ANGLE_PROFILE -> "Поверните лицо строго боком (90°). Если неудобно снимать себя, переключите камеру и попросите друга снять вас!"
            else -> "Совместите лицо с пунктирной рамкой на экране."
        }
    }
}
