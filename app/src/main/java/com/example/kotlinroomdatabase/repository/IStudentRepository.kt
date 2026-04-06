package com.example.kotlinroomdatabase.repository

import com.example.kotlinroomdatabase.model.Student
import kotlinx.serialization.InternalSerializationApi

sealed class LoginResult {
    data class Success @OptIn(InternalSerializationApi::class) constructor(val student: Student) : LoginResult()
    data class Error(val message: String) : LoginResult()
}

sealed class SyncResult {
    data class Success(val count: Int, val message: String) : SyncResult()
    data class Error(val message: String) : SyncResult()
}

sealed class FinishLessonResult {
    data class Success(val reportName: String, val data: List<Map<String, Any>>) : FinishLessonResult()
    data class Error(val message: String) : FinishLessonResult()
}

sealed class AttendanceResult {
    data class Success @OptIn(InternalSerializationApi::class) constructor(val student: Student) : AttendanceResult()
    data class Error(val message: String) : AttendanceResult()
}

interface IStudentRepository {
    suspend fun login(loginName: String, passwordRaw: String): LoginResult
    suspend fun register(name: String, group: String, passwordRaw: String): LoginResult
    suspend fun clearLocalRoomData()
    suspend fun syncAllStudents(): SyncResult

    @OptIn(InternalSerializationApi::class)
    suspend fun getStudentByNfc(nfcId: String): Student?
    @OptIn(InternalSerializationApi::class)
    suspend fun getStudentById(id: Int): Student?
    suspend fun updateAttendance(id: Int, status: Boolean)
    @OptIn(InternalSerializationApi::class)
    fun getAllStudents(): kotlinx.coroutines.flow.Flow<List<Student>>
    suspend fun finishLesson(lessonId: Int): FinishLessonResult
    suspend fun markAttendanceInLesson(lessonId: Int, nfcTag: String): AttendanceResult
}