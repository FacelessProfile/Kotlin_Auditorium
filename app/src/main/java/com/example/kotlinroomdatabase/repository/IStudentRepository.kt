package com.example.kotlinroomdatabase.repository

import com.example.kotlinroomdatabase.model.Student
import com.example.kotlinroomdatabase.model.Lesson
import com.example.kotlinroomdatabase.model.TeacherSubject
import com.example.kotlinroomdatabase.model.StudentAttendanceStats
import com.example.kotlinroomdatabase.model.AttendanceHistoryResponse
import com.example.kotlinroomdatabase.model.HistoryItem
import kotlinx.serialization.InternalSerializationApi
import kotlinx.coroutines.flow.Flow

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

sealed class AttendanceLinkResult {
    data class Success(val url: String) : AttendanceLinkResult()
    data class Error(val message: String) : AttendanceLinkResult()
}

sealed class AvatarResult {
    data class Success(val avatarUrl: String) : AvatarResult()
    data class Error(val message: String) : AvatarResult()
}

interface IStudentRepository {
    suspend fun login(loginName: String, passwordRaw: String): LoginResult
    suspend fun register(name: String, group: String, passwordRaw: String): LoginResult
    suspend fun clearLocalRoomData()
    suspend fun syncAllStudents(): SyncResult
    suspend fun syncLessonAttendance(subjectId: Int, groupIds: List<Int>): SyncResult
    suspend fun getAllUniqueGroups(): List<String>
    suspend fun createLesson(subject: String, teacherId: Int, groups: List<String>, lat: Double = 0.0, lon: Double = 0.0): Int?

    @OptIn(InternalSerializationApi::class)
    suspend fun getStudentByNfc(nfcId: String): Student?
    @OptIn(InternalSerializationApi::class)
    suspend fun getStudentById(id: Int): Student?
    suspend fun updateAttendance(id: Int, status: Boolean)
    @OptIn(InternalSerializationApi::class)
    fun getAllStudents(): Flow<List<Student>>
    fun getAllLessons(): Flow<List<Lesson>>

    @OptIn(InternalSerializationApi::class)
    suspend fun insertStudent(student: Student): Long
    @OptIn(InternalSerializationApi::class)
    suspend fun updateStudent(student: Student)
    @OptIn(InternalSerializationApi::class)
    suspend fun deleteStudent(student: Student)
    suspend fun deleteAllStudents()

    suspend fun finishLesson(lessonId: Int): FinishLessonResult
    suspend fun markAttendanceInLesson(lessonId: Int, nfcTag: String): AttendanceResult
    suspend fun markAttendanceViaQr(lessonId: Int, deviceId: String, lat: Double, lon: Double, inviteToken: String? = null, totpCode: String? = null): AttendanceResult
    suspend fun getAttendanceLink(lessonId: Int): AttendanceLinkResult
    suspend fun uploadAvatar(imagePath: String): AvatarResult
    suspend fun getTeacherSubjects(): List<TeacherSubject>
    suspend fun getGroupAttendance(groupId: Int, subjectId: Int): List<StudentAttendanceStats>
    suspend fun getStudentHistory(year: Int): AttendanceHistoryResponse
    suspend fun getDetailedStudentHistory(studentId: Int, subjectId: Int): List<HistoryItem>
}