package com.example.kotlinroomdatabase.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "offline_grade_actions")
data class OfflineGradeAction(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val studentId: Int,
    val itemId: Int,
    val score: Int,
    val comment: String?,
    val timestamp: Long = System.currentTimeMillis(),
    val isSynced: Boolean = false
)
