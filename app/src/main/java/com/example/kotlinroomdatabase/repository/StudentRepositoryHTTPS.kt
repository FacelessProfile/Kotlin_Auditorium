package com.example.kotlinroomdatabase.repository

import android.content.Context
import android.util.Log
import com.example.kotlinroomdatabase.data.StudentDao
import com.example.kotlinroomdatabase.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class StudentRepositoryHTTPS(
    context: Context,
    private val studentDao: StudentDao
) : IStudentRepository {

    private val sharedPrefs = context.applicationContext.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)

    private val jsonSerializer = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val logger = HttpLoggingInterceptor { message ->
        Log.d("HTTP_LOG", message)
    }.apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val client = OkHttpClient.Builder()
        .addInterceptor(logger)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    private val BASE_URL = "http://109.172.114.128:9000"

    fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }


    @OptIn(InternalSerializationApi::class)
    override suspend fun login(loginName: String, passwordRaw: String): LoginResult = withContext(Dispatchers.IO) {
        try {
            val jsonRequest = JSONObject().apply {
                put("login", loginName)
                put("password", passwordRaw)
            }

            val body = jsonRequest.toString().toRequestBody(JSON_TYPE)
            val request = Request.Builder()
                .url("$BASE_URL/login")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            val responseStr = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                val errorMsg = JSONObject(responseStr).optString("error", "Server Error ${response.code}")
                return@withContext LoginResult.Error(errorMsg)
            }

            val jsonResponse = JSONObject(responseStr)
            if (jsonResponse.optBoolean("ok")) {
                val result = jsonResponse.getJSONObject("result")
                val token = result.optString("token")
                saveToken(token)

                val userIdStr = result.optString("user_ID", result.optString("user_id", "0"))
                val student = Student(
                    id = userIdStr.hashCode(),
                    studentName = result.optString("login", "Unknown"),
                    studentGroup = "Unknown",
                    studentNFC = result.optString("nfc_tag", ""),
                    attendance = false,
                    role = result.optString("role", "student")
                )

                studentDao.insertStudent(student)
                LoginResult.Success(student)
            } else {
                LoginResult.Error(jsonResponse.optString("error", "Invalid Credentials"))
            }
        } catch (e: Exception) {
            Log.e("HTTP_REPO", "Login Exception", e)
            LoginResult.Error("Network Error: ${e.message ?: "Unknown"}")
        }
    }

    @OptIn(InternalSerializationApi::class)
    override suspend fun register(name: String, group: String, passwordRaw: String): LoginResult = withContext(Dispatchers.IO) {
        try {
            val jsonRequest = JSONObject().apply {
                put("login", name)
                put("password", passwordRaw)
                put("role", "student")
            }

            val body = jsonRequest.toString().toRequestBody(JSON_TYPE)
            val request = Request.Builder()
                .url("$BASE_URL/register")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            val responseStr = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                val errorMsg = JSONObject(responseStr).optString("error", "Registration Error ${response.code}")
                return@withContext LoginResult.Error(errorMsg)
            }

            val jsonResponse = JSONObject(responseStr)
            if (jsonResponse.optBoolean("ok")) {
                val result = jsonResponse.getJSONObject("result")
                val token = result.optString("token")
                saveToken(token)

                val userIdStr = result.optString("user_ID", result.optString("user_id", "0"))
                val student = Student(
                    id = userIdStr.hashCode(),
                    studentName = result.optString("login", "Unknown"),
                    studentGroup = group,
                    studentNFC = "",
                    attendance = false,
                    role = result.optString("role", "student")
                )

                studentDao.insertStudent(student)
                LoginResult.Success(student)
            } else {
                LoginResult.Error(jsonResponse.optString("error", "Registration Failed"))
            }
        } catch (e: Exception) {
            Log.e("HTTP_REPO", "Register Exception", e)
            LoginResult.Error("Network Error: ${e.message ?: "Unknown"}")
        }
    }

    override suspend fun clearLocalRoomData() {
        try {
            studentDao.deleteAllStudents()
            // Do not clear sharedPrefs here because it contains the auth_token we just got
            // sharedPrefs.edit().clear().apply() 
        } catch (e: Exception) {
            Log.e("HTTP_REPO", "Clear Data Exception", e)
        }
    }

    @OptIn(InternalSerializationApi::class)
    override suspend fun syncAllStudents(): SyncResult = withContext(Dispatchers.IO) {
        try {
            val token = sharedPrefs.getString("auth_token", "") ?: ""
            Log.d("HTTP_REPO", "syncAllStudents token: $token")
            if (token.isEmpty()) {
                Log.e("HTTP_REPO", "syncAllStudents: No token found in sharedPrefs")
                return@withContext SyncResult.Error("No token found")
            }
            
            val authHeader = "Bearer $token"

            val subjects = getTeacherSubjects()
            var totalSynced = 0
            subjects.forEach { subject ->
                subject.groups.forEach { group ->
                    val stats = getGroupAttendance(group.id, subject.subject_id)
                    Log.d("HTTP_REPO", "Syncing group ${group.name}, found ${stats.size} students")
                    stats.forEach { stat ->
                        val student = Student(
                            id = stat.student_id,
                            studentName = stat.student_name,
                            studentGroup = group.name,
                            studentNFC = "", // NFC tag might not be in stats, but login sync can fill it
                            attendance = stat.attendance_percent > 50.0, // Example logic
                            role = "student"
                        )
                        studentDao.insertStudent(student)
                        totalSynced++
                    }
                }
            }

            if (totalSynced > 0) {
                SyncResult.Success(totalSynced, "Синхронизировано $totalSynced студентов")
            } else {
                val request = Request.Builder()
                    .url("$BASE_URL/profile")
                    .get()
                    .addHeader("Authorization", authHeader)
                    .build()

                val response = client.newCall(request).execute()
                val responseStr = response.body?.string() ?: ""
                val jsonRes = JSONObject(responseStr)

                if (response.isSuccessful && jsonRes.optBoolean("ok")) {
                    val resultObj = jsonRes.getJSONObject("result")
                    if (resultObj.optString("role") == "student") {
                        val student = Student(
                            id = resultObj.optString("student_id", resultObj.optString("user_id", "0")).hashCode(),
                            studentName = resultObj.optString("student_name", resultObj.optString("name", "Unknown")),
                            studentGroup = resultObj.optString("group_name", "Unknown"),
                            studentNFC = resultObj.optString("nfc_tag", ""),
                            attendance = false,
                            role = "student"
                        )
                        studentDao.insertStudent(student)
                        Log.d("HTTP_REPO", "Student profile synced and saved: ${student.studentName}")
                    }
                    
                    SyncResult.Success(1, "Профиль синхронизирован")
                } else {
                    SyncResult.Error("Ошибка синхронизации: ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.e("HTTP_REPO", "syncAllStudents error", e)
            SyncResult.Error("Sync Exception: ${e.message}")
        }
    }

    @OptIn(InternalSerializationApi::class)
    override suspend fun getStudentByNfc(nfcId: String): Student? = studentDao.getStudentByNfc(nfcId)

    @OptIn(InternalSerializationApi::class)
    override suspend fun getStudentById(id: Int): Student? = studentDao.getStudentById(id)

    @OptIn(InternalSerializationApi::class)
    override suspend fun updateAttendance(id: Int, status: Boolean) = studentDao.updateAttendance(id, status)

    @OptIn(InternalSerializationApi::class)
    override fun getAllStudents(): Flow<List<Student>> = studentDao.getAllStudents()

    override fun getAllLessons(): Flow<List<Lesson>> = studentDao.getAllLessons()

    @OptIn(InternalSerializationApi::class)
    override suspend fun insertStudent(student: Student): Long = studentDao.insertStudent(student)

    @OptIn(InternalSerializationApi::class)
    override suspend fun updateStudent(student: Student) = studentDao.updateStudent(student)

    @OptIn(InternalSerializationApi::class)
    override suspend fun deleteStudent(student: Student) = studentDao.deleteStudent(student)

    override suspend fun deleteAllStudents() = studentDao.deleteAllStudents()

    override suspend fun getAttendanceLink(lessonId: Int): AttendanceLinkResult = withContext(Dispatchers.IO) {
        try {
            val token = sharedPrefs.getString("auth_token", "") ?: ""
            if (token.isEmpty()) return@withContext AttendanceLinkResult.Error("Отсутствует токен авторизации")

            val jsonRequest = JSONObject().apply {
                put("lesson_id", lessonId)
            }

            val body = jsonRequest.toString().toRequestBody(JSON_TYPE)
            val request = Request.Builder()
                .url("$BASE_URL/api/teacher/attendance-link")
                .post(body)
                .addHeader("Authorization", "Bearer $token")
                .build()

            val response = client.newCall(request).execute()
            val responseStr = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                val errorMsg = JSONObject(responseStr).optString("error", "Ошибка сервера ${response.code}")
                return@withContext AttendanceLinkResult.Error(errorMsg)
            }

            val jsonResponse = JSONObject(responseStr)
            if (jsonResponse.optBoolean("ok")) {
                val resultData = jsonResponse.opt("result")
                val joinUrl = when (resultData) {
                    is JSONObject -> resultData.optString("join_url", resultData.optString("url", ""))
                    is String -> resultData
                    else -> resultData?.toString() ?: ""
                }
                
                if (joinUrl.isNotBlank()) {
                    AttendanceLinkResult.Success(joinUrl)
                } else {
                    AttendanceLinkResult.Error("Пустая ссылка в ответе")
                }
            } else {
                AttendanceLinkResult.Error(jsonResponse.optString("error", "Не удалось получить ссылку"))
            }
        } catch (e: Exception) {
            Log.e("HTTP_REPO", "Link Exception", e)
            AttendanceLinkResult.Error("Ошибка сети: ${e.message}")
        }
    }

    override suspend fun getAllUniqueGroups(): List<String> = withContext(Dispatchers.IO) {
        try {
            studentDao.getAllGroups().first()
        } catch (e: Exception) {
            Log.e("HTTP_REPO", "getAllUniqueGroups error", e)
            emptyList()
        }
    }

    override suspend fun createLesson(subject: String, teacherId: Int, groups: List<String>, lat: Double, lon: Double): Int? = withContext(Dispatchers.IO) {
        try {
            val token = sharedPrefs.getString("auth_token", "") ?: ""
            val teacherSubjects = getTeacherSubjects()
            val matchedSubject = teacherSubjects.find { it.subject_name == subject }
            val subjectId = matchedSubject?.subject_id ?: subject.hashCode()
            
            val groupIdsArr = JSONArray()
            groups.forEach { groupName ->
                val id = matchedSubject?.groups?.find { it.name == groupName }?.id ?: groupName.hashCode()
                groupIdsArr.put(id)
            }

            val json = JSONObject().apply {
                put("lesson_name", subject)
                put("subject_id", subjectId)
                put("group_ids", groupIdsArr)
                put("lat", lat)
                put("lon", lon)
                put("expires_minutes", 90)
            }

            val request = Request.Builder()
                .url("$BASE_URL/api/teacher/attendance/session")
                .post(json.toString().toRequestBody(JSON_TYPE))
                .addHeader("Authorization", "Bearer $token")
                .build()

            val response = client.newCall(request).execute()
            val responseStr = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val jsonObj = JSONObject(responseStr)
                if (jsonObj.optBoolean("ok")) {
                    val result = jsonObj.getJSONObject("result")
                    val lessonId = result.optInt("lesson_id")
                    val inviteToken = result.optString("invite_token")
                    sharedPrefs.edit().putString("last_invite_token", inviteToken).apply()
                    val lesson = Lesson(
                        subject = subject,
                        date = System.currentTimeMillis(),
                        groups = groups.joinToString(", ")
                    )
                    studentDao.insertLesson(lesson)
                    
                    return@withContext lessonId
                }
            }
            null
        } catch (e: Exception) {
            Log.e("HTTP_REPO", "createLesson error", e)
            null
        }
    }

    override suspend fun finishLesson(lessonId: Int): FinishLessonResult = withContext(Dispatchers.IO) {
        sharedPrefs.edit().remove("last_invite_token").apply()
        FinishLessonResult.Success("Lesson Finished", emptyList())
    }

    @OptIn(InternalSerializationApi::class)
    override suspend fun markAttendanceInLesson(lessonId: Int, nfcTag: String): AttendanceResult = withContext(Dispatchers.IO) {
        try {
            val token = sharedPrefs.getString("auth_token", "") ?: ""
            val inviteToken = sharedPrefs.getString("last_invite_token", "") ?: ""
            
            val jsonRequest = JSONObject().apply {
                put("lesson_id", lessonId)
                put("invite_token", inviteToken)
                put("device_id", nfcTag)
                put("lat", 0.0)
                put("lon", 0.0)
            }

            val body = jsonRequest.toString().toRequestBody(JSON_TYPE)
            val request = Request.Builder()
                .url("$BASE_URL/api/student/mark-attendance")
                .post(body)
                .addHeader("Authorization", "Bearer $token")
                .build()

            val response = client.newCall(request).execute()
            val responseStr = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val jsonResponse = JSONObject(responseStr)
                if (jsonResponse.optBoolean("ok")) {
                    val localStudent = studentDao.getStudentByNfc(nfcTag)
                    localStudent?.let {
                        studentDao.updateAttendance(it.id, true)
                    }
                    
                    val student = localStudent ?: Student(
                        studentName = "Student $nfcTag",
                        studentGroup = "Unknown",
                        studentNFC = nfcTag,
                        attendance = true
                    )
                    return@withContext AttendanceResult.Success(student)
                } else {
                    return@withContext AttendanceResult.Error(jsonResponse.optString("error", "Failed to mark"))
                }
            }
            AttendanceResult.Error("Server error: ${response.code}")
        } catch (e: Exception) {
            Log.e("HTTP_REPO", "markAttendanceInLesson error", e)
            AttendanceResult.Error("Network error")
        }
    }

    @OptIn(InternalSerializationApi::class)
    override suspend fun markAttendanceViaQr(
        lessonId: Int,
        deviceId: String,
        lat: Double,
        lon: Double,
        inviteToken: String?
    ): AttendanceResult = withContext(Dispatchers.IO) {
        try {
            val token = sharedPrefs.getString("auth_token", "") ?: ""
            if (token.isEmpty()) return@withContext AttendanceResult.Error("No token")

            val jsonRequest = JSONObject().apply {
                if (lessonId > 0) put("lesson_id", lessonId)
                if (!inviteToken.isNullOrBlank()) put("invite_token", inviteToken)
                put("device_id", deviceId)
                put("lat", lat)
                put("lon", lon)
            }

            val body = jsonRequest.toString().toRequestBody(JSON_TYPE)
            val request = Request.Builder()
                .url("$BASE_URL/api/student/mark-attendance")
                .post(body)
                .addHeader("Authorization", "Bearer $token")
                .build()

            val response = client.newCall(request).execute()
            val responseStr = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                val errorMsg = JSONObject(responseStr).optString("error", "Error ${response.code}")
                return@withContext AttendanceResult.Error(errorMsg)
            }

            val jsonResponse = JSONObject(responseStr)
            if (jsonResponse.optBoolean("ok")) {
                val result = jsonResponse.getJSONObject("result")
                val student = Student(
                    id = result.optInt("user_ID", 0),
                    studentName = result.optString("login", ""),
                    studentGroup = result.optString("group", ""),
                    studentNFC = result.optString("nfc_tag", ""),
                    attendance = true,
                    role = result.optString("role", "student"),
                    isFraud = result.optBoolean("is_fraud", false),
                    totalCheatAttempts = result.optInt("total_cheat_attempts", 0)
                )
                studentDao.insertStudent(student)
                AttendanceResult.Success(student)
            } else {
                AttendanceResult.Error(jsonResponse.optString("error", "Failed"))
            }
        } catch (e: Exception) {
            AttendanceResult.Error("Network error")
        }
    }

    private fun saveToken(token: String?) {
        Log.d("HTTP_REPO", "saveToken called with: $token")
        if (!token.isNullOrBlank()) {
            sharedPrefs.edit().putString("auth_token", token).apply()
            Log.d("HTTP_REPO", "Token saved to sharedPrefs")
        } else {
            Log.e("HTTP_REPO", "Token is null or blank!")
        }
    }

    override suspend fun uploadAvatar(imagePath: String): AvatarResult = withContext(Dispatchers.IO) {
        try {
            val token = sharedPrefs.getString("auth_token", "") ?: ""
            if (token.isEmpty()) return@withContext AvatarResult.Error("No token")
            Log.d("HTTP_REPO", "Avatar upload triggered for: $imagePath")
            AvatarResult.Success("mock_server_url_for_$imagePath")
        } catch (e: Exception) {
            AvatarResult.Error("Upload failed: ${e.message}")
        }
    }

    override suspend fun getTeacherSubjects(): List<TeacherSubject> = withContext(Dispatchers.IO) {
        try {
            val token = sharedPrefs.getString("auth_token", "") ?: ""
            val request = Request.Builder()
                .url("$BASE_URL/api/teacher/subjects")
                .get()
                .addHeader("Authorization", "Bearer $token")
                .build()

            val response = client.newCall(request).execute()
            val responseStr = response.body?.string() ?: ""
            Log.d("HTTP_REPO", "getTeacherSubjects response: $responseStr")

            if (response.isSuccessful) {
                val jsonResponse = JSONObject(responseStr)
                if (jsonResponse.optBoolean("ok")) {
                    val resultStr = jsonResponse.get("result").toString()
                    val subjectsResponse = jsonSerializer.decodeFromString<TeacherSubjectsResponse>(resultStr)
                    val subjects = subjectsResponse.subjects
                    Log.d("HTTP_REPO", "Parsed ${subjects.size} subjects")
                    return@withContext subjects
                }
            } else {
                Log.e("HTTP_REPO", "getTeacherSubjects failed: ${response.code}")
            }
            emptyList()
        } catch (e: Exception) {
            Log.e("HTTP_REPO", "getTeacherSubjects error", e)
            emptyList()
        }
    }

    override suspend fun getGroupAttendance(groupId: Int, subjectId: Int): List<StudentAttendanceStats> = withContext(Dispatchers.IO) {
        try {
            val token = sharedPrefs.getString("auth_token", "") ?: ""
            val jsonRequest = JSONObject().apply {
                put("group_id", groupId)
                put("subject_id", subjectId)
            }

            val body = jsonRequest.toString().toRequestBody(JSON_TYPE)
            val request = Request.Builder()
                .url("$BASE_URL/api/teacher/attendance/group")
                .post(body)
                .addHeader("Authorization", "Bearer $token")
                .build()

            val response = client.newCall(request).execute()
            val responseStr = response.body?.string() ?: ""
            Log.d("HTTP_REPO", "getGroupAttendance($groupId, $subjectId) response: $responseStr")

            if (response.isSuccessful) {
                val jsonResponse = JSONObject(responseStr)
                if (jsonResponse.optBoolean("ok")) {
                    val resultObj = jsonResponse.getJSONObject("result")
                    val studentsStr = resultObj.getJSONArray("students").toString()
                    val stats = jsonSerializer.decodeFromString<List<StudentAttendanceStats>>(studentsStr)
                    Log.d("HTTP_REPO", "Parsed ${stats.size} students for group $groupId")
                    return@withContext stats
                }
            }
            emptyList()
        } catch (e: Exception) {
            Log.e("HTTP_REPO", "getGroupAttendance error", e)
            emptyList()
        }
    }

    override suspend fun getStudentHistory(year: Int): AttendanceHistoryResponse = withContext(Dispatchers.IO) {
        try {
            val token = sharedPrefs.getString("auth_token", "") ?: ""
            val request = Request.Builder()
                .url("$BASE_URL/api/student/attendance/history?year=$year")
                .get()
                .addHeader("Authorization", "Bearer $token")
                .build()

            val response = client.newCall(request).execute()
            val responseStr = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val jsonResponse = JSONObject(responseStr)
                if (jsonResponse.optBoolean("ok")) {
                    val resultStr = jsonResponse.getJSONObject("result").toString()
                    return@withContext jsonSerializer.decodeFromString<AttendanceHistoryResponse>(resultStr)
                }
            }
            AttendanceHistoryResponse(emptyList(), year)
        } catch (e: Exception) {
            Log.e("HTTP_REPO", "getStudentHistory error", e)
            AttendanceHistoryResponse(emptyList(), year)
        }
    }

    override suspend fun getDetailedStudentHistory(studentId: Int, subjectId: Int): List<HistoryItem> = withContext(Dispatchers.IO) {
        try {
            val token = sharedPrefs.getString("auth_token", "") ?: ""
            val jsonRequest = JSONObject().apply {
                put("student_id", studentId)
                put("subject_id", subjectId)
            }

            val body = jsonRequest.toString().toRequestBody(JSON_TYPE)
            val request = Request.Builder()
                .url("$BASE_URL/api/teacher/attendance/student/history")
                .post(body)
                .addHeader("Authorization", "Bearer $token")
                .build()

            val response = client.newCall(request).execute()
            val responseStr = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val jsonResponse = JSONObject(responseStr)
                if (jsonResponse.optBoolean("ok")) {
                    val resultObj = jsonResponse.getJSONObject("result")
                    val itemsStr = resultObj.getJSONArray("items").toString()
                    return@withContext jsonSerializer.decodeFromString<List<HistoryItem>>(itemsStr)
                }
            }
            emptyList()
        } catch (e: Exception) {
            Log.e("HTTP_REPO", "getDetailedStudentHistory error", e)
            emptyList()
        }
    }
}
