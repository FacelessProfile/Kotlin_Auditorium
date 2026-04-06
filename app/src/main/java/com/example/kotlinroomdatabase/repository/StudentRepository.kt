package com.example.kotlinroomdatabase.repository

import com.example.kotlinroomdatabase.data.ZmqSockets
import android.util.Log
import com.example.kotlinroomdatabase.data.Crypto
import com.example.kotlinroomdatabase.data.StudentDao
import com.example.kotlinroomdatabase.model.Lesson
import com.example.kotlinroomdatabase.model.Student
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.InternalSerializationApi
import org.json.JSONArray
import org.json.JSONObject

class StudentRepository(
    private val studentDao: StudentDao,
    private val zeroMQSender: ZmqSockets? = null
) : IStudentRepository {
    @OptIn(InternalSerializationApi::class)
    override fun getAllStudents(): Flow<List<Student>> {
        return studentDao.getAllStudents()
    }

    @OptIn(InternalSerializationApi::class)
    override suspend fun getStudentByNfc(nfcId: String): Student? = studentDao.getStudentByNfc(nfcId)

    @OptIn(InternalSerializationApi::class)
    override suspend fun getStudentById(id: Int): Student? = studentDao.getStudentById(id)

    @OptIn(InternalSerializationApi::class)
    override suspend fun updateAttendance(id: Int, status: Boolean) {
        studentDao.updateAttendance(id, status)
        if (zeroMQSender != null) {
            val student = studentDao.getStudentById(id)
            student?.let { syncStudentToServer(it, "attendance") }
        }
    }

    override suspend fun clearLocalRoomData() {
        studentDao.deleteAllStudents()
    }

    @OptIn(InternalSerializationApi::class)
    override suspend fun syncAllStudents(): SyncResult {
        return try {
            val jsonRequest = JSONObject().apply {
                put("operation", "sync_all")
                put("data", JSONObject())
            }

            val response = zeroMQSender?.sendData(jsonRequest.toString())
                ?: return SyncResult.Error("ZeroMQ not initialized")

            val jsonResponse = JSONObject(response)

            if (jsonResponse.optString("status") == "success") {
                val remoteStudentsArray = jsonResponse.optJSONArray("students")
                if (remoteStudentsArray != null) {
                    studentDao.deleteAllStudents()
                    for (i in 0 until remoteStudentsArray.length()) {
                        val s = remoteStudentsArray.getJSONObject(i)
                        val remoteStudent = Student(
                            id = s.getInt("id"),
                            studentName = s.getString("studentName"),
                            studentGroup = s.getString("studentGroup"),
                            studentNFC = s.optString("studentNFC", ""),
                            attendance = s.optBoolean("attendance", false),
                            role = s.optString("role", "student")
                        )
                        studentDao.insertStudent(remoteStudent)
                    }
                }
                SyncResult.Success(remoteStudentsArray?.length() ?: 0, "Данные обновлены")
            } else {
                SyncResult.Error("Сервер вернул ошибку")
            }
        } catch (e: Exception) {
            SyncResult.Error(e.message ?: "Unknown error")
        }
    }

    @OptIn(InternalSerializationApi::class)
    override suspend fun login(loginName: String, passwordRaw: String): LoginResult {
        return try {
            val encryptedPassword = Crypto.encryptPassword(passwordRaw)
            if (encryptedPassword.isEmpty()) return LoginResult.Error("Ошибка шифрования")

            val jsonRequest = JSONObject().apply {
                put("operation", "login")
                put("data", JSONObject().apply {
                    put("login", loginName)
                    put("password", encryptedPassword)
                })
            }

            val response = zeroMQSender?.sendData(jsonRequest.toString()) ?: return LoginResult.Error("Сервер недоступен")
            val jsonResponse = JSONObject(response)

            if (jsonResponse.optString("status") == "success") {
                parseAndSaveStudent(jsonResponse)
            } else {
                LoginResult.Error(jsonResponse.optString("message", "Неверный логин или пароль"))
            }
        } catch (e: Exception) {
            LoginResult.Error("Ошибка связи: ${e.message}")
        }
    }

    @OptIn(InternalSerializationApi::class)
    override suspend fun register(name: String, group: String, passwordRaw: String): LoginResult {
        return try {
            val encryptedPassword = Crypto.encryptPassword(passwordRaw)
            val jsonRequest = JSONObject().apply {
                put("operation", "register")
                put("data", JSONObject().apply {
                    put("login", name)
                    put("group", group)
                    put("password", encryptedPassword)
                })
            }

            val response = zeroMQSender?.sendData(jsonRequest.toString()) ?: return LoginResult.Error("Сервер недоступен")
            val jsonResponse = JSONObject(response)

            if (jsonResponse.optString("status") == "success") {
                parseAndSaveStudent(jsonResponse)
            } else {
                LoginResult.Error(jsonResponse.optString("message", "Ошибка регистрации"))
            }
        } catch (e: Exception) {
            LoginResult.Error("Ошибка связи: ${e.message}")
        }
    }

    @OptIn(InternalSerializationApi::class)
    override suspend fun markAttendanceInLesson(lessonId: Int, nfcTag: String): AttendanceResult {
        return try {
            val jsonRequest = JSONObject().apply {
                put("operation", "mark_attendance")
                put("data", JSONObject().apply {
                    put("lesson_id", lessonId)
                    put("student_nfc", nfcTag)
                })
            }
            val response = zeroMQSender?.sendData(jsonRequest.toString()) ?: return AttendanceResult.Error("Сервер недоступен")
            val jsonResponse = JSONObject(response)

            if (jsonResponse.optString("status") == "success") {
                val s = jsonResponse.getJSONObject("student")
                val student = Student(
                    id = s.getInt("id"),
                    studentName = s.getString("studentName"),
                    studentGroup = s.getString("studentGroup"),
                    studentNFC = s.optString("studentNFC", ""),
                    attendance = true,
                    role = "student"
                )

                studentDao.insertStudent(student)
                AttendanceResult.Success(student)
            } else {
                AttendanceResult.Error(jsonResponse.optString("message", "Ошибка отметки"))
            }
        } catch (e: Exception) {
            AttendanceResult.Error("Ошибка связи")
        }
    }

    override suspend fun finishLesson(lessonId: Int): FinishLessonResult {
        return try {
            val jsonRequest = JSONObject().apply {
                put("operation", "finish_lesson")
                put("data", JSONObject().apply {
                    put("lesson_id", lessonId)
                })
            }

            val response = zeroMQSender?.sendData(jsonRequest.toString())
                ?: return FinishLessonResult.Error("Сервер недоступен")

            val jsonResponse = JSONObject(response)

            if (jsonResponse.optString("status") == "success") {
                val reportName = jsonResponse.optString("report_name")
                val studentsData = jsonResponse.optJSONArray("data")
                val resultList = mutableListOf<Map<String, Any>>()

                if (studentsData != null) {
                    for (i in 0 until studentsData.length()) {
                        val item = studentsData.getJSONObject(i)
                        resultList.add(mapOf(
                            "group" to item.getString("group"),
                            "name" to item.getString("name"),
                            "present" to item.getBoolean("present")
                        ))
                    }
                }
                FinishLessonResult.Success(reportName, resultList)
            } else {
                FinishLessonResult.Error(jsonResponse.optString("message", "Ошибка завершения"))
            }
        } catch (e: Exception) {
            FinishLessonResult.Error("Ошибка связи: ${e.message}")
        }
    }

    @OptIn(InternalSerializationApi::class)
    fun getStudentsByGroup(group: String): Flow<List<Student>> = studentDao.getStudentsByGroup(group)

    fun getAllGroups(): Flow<List<String>> = studentDao.getAllGroups()

    @OptIn(InternalSerializationApi::class)
    suspend fun insertStudent(student: Student): Long {
        val newId = studentDao.insertStudent(student)
        if (zeroMQSender != null) {
            syncStudentToServer(student.copy(id = newId.toInt()), "insert")
        }
        return newId
    }

    @OptIn(InternalSerializationApi::class)
    suspend fun updateStudent(student: Student) {
        studentDao.updateStudent(student)
        if (zeroMQSender != null) {
            syncStudentToServer(student, "update")
        }
    }

    @OptIn(InternalSerializationApi::class)
    suspend fun deleteStudent(student: Student) {
        if (zeroMQSender != null) {
            syncStudentToServer(student, "delete")
        }
        studentDao.deleteStudent(student)
    }

    @OptIn(InternalSerializationApi::class)
    suspend fun deleteAllStudents() {
        val studentList = studentDao.getAllStudents().first()
        if (zeroMQSender != null && studentList.isNotEmpty()) {
            for (student in studentList) {
                syncStudentToServer(student, "delete")
            }
        }
        studentDao.deleteAllStudents()
    }

    @OptIn(InternalSerializationApi::class)
    suspend fun getStudentByNameAndGroup(name: String, group: String): Student? =
        studentDao.getStudentByNameAndGroup(name, group)

    @OptIn(InternalSerializationApi::class)
    private suspend fun syncStudentToServer(student: Student, operation: String) {
        try {
            if (zeroMQSender == null) return

            val jsonObject = JSONObject().apply {
                put("operation", operation)
                put("data", JSONObject().apply {
                    put("id", student.id)
                    put("studentName", student.studentName)
                    put("studentGroup", student.studentGroup)
                    put("studentNFC", student.studentNFC)
                    put("attendance", student.attendance)
                    put("role", student.role)
                })
            }
            zeroMQSender.sendData(jsonObject.toString())
        } catch (e: Exception) {
            Log.e("ZMQ_DEBUG", "Failed to sync student", e)
        }
    }

    @OptIn(InternalSerializationApi::class)
    suspend fun searchStudentOnServer(name: String, group: String): Student? {
        return try {
            val searchData = JSONObject().apply {
                put("operation", "search")
                put("data", JSONObject().apply {
                    put("studentName", name)
                    put("studentGroup", group)
                })
            }

            val response = zeroMQSender?.sendData(searchData.toString()) ?: return null
            val json = JSONObject(response)
            val data = json.optJSONObject("data") ?: return null
            return Student(
                id = 0,
                studentName = data.getString("studentName"),
                studentGroup = data.getString("studentGroup"),
                studentNFC = data.optString("studentNFC", ""),
                attendance = data.optBoolean("attendance", false),
                role = data.optString("role", "student")
            )
        } catch (e: Exception) {
            null
        }
    }

    @OptIn(InternalSerializationApi::class)
    private suspend fun parseAndSaveStudent(jsonResponse: JSONObject): LoginResult {
        val role = jsonResponse.getString("role")
        val data = jsonResponse.getJSONObject("student")

        val student = Student(
            id = data.getInt("id"),
            studentName = data.getString("studentName"),
            studentGroup = data.getString("studentGroup"),
            studentNFC = data.optString("studentNFC", ""),
            attendance = data.optBoolean("attendance", false),
            role = role
        )

        studentDao.insertStudent(student)
        return LoginResult.Success(student)
    }

    suspend fun testConnection(): String {
        return try {
            zeroMQSender?.testConnection() ?: "ZeroMQ sender is not available"
        } catch (e: Exception) {
            "Connection test error: ${e.message}"
        }
    }

    @OptIn(InternalSerializationApi::class)
    suspend fun getAllUniqueGroups(): List<String> {
        return try {
            studentDao.getAllGroups().first()
        } catch (e: Exception) {
            emptyList()
        }
    }

    @OptIn(InternalSerializationApi::class)
    suspend fun createLesson(subject: String, teacherId: Int, groups: List<String>): Int? {
        return try {
            val newLesson = Lesson(
                subject = subject,
                date = System.currentTimeMillis(),
                groups = groups.joinToString(", ")
            )
            val localId = studentDao.insertLesson(newLesson).toInt()
            val jsonRequest = JSONObject().apply {
                put("operation", "create_lesson")
                put("data", JSONObject().apply {
                    put("subject", subject)
                    put("teacher_id", teacherId)
                    put("groups", JSONArray(groups))
                    put("local_id", localId)
                })
            }

            val response = zeroMQSender?.sendData(jsonRequest.toString())

            if (response != null) {
                val jsonResponse = JSONObject(response)
                if (jsonResponse.optString("status") == "success") {
                    return jsonResponse.optInt("lesson_id", localId)
                }
            }
            localId
        } catch (e: Exception) {
            null
        }
    }
}