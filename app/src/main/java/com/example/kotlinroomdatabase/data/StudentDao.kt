package com.example.kotlinroomdatabase.data

import androidx.room.*
import com.example.kotlinroomdatabase.model.Student
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.InternalSerializationApi

@Dao
interface StudentDao {
    @OptIn(InternalSerializationApi::class)
    @Query("SELECT * FROM student ORDER BY studentName")
    fun getAllStudents(): Flow<List<Student>>

    @OptIn(InternalSerializationApi::class)
    @Query("SELECT * FROM student WHERE studentGroup = :group ORDER BY studentName")
    fun getStudentsByGroup(group: String): Flow<List<Student>>

    @Query("SELECT DISTINCT studentGroup FROM student ORDER BY studentGroup")
    fun getAllGroups(): Flow<List<String>>

    @OptIn(InternalSerializationApi::class)
    @Query("SELECT * FROM student WHERE studentNFC = :nfcId")
    suspend fun getStudentByNfc(nfcId: String): Student?

    @OptIn(InternalSerializationApi::class)
    @Query("SELECT * FROM student WHERE id = :id")
    suspend fun getStudentById(id: Int): Student?

    @OptIn(InternalSerializationApi::class)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStudent(student: Student)

    @OptIn(InternalSerializationApi::class)
    @Update
    suspend fun updateStudent(student: Student)

    @OptIn(InternalSerializationApi::class)
    @Delete
    suspend fun deleteStudent(student: Student)

    @Query("DELETE FROM student")
    suspend fun deleteAllStudents()

    @Query("UPDATE student SET attendance = :attendance WHERE id = :id")
    suspend fun updateAttendance(id: Int, attendance: Boolean)
}