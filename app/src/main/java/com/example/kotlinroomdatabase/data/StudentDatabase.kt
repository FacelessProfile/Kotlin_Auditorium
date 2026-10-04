package com.example.kotlinroomdatabase.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.kotlinroomdatabase.data.StudentDao
import com.example.kotlinroomdatabase.model.Student
import com.example.kotlinroomdatabase.model.Lesson
import com.example.kotlinroomdatabase.model.OfflineGradeAction
import kotlinx.serialization.InternalSerializationApi

@OptIn(InternalSerializationApi::class)
@Database(entities = [Student::class, Lesson::class, OfflineGradeAction::class, CachedDayScheduleEntity::class], version = 6, exportSchema = false)
abstract class StudentDatabase : RoomDatabase() {
            abstract fun studentDao(): StudentDao

            companion object {
                @Volatile
                private var INSTANCE: StudentDatabase? = null

                private val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS `offline_grade_actions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `studentId` INTEGER NOT NULL, `itemId` INTEGER NOT NULL, `score` INTEGER NOT NULL, `comment` TEXT, `authorId` TEXT NOT NULL DEFAULT '0', `serverOrigin` TEXT NOT NULL DEFAULT '', `timestamp` INTEGER NOT NULL DEFAULT 0, `isSynced` INTEGER NOT NULL DEFAULT 0)")
                    }
                }

                private val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS `cached_day_schedule` (`dateIso` TEXT NOT NULL, `dayName` TEXT NOT NULL, `lessonsJson` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`dateIso`))")
                    }
                }

                fun getInstance(context: Context): StudentDatabase {
                    return INSTANCE ?: synchronized(this) {
                        val instance = Room.databaseBuilder(
                            context.applicationContext,
                            StudentDatabase::class.java,
                            "student_database"
                        )
                            .addMigrations(MIGRATION_4_5, MIGRATION_5_6)
                            .fallbackToDestructiveMigrationOnDowngrade()
                            .build()
                        INSTANCE = instance
                        instance
                    }
                }
    }
}