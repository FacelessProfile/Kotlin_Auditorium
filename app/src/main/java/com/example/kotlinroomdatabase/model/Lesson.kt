package com.example.kotlinroomdatabase.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "lessons_table")
data class Lesson(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val subject: String,
    val date: Long,
    val groups: String
)