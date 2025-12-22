package com.example.kotlinroomdatabase.repository

import com.example.kotlinroomdatabase.data.ZmqSockets
import android.util.Log
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
        val newId = studentDao.insertStudent(student) // Получаем ID от Dao

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
        val students = getAllStudents()
        var studentList: List<Student> = emptyList()

        students.collect { list ->
            studentList = list
        }
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
            student?.let {
                syncStudentToServer(it, "attendance")
            }
        }
    }

    @OptIn(InternalSerializationApi::class)
    suspend fun migrateFromJson(jsonStudents: List<Student>) {
        jsonStudents.forEach { student ->
            insertStudent(student)
        }
    }

    @OptIn(InternalSerializationApi::class)
    suspend fun getStudentByNameAndGroup(name: String, group: String): Student? =
        studentDao.getStudentByNameAndGroup(name, group)

    @OptIn(InternalSerializationApi::class)
    suspend fun syncAllStudents(): SyncResult {
        return try {
            val localStudents = studentDao.getAllStudents().first()
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
                            attendance = s.optBoolean("attendance", false)
                        )
                        studentDao.insertStudent(remoteStudent)
                    }
                }
                SyncResult.Success(remoteStudentsArray?.length() ?: 0, "Данные обновлены")
            } else {
                SyncResult.Error("Сервер вернул ошибку")
            }
        } catch (e: Exception) {
            Log.e("ZMQ_DEBUG", "Sync error: ${e.message}")
            SyncResult.Error(e.message ?: "Unknown error")
        }
    }

    @OptIn(InternalSerializationApi::class)
    private suspend fun syncStudentToServer(student: Student, operation: String) {
        try {
            Log.d("ZMQ_DEBUG", "Syncing student $operation: ${student.studentName}")

            if (zeroMQSender == null) {
                Log.w("ZMQ_DEBUG", "ZeroMQ sender is null, skipping sync")
                return
            }

            val jsonObject = JSONObject().apply {
                put("operation", operation)
                put("data", JSONObject().apply {
                    put("id", student.id)
                    put("studentName", student.studentName)
                    put("studentGroup", student.studentGroup)
                    put("studentNFC", student.studentNFC)
                    put("attendance", student.attendance)
                })
            }

            val response = zeroMQSender.sendData(jsonObject.toString())
            Log.d("ZMQ_DEBUG", "Student $operation sync response: $response")

        } catch (e: Exception) {
            Log.e("ZMQ_DEBUG", "Failed to sync student: ${e.message}", e)
        }
    }

    suspend fun testConnection(): String {
        return try {
            zeroMQSender?.testConnection() ?: "ZeroMQ sender is not available"
        } catch (e: Exception) {
            "Connection test error: ${e.message}"
        }
    }

    @OptIn(InternalSerializationApi::class)
    suspend fun syncStudentsByGroup(group: String): SyncResult {
        return try {
            val students = studentDao.getStudentsByGroup(group)
            var studentList: List<Student> = emptyList()

            students.collect { list ->
                studentList = list
            }

            if (studentList.isEmpty()) {
                return SyncResult.Success(0, "No data to sync for group $group")
            }

            val jsonArray = JSONArray()
            studentList.forEach { student ->
                val jsonObject = JSONObject().apply {
                    put("operation", "sync_group")
                    put("data", JSONObject().apply {
                        put("id", student.id)
                        put("studentName", student.studentName)
                        put("studentGroup", student.studentGroup)
                        put("studentNFC", student.studentNFC)
                        put("attendance", student.attendance)
                        put("timestamp", System.currentTimeMillis())
                    })
                }
                jsonArray.put(jsonObject)
            }

            val response = zeroMQSender?.sendData(jsonArray.toString())
                ?: return SyncResult.Error("ZeroMQ sender not initialized")

            if (response.contains("\"status\":\"success\"")) {
                SyncResult.Success(studentList.size, "Synced ${studentList.size} students from group $group")
            } else {
                SyncResult.Error("Sync failed: $response")
            }

        } catch (e: Exception) {
            SyncResult.Error("Sync error for group $group: ${e.message}")
        }
    }

    @OptIn(InternalSerializationApi::class)
    suspend fun searchStudentOnServer(name: String, group: String): Student? {
        return try {
            if (zeroMQSender == null) {
                Log.e("StudentRepository", "ZeroMQ sender is null")
                return null
            }

            val searchData = JSONObject().apply {
                put("operation", "search")
                put("data", JSONObject().apply {
                    put("studentName", name)
                    put("studentGroup", group)
                })
            }

            Log.d("StudentRepository", "Searching on server: $name, $group")
            val response = zeroMQSender.sendData(searchData.toString())
            Log.d("StudentRepository", "Server response: $response")

            val json = JSONObject(response)
            if (json.optString("status") != "success") {
                Log.e("StudentRepository", "Server returned error status")
                return null
            }
            val data = json.optJSONObject("data")
            if (data == null) {
                Log.e("StudentRepository", "No data field in response")
                return null
            }

            Log.d("StudentRepository", "Parsed data: $data")

            val student = Student(
                id = 0,
                studentName = data.getString("studentName"),
                studentGroup = data.getString("studentGroup"),
                studentNFC = data.optString("studentNFC", ""),
                attendance = data.optBoolean("attendance", false)
            )

            Log.d("StudentRepository", "Created student: ${student.studentName}")
            return student

        } catch (e: Exception) {
            Log.e("StudentRepository", "Search on server failed", e)
            null
        }
    }

    sealed class SyncResult {
        data class Success(val count: Int, val message: String) : SyncResult()
        data class Error(val message: String) : SyncResult()
    }

    companion object
}