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
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
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
            if (token.isEmpty()) return@withContext SyncResult.Error("No token found")
            
            val authHeader = "Bearer $token"

            // 1. Sync profile
            val profileRequest = Request.Builder()
                .url("$BASE_URL/profile")
                .get()
                .addHeader("Authorization", authHeader)
                .build()

            val profileResponse = client.newCall(profileRequest).execute()
            val profileStr = profileResponse.body?.string() ?: ""
            val profileJson = JSONObject(profileStr)

            if (profileResponse.isSuccessful && profileJson.optBoolean("ok")) {
                val resultObj = profileJson.getJSONObject("result")
                val avatarUrl = resultObj.optString("avatar", "")
                val studentName = resultObj.optString("name", resultObj.optString("student_name", "Unknown"))
                val studentGroup = resultObj.optString("group_name", resultObj.optString("group", "Unknown"))
                val userIdStr = resultObj.optString("user_id", "0")
                val role = resultObj.optString("role", "student")
                val nfcTag = resultObj.optString("nfc_tag", "")

                sharedPrefs.edit().putString("avatar_url", avatarUrl).apply()

                val profileStudent = Student(
                    id = userIdStr.hashCode(),
                    studentName = studentName,
                    studentGroup = studentGroup,
                    studentNFC = nfcTag,
                    attendance = false,
                    role = role
                )
                studentDao.insertStudent(profileStudent)
            }

            // 2. Get Active Session to know the start time
            var activeSessionStartTime: String? = null
            try {
                val activeRequest = Request.Builder()
                    .url("$BASE_URL/api/teacher/attendance/session/active")
                    .get()
                    .addHeader("Authorization", authHeader)
                    .build()
                val activeResponse = client.newCall(activeRequest).execute()
                val activeStr = activeResponse.body?.string() ?: ""
                val activeJson = JSONObject(activeStr)
                if (activeResponse.isSuccessful && activeJson.optBoolean("ok")) {
                    val result = activeJson.getJSONObject("result")
                    if (result.optBoolean("active")) {
                        activeSessionStartTime = result.getJSONObject("session").optString("created_at")
                    }
                }
            } catch (e: Exception) {
                Log.e("HTTP_REPO", "Error getting active session", e)
            }

            // 3. Get Teacher subjects and sync attendance
            val subjects = getTeacherSubjects()
            val studentsToUpdate = mutableMapOf<Int, Student>()
            var totalSynced = 0

            subjects.forEach { subject ->
                subject.groups.forEach { group ->
                    try {
                        val stats = getGroupAttendance(group.id, subject.subject_id)
                        stats.forEach { stat ->
                            var isPresentInCurrentSession = false
                            
                            if (activeSessionStartTime != null && !stat.last_marked_at.isNullOrBlank()) {
                                isPresentInCurrentSession = try {
                                    val lastMarked = java.time.OffsetDateTime.parse(stat.last_marked_at)
                                    val sessionStart = java.time.OffsetDateTime.parse(activeSessionStartTime)
                                    !lastMarked.isBefore(sessionStart)
                                } catch (e: Exception) {
                                    Log.e("HTTP_REPO", "Error parsing date: lastMarked=${stat.last_marked_at}, sessionStart=$activeSessionStartTime", e)
                                    stat.last_marked_at >= activeSessionStartTime
                                }
                                Log.d("HTTP_REPO", "Checking student ${stat.student_name}: last_marked=${stat.last_marked_at}, session_start=$activeSessionStartTime, result=$isPresentInCurrentSession")
                            }
                            
                            val existingLocal = studentDao.getStudentById(stat.student_id)
                            val nfc = existingLocal?.studentNFC ?: ""
                            
                            studentsToUpdate[stat.student_id] = Student(
                                id = stat.student_id,
                                studentName = stat.student_name,
                                studentGroup = group.name,
                                studentNFC = nfc, 
                                attendance = isPresentInCurrentSession,
                                role = "student"
                            )
                        }
                    } catch (e: Exception) {
                        Log.e("HTTP_REPO", "Error syncing group ${group.name}", e)
                    }
                }
            }

            studentsToUpdate.values.forEach { 
                studentDao.insertStudent(it)
                totalSynced++
            }

            if (totalSynced > 0) {
                SyncResult.Success(totalSynced, "Синхронизировано $totalSynced студентов")
            } else {
                SyncResult.Success(1, "Профиль синхронизирован")
            }
        } catch (e: Exception) {
            Log.e("HTTP_REPO", "syncAllStudents error", e)
            SyncResult.Error("Sync Exception: ${e.message}")
        }
    }

    override suspend fun syncLessonAttendance(subjectId: Int, groupIds: List<Int>): SyncResult = withContext(Dispatchers.IO) {
        // Для простоты используем общую синхронизацию, она теперь корректно мержит статусы
        syncAllStudents()
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
                    val totpSecret = result.optString("totp_secret")
                    sharedPrefs.edit().apply {
                        putString("last_invite_token", inviteToken)
                        if (totpSecret.isNotBlank()) putString("last_totp_secret", totpSecret)
                    }.apply()
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
            val studentToken = nfcTag // Student's JWT token transmitted via HCE
            
            // Get location if saved in prefs by LessonFragment
            val lat = sharedPrefs.getFloat("last_lat", 0.0f).toDouble()
            val lon = sharedPrefs.getFloat("last_lon", 0.0f).toDouble()

            val jsonRequest = JSONObject().apply {
                put("lesson_id", lessonId)
                if (!inviteToken.isNullOrBlank()) {
                    put("invite_token", inviteToken)
                } else {
                    put("invite_token", token) 
                }
                put("device_id", studentToken)
                put("lat", lat)
                put("lon", lon)
            }
            
            Log.d("HTTP_REPO", "NFC Mark Attempt 1 (Standard): $jsonRequest")
            
            val body = jsonRequest.toString().toRequestBody(JSON_TYPE)
            val request = Request.Builder()
                .url("$BASE_URL/api/student/mark-attendance")
                .post(body)
                .addHeader("Authorization", "Bearer $studentToken")
                .build()

            var response = client.newCall(request).execute()
            var responseStr = response.body?.string() ?: ""
            Log.d("HTTP_REPO", "NFC Mark Response 1 (${response.code}): $responseStr")

            if (response.code == 403 || response.code == 400) {
                // Try second attempt: maybe the server wants /api/student/attendance/confirm
                val jsonConfirm = JSONObject().apply {
                    put("invite_token", if (inviteToken.isNotBlank()) inviteToken else token)
                }
                Log.d("HTTP_REPO", "NFC Mark Attempt 2 (Confirm): $jsonConfirm")
                
                val requestConfirm = Request.Builder()
                    .url("$BASE_URL/api/student/attendance/confirm")
                    .post(jsonConfirm.toString().toRequestBody(JSON_TYPE))
                    .addHeader("Authorization", "Bearer $studentToken")
                    .build()
                
                response = client.newCall(requestConfirm).execute()
                responseStr = response.body?.string() ?: ""
                Log.d("HTTP_REPO", "NFC Mark Response 2 (${response.code}): $responseStr")
            }

            if (response.isSuccessful) {
                val jsonResponse = JSONObject(responseStr)
                if (jsonResponse.optBoolean("ok")) {
                    var student: Student? = null
                    try {
                        val profileRequest = Request.Builder()
                            .url("$BASE_URL/profile")
                            .get()
                            .addHeader("Authorization", "Bearer $studentToken")
                            .build()
                        val profileResponse = client.newCall(profileRequest).execute()
                        val profileStr = profileResponse.body?.string() ?: ""
                        val profileJson = JSONObject(profileStr)
                        if (profileResponse.isSuccessful && profileJson.optBoolean("ok")) {
                            val resultObj = profileJson.getJSONObject("result")
                            val studentName = resultObj.optString("name", resultObj.optString("student_name", "Unknown"))
                            val studentGroup = resultObj.optString("group_name", resultObj.optString("group", "Unknown"))
                            val userIdStr = resultObj.optString("user_id", "0")
                            val role = resultObj.optString("role", "student")
                            val nfcTagVal = resultObj.optString("nfc_tag", "")
                            
                            student = Student(
                                id = userIdStr.toIntOrNull() ?: userIdStr.hashCode(),
                                studentName = studentName,
                                studentGroup = studentGroup,
                                studentNFC = nfcTagVal,
                                attendance = true,
                                role = role
                            )
                            studentDao.insertStudent(student)
                        }
                    } catch (e: Exception) {
                        Log.e("HTTP_REPO", "Failed to fetch student profile for token", e)
                    }

                    if (student == null) {
                        val localStudent = studentDao.getStudentByNfc(nfcTag)
                        localStudent?.let {
                            studentDao.updateAttendance(it.id, true)
                            student = it.copy(attendance = true)
                        }
                    }
                    
                    val finalStudent = student ?: Student(
                        studentName = "Студент",
                        studentGroup = "Группа",
                        studentNFC = nfcTag,
                        attendance = true
                    )
                    return@withContext AttendanceResult.Success(finalStudent)
                }
            }
            
            val finalError = try { JSONObject(responseStr).optString("error", "Error ${response.code}") } catch(e:Exception) { "Error ${response.code}" }
            AttendanceResult.Error(finalError)
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
        inviteToken: String?,
        totpCode: String?
    ): AttendanceResult = withContext(Dispatchers.IO) {
        try {
            val token = sharedPrefs.getString("auth_token", "") ?: ""
            if (token.isEmpty()) return@withContext AttendanceResult.Error("No token")

            val jsonRequest = JSONObject().apply {
                if (lessonId > 0) put("lesson_id", lessonId)
                if (!inviteToken.isNullOrBlank()) put("invite_token", inviteToken)
                if (!totpCode.isNullOrBlank()) put("totp_code", totpCode)
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
            
            val file = java.io.File(imagePath)
            if (!file.exists()) return@withContext AvatarResult.Error("File not found")

            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("avatar", file.name, file.asRequestBody("image/jpeg".toMediaType()))
                .build()

            val request = Request.Builder()
                .url("$BASE_URL/api/user/upload-avatar")
                .post(body)
                .addHeader("Authorization", "Bearer $token")
                .build()

            val response = client.newCall(request).execute()
            val responseStr = response.body?.string() ?: ""
            Log.d("HTTP_REPO", "NFC Mark Response (${response.code}): $responseStr")

            if (response.isSuccessful) {
                val jsonResponse = JSONObject(responseStr)
                if (jsonResponse.optBoolean("ok")) {
                    val url = jsonResponse.optString("result")
                    sharedPrefs.edit().putString("avatar_url", url).apply()
                    return@withContext AvatarResult.Success(url)
                }
            }
            AvatarResult.Error("Server error: ${response.code}")
        } catch (e: Exception) {
            Log.e("HTTP_REPO", "uploadAvatar error", e)
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
            Log.d("HTTP_REPO", "NFC Mark Response (${response.code}): $responseStr")

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
            Log.d("HTTP_REPO", "NFC Mark Response (${response.code}): $responseStr")

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
