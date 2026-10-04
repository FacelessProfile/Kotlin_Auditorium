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

                val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        try {
                            db.execSQL("ALTER TABLE `student` ADD COLUMN `isFraud` INTEGER NOT NULL DEFAULT 0")
                        } catch (_: Exception) {}
                        try {
                            db.execSQL("ALTER TABLE `student` ADD COLUMN `totalCheatAttempts` INTEGER NOT NULL DEFAULT 0")
                        } catch (_: Exception) {}
                    }
                }

                val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS `lessons_table` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `subject` TEXT NOT NULL, `date` INTEGER NOT NULL, `groups` TEXT NOT NULL)")
                    }
                }

                val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS `offline_grade_actions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `studentId` INTEGER NOT NULL, `itemId` INTEGER NOT NULL, `score` INTEGER NOT NULL, `comment` TEXT, `timestamp` INTEGER NOT NULL DEFAULT 0, `isSynced` INTEGER NOT NULL DEFAULT 0, `authorId` TEXT NOT NULL DEFAULT '', `serverOrigin` TEXT NOT NULL DEFAULT '')")
                    }
                }

                val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS `offline_grade_actions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `studentId` INTEGER NOT NULL, `itemId` INTEGER NOT NULL, `score` INTEGER NOT NULL, `comment` TEXT, `timestamp` INTEGER NOT NULL DEFAULT 0, `isSynced` INTEGER NOT NULL DEFAULT 0, `authorId` TEXT NOT NULL DEFAULT '', `serverOrigin` TEXT NOT NULL DEFAULT '')")
                        try {
                            db.execSQL("ALTER TABLE `offline_grade_actions` ADD COLUMN `authorId` TEXT NOT NULL DEFAULT ''")
                        } catch (_: Exception) {}
                        try {
                            db.execSQL("ALTER TABLE `offline_grade_actions` ADD COLUMN `serverOrigin` TEXT NOT NULL DEFAULT ''")
                        } catch (_: Exception) {}
                        try {
                            db.execSQL("ALTER TABLE `offline_grade_actions` ADD COLUMN `timestamp` INTEGER NOT NULL DEFAULT 0")
                        } catch (_: Exception) {}
                        try {
                            db.execSQL("ALTER TABLE `offline_grade_actions` ADD COLUMN `isSynced` INTEGER NOT NULL DEFAULT 0")
                        } catch (_: Exception) {}
                    }
                }

                val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        // Drop invalid table structure if created during previous development draft
                        db.execSQL("DROP TABLE IF EXISTS `cached_day_schedule`")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `cached_day_schedule` (`date` TEXT NOT NULL, `weekday` TEXT NOT NULL, `dayIdx` INTEGER NOT NULL, `weekType` INTEGER NOT NULL, `teacherId` INTEGER, `lessonsJson` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`date`))")
                    }
                }

                fun getInstance(context: Context): StudentDatabase {
                    return INSTANCE ?: synchronized(this) {
                        val instance = Room.databaseBuilder(
                            context.applicationContext,
                            StudentDatabase::class.java,
                            "student_database"
                        )
                            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                            .fallbackToDestructiveMigration(true)
                            .fallbackToDestructiveMigrationOnDowngrade(true)
                            .build()
                        INSTANCE = instance
                        instance
                    }
                }
    }
}