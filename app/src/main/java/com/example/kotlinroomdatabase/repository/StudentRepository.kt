package com.example.kotlinroomdatabase.repository

import com.example.kotlinroomdatabase.data.ZmqSockets
import android.util.Log
import com.example.kotlinroomdatabase.data.StudentDao
import com.example.kotlinroomdatabase.model.Student
import kotlinx.coroutines.flow.Flow
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
    suspend fun insertStudent(student: Student) {
        studentDao.insertStudent(student)
        if (zeroMQSender != null) {
            syncStudentToServer(student, "insert")
        }
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
            Log.d("ZMQ_DEBUG", "Starting syncAllStudents...")

            val students = studentDao.getAllStudents()
            var studentList: List<Student> = emptyList()

            students.collect { list ->
                studentList = list
            }

            Log.d("ZMQ_DEBUG", "Found ${studentList.size} students to sync")

            val jsonArray = JSONArray()
            studentList.forEach { student ->
                val jsonObject = JSONObject().apply {
                    put("operation", "sync_all")
                    put("data", JSONObject().apply {
                        put("id", student.id)
                        put("studentName", student.studentName)
                        put("studentGroup", student.studentGroup)
                        put("studentNFC", student.studentNFC)
                        put("attendance", student.attendance)
                    })
                }
                jsonArray.put(jsonObject)
            }

            Log.d("ZMQ_DEBUG", "Sending JSON: ${jsonArray.toString()}")
            val response = zeroMQSender?.sendData(jsonArray.toString()) ?: return SyncResult.Error("ZeroMQ not initialized")

            Log.d("ZMQ_DEBUG", "Server response: $response")

            if (response.contains("\"status\":\"success\"")) {
                SyncResult.Success(studentList.size, "Synced ${studentList.size} students")
            } else {
                SyncResult.Error("Sync failed: $response")
            }

        } catch (e: Exception) {
            Log.e("ZMQ_DEBUG", "Sync error: ${e.message}", e)
            SyncResult.Error("Sync error: ${e.message}")
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

    sealed class SyncResult {
        data class Success(val count: Int, val message: String) : SyncResult()
        data class Error(val message: String) : SyncResult()
    }

    companion object
}