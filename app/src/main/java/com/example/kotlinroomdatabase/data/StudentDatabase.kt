package com.example.kotlinroomdatabase.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.kotlinroomdatabase.data.StudentDao
import com.example.kotlinroomdatabase.model.Student
import kotlinx.serialization.InternalSerializationApi

@OptIn(InternalSerializationApi::class)
@Database(entities = [Student::class], version = 2, exportSchema = false)
abstract class StudentDatabase : RoomDatabase() {
            abstract fun studentDao(): StudentDao

            companion object {
                @Volatile
                private var INSTANCE: StudentDatabase? = null

                fun getInstance(context: Context): StudentDatabase {
                    return INSTANCE ?: synchronized(this) {
                        val instance = Room.databaseBuilder(
                            context.applicationContext,
                            StudentDatabase::class.java,
                            "student_database"
                        ).build()
                        INSTANCE = instance
                        instance
                    }
                }
    }
}