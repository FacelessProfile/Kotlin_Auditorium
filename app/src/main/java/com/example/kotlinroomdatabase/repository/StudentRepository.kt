package com.example.kotlinroomdatabase.repository

import com.example.kotlinroomdatabase.data.ZmqSockets
import android.util.Log
import com.example.kotlinroomdatabase.data.Crypto
import com.example.kotlinroomdatabase.data.StudentDao
import com.example.kotlinroomdatabase.model.Student
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.InternalSerializationApi
import org.json.JSONArray
import org.json.JSONObject

class StudentRepository(
    private val studentDao: StudentDao,
    private val zeroMQSender: ZmqSockets? = null
) {

    @OptIn(InternalSerializationApi::class)
    fun getAllStudents(): Flow<List<Student>> = studentDao.getAllStudents()

    @OptIn(InternalSerializationApi::class)
    fun getStudentsByGroup(group: String): Flow<List<Student>> = studentDao.getStudentsByGroup(group)

    fun getAllGroups(): Flow<List<String>> = studentDao.getAllGroups()

    @OptIn(InternalSerializationApi::class)
    suspend fun getStudentByNfc(nfcId: String): Student? = studentDao.getStudentByNfc(nfcId)

    @OptIn(InternalSerializationApi::class)
    suspend fun getStudentById(id: Int): Student? = studentDao.getStudentById(id)

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
    suspend fun updateAttendance(id: Int, attendance: Boolean) {
        studentDao.updateAttendance(id, attendance)
        if (zeroMQSender != null) {
            val student = studentDao.getStudentById(id)
            student?.let { syncStudentToServer(it, "attendance") }
        }
    }

    @OptIn(InternalSerializationApi::class)
    suspend fun getStudentByNameAndGroup(name: String, group: String): Student? =
        studentDao.getStudentByNameAndGroup(name, group)

    @OptIn(InternalSerializationApi::class)
    suspend fun syncAllStudents(): SyncResult {
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
    suspend fun login(loginName: String, passwordRaw: String): LoginResult {
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
    suspend fun register(name: String, group: String, passwordRaw: String): LoginResult {
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


    sealed class LoginResult {
        @OptIn(InternalSerializationApi::class)
        data class Success(val student: Student) : LoginResult()
        data class Error(val message: String) : LoginResult()
    }

    sealed class SyncResult {
        data class Success(val count: Int, val message: String) : SyncResult()
        data class Error(val message: String) : SyncResult()
    }
}