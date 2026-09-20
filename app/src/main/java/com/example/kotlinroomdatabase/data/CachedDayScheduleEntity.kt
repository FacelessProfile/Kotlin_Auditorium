package com.example.kotlinroomdatabase.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cached_day_schedule")
data class CachedDayScheduleEntity(
    @PrimaryKey
    val date: String,
    val weekday: String,
    val dayIdx: Int,
    val weekType: Int,
    val teacherId: Int?,
    val lessonsJson: String,
    val updatedAt: Long = System.currentTimeMillis()
)
