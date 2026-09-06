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
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class StudentRepositoryHTTPS(
    private val context: Context,
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

    private fun getUnsafeOkHttpClientBuilder(): OkHttpClient.Builder {
        return try {
            val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(
                object : javax.net.ssl.X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                }
            )
            val sslContext = javax.net.ssl.SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())
            val sslSocketFactory = sslContext.socketFactory

            OkHttpClient.Builder()
                .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as javax.net.ssl.X509TrustManager)
                .hostnameVerifier { _, _ -> true }
        } catch (e: Exception) {
            OkHttpClient.Builder()
        }
    }

    @Synchronized
    private fun refreshSessionTokenSync(rawCurrentToken: String?): String? {
        val currentToken = rawCurrentToken ?: sharedPrefs.getString("auth_token", null)
        if (currentToken.isNullOrBlank()) return null

        return try {
            val refreshUrl = "$BASE_URL/api/auth/refresh"
            val refreshRequest = Request.Builder()
                .url(refreshUrl)
                .post("{}".toRequestBody(JSON_TYPE))
                .header("Authorization", "Bearer $currentToken")
                .build()

            val rawClient = getUnsafeOkHttpClientBuilder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()

            val response = rawClient.newCall(refreshRequest).execute()
            val responseBody = response.body?.string() ?: ""
            if (response.isSuccessful) {
                val json = JSONObject(responseBody)
                if (json.optBoolean("ok")) {
                    val result = json.getJSONObject("result")
                    val newToken = result.optString("token")
                    if (newToken.isNotBlank()) {
                        sharedPrefs.edit().putString("auth_token", newToken).apply()
                        Log.d("HTTP_REPO", "Token refreshed successfully, expires_at: ${result.optString("expires_at")}")
                        newToken
                    } else null
                } else null
            } else {
                Log.e("HTTP_REPO", "Refresh failed: status=${response.code}, body=$responseBody")
                null
            }
        } catch (e: Exception) {
            Log.e("HTTP_REPO", "Exception refreshing token", e)
            null
        }
    }

    override suspend fun refreshSessionToken(): Boolean = withContext(Dispatchers.IO) {
        refreshSessionTokenSync(null) != null
    }

    private val client = getUnsafeOkHttpClientBuilder()
        .addInterceptor(logger)
        .addInterceptor { chain ->
            val originalRequest = chain.request()
            val authHeader = originalRequest.header("Authorization")
            var currentToken = sharedPrefs.getString("auth_token", null)

            // 1. Proactive check & prediction: if token expires soon (<10m) or is expired, refresh before sending
            if (!authHeader.isNullOrBlank() && !currentToken.isNullOrBlank()) {
                if (com.example.kotlinroomdatabase.util.JwtUtils.needsRefresh(currentToken)) {
                    Log.d("HTTP_REPO", "Proactively refreshing expiring JWT token before request: ${originalRequest.url.encodedPath}")
                    val refreshedToken = refreshSessionTokenSync(currentToken)
                    if (!refreshedToken.isNullOrBlank()) {
                        currentToken = refreshedToken
                    }
                }
            }

            val requestToProceed = if (!authHeader.isNullOrBlank() && !currentToken.isNullOrBlank()) {
                originalRequest.newBuilder().header("Authorization", "Bearer $currentToken").build()
            } else {
                originalRequest
            }

            val response = chain.proceed(requestToProceed)

            // 2. Reactive recovery: if 401 Unauthorized, try one reactive refresh & retry
            if (response.code == 401) {
                val path = originalRequest.url.encodedPath
                if (!path.endsWith("/login") && !path.endsWith("/register") && !path.contains("/api/auth/refresh")) {
                    Log.d("HTTP_REPO", "Received 401 for $path, attempting token refresh...")
                    val refreshedToken = refreshSessionTokenSync(null)
                    if (!refreshedToken.isNullOrBlank()) {
                        response.close()
                        val retriedRequest = originalRequest.newBuilder()
                            .header("Authorization", "Bearer $refreshedToken")
                            .build()
                        return@addInterceptor chain.proceed(retriedRequest)
                    } else {
                        Log.e("HTTP_REPO", "Token refresh failed on 401, triggering session expiration")
                        triggerSessionExpired(context, "Срок действия сессии истёк. Пожалуйста, выполните вход повторно.")
                    }
                }
            }

            response
        }
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    companion object {
        fun triggerSessionExpired(context: Context, customMessage: String? = null) {
            com.example.kotlinroomdatabase.util.JwtUtils.clearAllSessionData(context)
            val intent = android.content.Intent("com.example.kotlinroomdatabase.LOGOUT").apply {
                putExtra("reason", customMessage ?: "Срок действия сессии истёк. Пожалуйста, выполните вход повторно.")
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)
            try {
                androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
            } catch (e: Exception) {}
        }
    }

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    private val BASE_URL: String
        get() = com.example.kotlinroomdatabase.config.ServerConfig.getBaseUrl(context)

    fun getUnsafeOkHttpClient(): OkHttpClient = client

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

                val email = result.optString("email", "")
                if (email.isNotBlank()) {
                    sharedPrefs.edit().putString("user_email", email).apply()
                    context.getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
                        .edit().putString("user_email", email).apply()
                }

                val userIdStr = result.optString("user_ID", result.optString("user_id", "0"))
                val parsedId = userIdStr.toIntOrNull() ?: userIdStr.hashCode()
                val effectiveRole = result.optString("active_role", result.optString("role", result.optString("primary_role", "student"))).trim().lowercase()
                val studentName = result.optString("teacher_name", result.optString("name", result.optString("login", "Пользователь")))

                val studentPrefs = context.getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
                studentPrefs.edit().apply {
                    putString("user_role", effectiveRole)
                    putString("student_name", studentName)
                    putInt("current_student_id", parsedId)
                }.apply()

                val student = Student(
                    id = parsedId,
                    studentName = studentName,
                    studentGroup = result.optString("group_name", result.optString("group", "")),
                    studentNFC = result.optString("nfc_tag", ""),
                    attendance = false,
                    role = effectiveRole
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
                    id = userIdStr.toIntOrNull() ?: userIdStr.hashCode(),
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
                val displayName = resultObj.optString("teacher_name", resultObj.optString("name", resultObj.optString("student_name", resultObj.optString("login", "Пользователь"))))
                val studentGroup = resultObj.optString("group_name", resultObj.optString("group", ""))
                val jobTitle = resultObj.optString("job_title", "")
                val userIdStr = resultObj.optString("user_id", resultObj.optString("user_ID", "0"))
                val activeRole = resultObj.optString("active_role", resultObj.optString("role", resultObj.optString("primary_role", "student"))).trim().lowercase()
                val primaryRole = resultObj.optString("primary_role", "").trim().lowercase()
                val nfcTag = resultObj.optString("nfc_tag", "")
                val email = resultObj.optString("email", "")

                val rolesArr = resultObj.optJSONArray("roles")
                val rolesList = mutableListOf<String>()
                if (rolesArr != null) {
                    for (i in 0 until rolesArr.length()) {
                        rolesList.add(rolesArr.getString(i).trim().lowercase())
                    }
                }
                if (rolesList.isEmpty()) rolesList.add(activeRole)

                val studentPrefs = context.getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
                val parsedUserId = userIdStr.toIntOrNull() ?: userIdStr.hashCode()

                sharedPrefs.edit().apply {
                    if (email.isNotBlank()) putString("user_email", email)
                    putString("avatar_url", avatarUrl)
                }.apply()

                studentPrefs.edit().apply {
                    putInt("current_student_id", parsedUserId)
                    putString("user_role", activeRole)
                    putString("primary_role", primaryRole)
                    putString("user_roles", rolesList.joinToString(","))
                    putString("student_name", displayName)
                    if (email.isNotBlank()) putString("user_email", email)
                    putString("student_group", studentGroup)
                    putString("job_title", jobTitle)
                    if (resultObj.has("lectern_id")) putInt("lectern_id", resultObj.optInt("lectern_id"))
                }.apply()

                val profileStudent = Student(
                    id = parsedUserId,
                    studentName = displayName,
                    studentGroup = studentGroup.ifBlank { jobTitle },
                    studentNFC = nfcTag,
                    attendance = false,
                    role = activeRole
                )
                studentDao.insertStudent(profileStudent)
            }

            val studentPrefs = context.getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
            val currentRole = studentPrefs.getString("user_role", null) ?: "student"
            if (!com.example.kotlinroomdatabase.util.RoleUtils.isTeacherOrHead(currentRole) && currentRole != "admin") {
                return@withContext SyncResult.Success(1, "Студент синхронизирован")
            }

            // 2. Get Active Session to know the start time
            var activeSessionStartTime: String? = null
            var activeSessionSubjectId: Int? = null
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
                        val session = result.getJSONObject("session")
                        activeSessionStartTime = session.optString("created_at")
                        activeSessionSubjectId = session.optInt("subject_id", 0)
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
                            val prevAttendance = studentsToUpdate[stat.student_id]?.attendance ?: (existingLocal?.attendance ?: false)
                            val finalAttendance = if (activeSessionStartTime != null) {
                                prevAttendance || isPresentInCurrentSession
                            } else {
                                false
                            }
                            
                            studentsToUpdate[stat.student_id] = Student(
                                id = stat.student_id,
                                studentName = stat.student_name,
                                studentGroup = group.name,
                                studentNFC = nfc, 
                                attendance = finalAttendance,
                                role = "student"
                            )
                        }
                    } catch (e: Exception) {
                        Log.e("HTTP_REPO", "Error syncing group ${group.name}", e)
                    }
                }
            }

            // 4. Also sync students from /api/staff/overview
            try {
                val staffRequest = Request.Builder()
                    .url("$BASE_URL/api/staff/overview")
                    .get()
                    .addHeader("Authorization", authHeader)
                    .build()
                val staffResp = client.newCall(staffRequest).execute()
                if (staffResp.isSuccessful) {
                    val staffJson = JSONObject(staffResp.body?.string() ?: "")
                    if (staffJson.optBoolean("ok")) {
                        val resObj = staffJson.optJSONObject("result")
                        val studentsArr = resObj?.optJSONArray("students")
                        if (studentsArr != null) {
                            for (i in 0 until studentsArr.length()) {
                                val sObj = studentsArr.getJSONObject(i)
                                val sId = sObj.getInt("student_id")
                                val sName = sObj.getString("name")
                                val sGroup = sObj.getString("group_name")
                                val existing = studentDao.getStudentById(sId)
                                val nfc = existing?.studentNFC ?: ""
                                val isAttended = studentsToUpdate[sId]?.attendance ?: (existing?.attendance ?: false)
                                studentsToUpdate[sId] = Student(
                                    id = sId,
                                    studentName = sName,
                                    studentGroup = sGroup,
                                    studentNFC = nfc,
                                    attendance = isAttended,
                                    role = "student"
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("HTTP_REPO", "Error syncing staff overview", e)
            }

            // 5. Fallback if still empty (e.g. demo teacher without active semester)
            if (studentsToUpdate.isEmpty()) {
                try {
                    val perf = getTeacherGroupSubjectPerformance(469, 1, 2)
                    perf.forEach { row ->
                        val existing = studentDao.getStudentById(row.student_id)
                        val nfc = existing?.studentNFC ?: ""
                        studentsToUpdate[row.student_id] = Student(
                            id = row.student_id,
                            studentName = row.student_name,
                            studentGroup = "TEST-GROUP-1",
                            studentNFC = nfc,
                            attendance = false,
                            role = "student"
                        )
                    }
                } catch (e: Exception) {
                    Log.e("HTTP_REPO", "Error syncing demo group performance", e)
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

    override suspend fun createLesson(
        subject: String,
        teacherId: Int,
        groups: List<String>,
        lat: Double,
        lon: Double,
        lessonType: String
    ): Int? = withContext(Dispatchers.IO) {
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
                put("lesson_name", "$subject ($lessonType)")
                put("subject_id", subjectId)
                put("group_ids", groupIdsArr)
                put("lesson_type", lessonType)
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
        try {
            val token = sharedPrefs.getString("auth_token", "") ?: ""
            val json = JSONObject().apply {
                put("session_id", lessonId)
                put("lesson_id", lessonId)
            }
            val request = Request.Builder()
                .url("$BASE_URL/api/teacher/attendance/session/finish")
                .post(json.toString().toRequestBody(JSON_TYPE))
                .addHeader("Authorization", "Bearer $token")
                .build()

            val response = client.newCall(request).execute()
            val responseStr = response.body?.string() ?: ""
            Log.d("HTTP_REPO", "finishLesson response: $responseStr")

            sharedPrefs.edit().remove("last_invite_token").remove("last_totp_secret").apply()

            if (response.isSuccessful) {
                FinishLessonResult.Success("Lesson Finished", emptyList())
            } else {
                FinishLessonResult.Error(try { JSONObject(responseStr).optString("error", "Ошибка завершения") } catch (e: Exception) { "Ошибка завершения" })
            }
        } catch (e: Exception) {
            Log.e("HTTP_REPO", "finishLesson error", e)
            FinishLessonResult.Error("Network error: ${e.message}")
        }
    }

    @OptIn(InternalSerializationApi::class)
    override suspend fun markAttendanceInLesson(lessonId: Int, nfcTag: String): AttendanceResult = withContext(Dispatchers.IO) {
        try {
            val teacherToken = sharedPrefs.getString("auth_token", "") ?: ""
            val inviteToken = sharedPrefs.getString("last_invite_token", "") ?: ""
            val cleanTag = nfcTag.trim()

            Log.d("HTTP_REPO", "markAttendanceInLesson: lessonId=$lessonId, tag=$cleanTag")

            // 1. Compact HCE Student payload: "STUDENT:<student_id>:<name>"
            if (cleanTag.startsWith("STUDENT:")) {
                val parts = cleanTag.split(":")
                val sId = parts.getOrNull(1)?.toIntOrNull()
                val sName = parts.getOrNull(2) ?: "Студент"
                if (sId != null && sId > 0) {
                    val markRes = teacherMarkAttendance(lessonId, sId, "present")
                    if (markRes is GenericResult.Success) {
                        var s = studentDao.getStudentById(sId)
                        if (s == null) {
                            s = Student(
                                id = sId,
                                studentName = sName,
                                studentGroup = "Группа",
                                studentNFC = cleanTag,
                                attendance = true,
                                role = "student"
                            )
                            studentDao.insertStudent(s)
                        } else {
                            studentDao.updateAttendance(sId, true)
                            s = s.copy(attendance = true)
                        }
                        return@withContext AttendanceResult.Success(s)
                    } else if (markRes is GenericResult.Error) {
                        return@withContext AttendanceResult.Error(markRes.message)
                    }
                }
            }

            // 2. If student passed JWT via HCE
            if (cleanTag.startsWith("eyJ")) {
                try {
                    val profileReq = Request.Builder()
                        .url("$BASE_URL/profile")
                        .get()
                        .addHeader("Authorization", "Bearer $cleanTag")
                        .build()
                    val profResp = client.newCall(profileReq).execute()
                    val profStr = profResp.body?.string() ?: ""
                    val profJson = JSONObject(profStr)
                    if (profResp.isSuccessful && profJson.optBoolean("ok")) {
                        val resObj = profJson.getJSONObject("result")
                        val sId = resObj.optInt("user_id", resObj.optInt("id", 0))
                        val sName = resObj.optString("name", resObj.optString("student_name", "Студент"))
                        val sGroup = resObj.optString("group_name", resObj.optString("group", "Группа"))
                        
                        if (sId > 0) {
                            teacherMarkAttendance(lessonId, sId, "present")
                        }

                        val markedStudent = Student(
                            id = if (sId > 0) sId else sName.hashCode(),
                            studentName = sName,
                            studentGroup = sGroup,
                            studentNFC = cleanTag,
                            attendance = true,
                            role = "student"
                        )
                        studentDao.insertStudent(markedStudent)
                        studentDao.updateAttendance(markedStudent.id, true)
                        return@withContext AttendanceResult.Success(markedStudent)
                    }
                } catch (e: Exception) {
                    Log.e("HTTP_REPO", "Error processing JWT HCE attendance", e)
                }
            }

            // 3. If student NFC tag / UID found in local DB
            var localStudent = studentDao.getStudentByNfc(cleanTag)
            if (localStudent == null) {
                val numId = cleanTag.toIntOrNull()
                if (numId != null) {
                    localStudent = studentDao.getStudentById(numId)
                }
            }

            if (localStudent != null) {
                val markRes = teacherMarkAttendance(lessonId, localStudent.id, "present")
                if (markRes is GenericResult.Success) {
                    studentDao.updateAttendance(localStudent.id, true)
                    return@withContext AttendanceResult.Success(localStudent.copy(attendance = true))
                } else if (markRes is GenericResult.Error) {
                    return@withContext AttendanceResult.Error(markRes.message)
                }
            }

            // 4. Try matching with overview students
            try {
                val allStudents = studentDao.getAllStudents().first()
                val matched = allStudents.firstOrNull { it.studentNFC.equals(cleanTag, ignoreCase = true) }
                if (matched != null) {
                    val markRes = teacherMarkAttendance(lessonId, matched.id, "present")
                    if (markRes is GenericResult.Success) {
                        studentDao.updateAttendance(matched.id, true)
                        return@withContext AttendanceResult.Success(matched.copy(attendance = true))
                    }
                }
            } catch (e: Exception) {}

            AttendanceResult.Error("Студент не найден по NFC ($cleanTag)")
        } catch (e: Exception) {
            Log.e("HTTP_REPO", "markAttendanceInLesson error", e)
            AttendanceResult.Error("Ошибка отметки: ${e.message}")
        }
    }

    @OptIn(InternalSerializationApi::class)
    override suspend fun markAttendanceViaQr(
        lessonId: Int,
        deviceId: String,
        lat: Double,
        lon: Double,
        inviteToken: String?,
        totpCode: String?,
        ts: Long?,
        nonce: String?,
        biometricSignature: String?
    ): AttendanceResult = withContext(Dispatchers.IO) {
        try {
            val token = sharedPrefs.getString("auth_token", "") ?: ""
            if (token.isEmpty()) return@withContext AttendanceResult.Error("Сессия не найдена. Пожалуйста, авторизуйтесь снова.")

            var effectiveLessonId = lessonId
            if (effectiveLessonId <= 0 && !inviteToken.isNullOrBlank()) {
                try {
                    val parts = inviteToken.split(".")
                    if (parts.size >= 2) {
                        val payloadBytes = android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
                        val payloadJson = JSONObject(String(payloadBytes, Charsets.UTF_8))
                        effectiveLessonId = payloadJson.optString("lesson_id").toIntOrNull()
                            ?: payloadJson.optInt("lesson_id", 0)
                    }
                } catch (e: Exception) {
                    Log.w("HTTP_REPO", "Failed to parse JWT inviteToken claims: ${e.message}")
                }
            }

            val jsonRequest = JSONObject().apply {
                if (effectiveLessonId > 0) put("lesson_id", effectiveLessonId)
                if (!inviteToken.isNullOrBlank()) put("invite_token", inviteToken)
                if (!totpCode.isNullOrBlank()) put("totp_code", totpCode)
                if (ts != null && ts > 0) put("ts", ts)
                if (!nonce.isNullOrBlank()) put("nonce", nonce)
                if (!biometricSignature.isNullOrBlank()) put("biometric_signature", biometricSignature)
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
                val errorMsg = try {
                    JSONObject(responseStr).optString("error", "Ошибка сервера (${response.code})")
                } catch (e: Exception) {
                    "Ошибка сервера (${response.code})"
                }
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

                val lessonName = result.optString("lesson_name", result.optString("subject_name", "Учебное занятие"))
                val sessionId = result.optInt("session_id", effectiveLessonId)
                val expiresAtStr = result.optString("expires_at", "")
                var expiresAtMillis = 0L
                if (expiresAtStr.isNotBlank()) {
                    try {
                        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault())
                        expiresAtMillis = sdf.parse(expiresAtStr.substringBefore("Z").substringBefore("+"))?.time ?: 0L
                    } catch (e: Exception) {}
                }
                if (expiresAtMillis <= 0L) {
                    expiresAtMillis = System.currentTimeMillis() + 90 * 60 * 1000L
                }

                AttendanceResult.Success(student, lessonName, expiresAtMillis, sessionId)
            } else {
                AttendanceResult.Error(jsonResponse.optString("error", "Не удалось подтвердить посещаемость"))
            }
        } catch (e: Exception) {
            Log.e("HTTP_REPO", "markAttendanceViaQr error", e)
            AttendanceResult.Error("Ошибка соединения с сервером: ${e.message}")
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
            if (token.isNotEmpty()) {
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
                        if (subjects.isNotEmpty()) {
                            Log.d("HTTP_REPO", "Parsed ${subjects.size} subjects")
                            return@withContext subjects
                        }
                    }
                }

                // Fallback 1: Try /api/staff/overview
                try {
                    val overviewRequest = Request.Builder()
                        .url("$BASE_URL/api/staff/overview")
                        .get()
                        .addHeader("Authorization", "Bearer $token")
                        .build()
                    val overviewResp = client.newCall(overviewRequest).execute()
                    if (overviewResp.isSuccessful) {
                        val overviewJson = JSONObject(overviewResp.body?.string() ?: "")
                        if (overviewJson.optBoolean("ok")) {
                            val res = overviewJson.optJSONObject("result")
                            val groupsArr = res?.optJSONArray("groups")
                            if (groupsArr != null && groupsArr.length() > 0) {
                                val groupsList = mutableListOf<TeacherGroup>()
                                for (i in 0 until groupsArr.length()) {
                                    val g = groupsArr.getJSONObject(i)
                                    groupsList.add(TeacherGroup(g.getInt("group_id"), g.getString("group_name")))
                                }
                                return@withContext listOf(
                                    TeacherSubject(1, "Networks", groupsList),
                                    TeacherSubject(2, "Информатика", groupsList),
                                    TeacherSubject(3, "Программирование", groupsList)
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("HTTP_REPO", "Error getting groups from staff overview", e)
                }
            }

            // Fallback 2: Check local Room DB groups
            val localGroups = try { studentDao.getAllGroups().first() } catch (e: Exception) { emptyList() }
            if (localGroups.isNotEmpty()) {
                val groupsList = localGroups.mapIndexed { idx, name -> TeacherGroup(idx + 1, name) }
                return@withContext listOf(
                    TeacherSubject(1, "Networks", groupsList),
                    TeacherSubject(2, "Информатика", groupsList),
                    TeacherSubject(3, "Программирование", groupsList)
                )
            }

            // Fallback 3: Return default demo subject & group
            listOf(
                TeacherSubject(1, "Networks", listOf(TeacherGroup(469, "TEST-GROUP-1")))
            )
        } catch (e: Exception) {
            Log.e("HTTP_REPO", "getTeacherSubjects error", e)
            listOf(
                TeacherSubject(1, "Networks", listOf(TeacherGroup(469, "TEST-GROUP-1")))
            )
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
                    if (stats.isNotEmpty()) {
                        Log.d("HTTP_REPO", "Parsed ${stats.size} students for group $groupId")
                        return@withContext stats
                    }
                }
            }

            // Fallback: If attendance group is empty (e.g. between open semesters), load students from group performance
            val perf = getTeacherGroupSubjectPerformance(groupId, subjectId, 2)
            if (perf.isNotEmpty()) {
                return@withContext perf.map { row ->
                    StudentAttendanceStats(
                        student_id = row.student_id,
                        student_name = row.student_name,
                        attendance_percent = if (row.total_sessions > 0) (row.attended_sessions.toDouble() * 100.0 / row.total_sessions) else 0.0,
                        attended_sessions = row.attended_sessions,
                        total_sessions = row.total_sessions
                    )
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

    override suspend fun getStudentGradesAll(semesterId: Int?): List<com.example.kotlinroomdatabase.model.StudentSubjectPerformance> = withContext(Dispatchers.IO) {
        try {
            val token = sharedPrefs.getString("auth_token", "") ?: ""
            if (token.isEmpty()) return@withContext emptyList()
            
            val url = if (semesterId != null && semesterId > 0) {
                "$BASE_URL/api/student/grades/all?semester_id=$semesterId"
            } else {
                "$BASE_URL/api/student/grades/all"
            }
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("Authorization", "Bearer $token")
                .build()

            val response = client.newCall(request).execute()
            val responseStr = response.body?.string() ?: ""
            if (response.isSuccessful) {
                val jsonResponse = JSONObject(responseStr)
                if (jsonResponse.optBoolean("ok")) {
                    val resultObjStr = jsonResponse.getJSONObject("result").toString()
                    val decoded = jsonSerializer.decodeFromString<com.example.kotlinroomdatabase.model.StudentAllGradesResponse>(resultObjStr)
                    return@withContext decoded.subjects
                }
            }
            emptyList()
        } catch (e: Exception) {
            Log.e("HTTP_REPO", "getStudentGradesAll error", e)
            emptyList()
        }
    }

    override suspend fun getStudentGradesBySubject(subjectId: Int): List<com.example.kotlinroomdatabase.model.StudentGradePoint> = withContext(Dispatchers.IO) {
        try {
            val token = sharedPrefs.getString("auth_token", "") ?: ""
            if (token.isEmpty()) return@withContext emptyList()
            
            val jsonRequest = JSONObject().apply { put("subject_id", subjectId) }
            val request = Request.Builder()
                .url("$BASE_URL/api/student/grades")
                .post(jsonRequest.toString().toRequestBody(JSON_TYPE))
                .addHeader("Authorization", "Bearer $token")
                .build()

            val response = client.newCall(request).execute()
            val responseStr = response.body?.string() ?: ""
            if (response.isSuccessful) {
                val jsonResponse = JSONObject(responseStr)
                if (jsonResponse.optBoolean("ok")) {
                    val items = jsonResponse.getJSONObject("result").getJSONArray("items").toString()
                    return@withContext jsonSerializer.decodeFromString<List<com.example.kotlinroomdatabase.model.StudentGradePoint>>(items)
                }
            }
            emptyList()
        } catch (e: Exception) {
            Log.e("HTTP_REPO", "getStudentGradesBySubject error", e)
            emptyList()
        }
    }

    override suspend fun getStudentPerformanceRadar(): List<com.example.kotlinroomdatabase.model.SubjectPerformancePoint> = withContext(Dispatchers.IO) {
        try {
            val token = sharedPrefs.getString("auth_token", "") ?: ""
            if (token.isEmpty()) return@withContext emptyList()
            
            val request = Request.Builder()
                .url("$BASE_URL/api/student/performance/radar")
                .get()
                .addHeader("Authorization", "Bearer $token")
                .build()

            val response = client.newCall(request).execute()
            val responseStr = response.body?.string() ?: ""
            if (response.isSuccessful) {
                val jsonResponse = JSONObject(responseStr)
                if (jsonResponse.optBoolean("ok")) {
                    val resultArr = jsonResponse.getJSONArray("result").toString()
                    return@withContext jsonSerializer.decodeFromString<List<com.example.kotlinroomdatabase.model.SubjectPerformancePoint>>(resultArr)
                }
            }
            emptyList()
        } catch (e: Exception) {
            Log.e("HTTP_REPO", "getStudentPerformanceRadar error", e)
            emptyList()
        }
    }

    override suspend fun getTeacherGroupSubjectPerformance(groupId: Int, subjectId: Int, semesterId: Int?): List<com.example.kotlinroomdatabase.model.GroupSubjectPerformanceRow> = withContext(Dispatchers.IO) {
        try {
            val token = sharedPrefs.getString("auth_token", "") ?: ""
            if (token.isEmpty()) return@withContext emptyList()
            
            val jsonRequest = JSONObject().apply { 
                put("group_id", groupId)
                put("subject_id", subjectId)
                if (semesterId != null && semesterId > 0) {
                    put("semester_id", semesterId)
                }
            }
            val request = Request.Builder()
                .url("$BASE_URL/api/teacher/group/performance")
                .post(jsonRequest.toString().toRequestBody(JSON_TYPE))
                .addHeader("Authorization", "Bearer $token")
                .build()

            val response = client.newCall(request).execute()
            val responseStr = response.body?.string() ?: ""
            if (response.isSuccessful) {
                val jsonResponse = JSONObject(responseStr)
                if (jsonResponse.optBoolean("ok")) {
                    val studentsArr = jsonResponse.getJSONObject("result").getJSONArray("students").toString()
                    return@withContext jsonSerializer.decodeFromString<List<com.example.kotlinroomdatabase.model.GroupSubjectPerformanceRow>>(studentsArr)
                }
            }
            emptyList()
        } catch (e: Exception) {
            Log.e("HTTP_REPO", "getTeacherGroupSubjectPerformance error", e)
            emptyList()
        }
    }

    override suspend fun getTeacherGradeItems(subjectId: Int, semesterId: Int?): List<com.example.kotlinroomdatabase.model.GradeItem> = withContext(Dispatchers.IO) {
        try {
            val token = sharedPrefs.getString("auth_token", "") ?: ""
            if (token.isEmpty()) return@withContext emptyList()
            
            val jsonRequest = JSONObject().apply { 
                put("subject_id", subjectId) 
                if (semesterId != null && semesterId > 0) {
                    put("semester_id", semesterId)
                }
            }
            val request = Request.Builder()
                .url("$BASE_URL/api/teacher/grades/items/list")
                .post(jsonRequest.toString().toRequestBody(JSON_TYPE))
                .addHeader("Authorization", "Bearer $token")
                .build()

            val response = client.newCall(request).execute()
            val responseStr = response.body?.string() ?: ""
            if (response.isSuccessful) {
                val jsonResponse = JSONObject(responseStr)
                if (jsonResponse.optBoolean("ok")) {
                    val resultObj = jsonResponse.optJSONObject("result")
                    if (resultObj != null) {
                        val itemsArr = resultObj.optJSONArray("items")?.toString() ?: "[]"
                        return@withContext jsonSerializer.decodeFromString<List<com.example.kotlinroomdatabase.model.GradeItem>>(itemsArr)
                    }
                }
            }
            emptyList()
        } catch (e: Exception) {
            Log.e("HTTP_REPO", "getTeacherGradeItems error", e)
            emptyList()
        }
    }

    override suspend fun getTeacherStudentGrades(studentId: Int, subjectId: Int, semesterId: Int?): com.example.kotlinroomdatabase.model.TeacherStudentGradesResponse? = withContext(Dispatchers.IO) {
        try {
            val token = sharedPrefs.getString("auth_token", "") ?: ""
            if (token.isEmpty()) return@withContext null
            
            val jsonRequest = JSONObject().apply { 
                put("student_id", studentId)
                put("subject_id", subjectId)
                if (semesterId != null && semesterId > 0) {
                    put("semester_id", semesterId)
                }
            }
            val request = Request.Builder()
                .url("$BASE_URL/api/teacher/grades/student")
                .post(jsonRequest.toString().toRequestBody(JSON_TYPE))
                .addHeader("Authorization", "Bearer $token")
                .build()

            val response = client.newCall(request).execute()
            val responseStr = response.body?.string() ?: ""
            if (response.isSuccessful) {
                val jsonResponse = JSONObject(responseStr)
                if (jsonResponse.optBoolean("ok")) {
                    val resultObj = jsonResponse.getJSONObject("result").toString()
                    return@withContext jsonSerializer.decodeFromString<com.example.kotlinroomdatabase.model.TeacherStudentGradesResponse>(resultObj)
                }
            }
            null
        } catch (e: Exception) {
            Log.e("HTTP_REPO", "getTeacherStudentGrades error", e)
            null
        }
    }

    override suspend fun createGradeItem(subjectId: Int, title: String, maxScore: Int, itemType: String, deadline: String?): Boolean = withContext(Dispatchers.IO) {
        try {
            val token = sharedPrefs.getString("auth_token", "") ?: ""
            if (token.isEmpty()) return@withContext false
            
            val jsonRequest = JSONObject().apply { 
                put("subject_id", subjectId)
                put("title", title)
                put("max_score", maxScore)
                put("item_type", itemType)
                if (deadline != null) put("deadline", deadline)
            }
            val request = Request.Builder()
                .url("$BASE_URL/api/teacher/grades/items")
                .post(jsonRequest.toString().toRequestBody(JSON_TYPE))
                .addHeader("Authorization", "Bearer $token")
                .build()

            val response = client.newCall(request).execute()
            val responseStr = response.body?.string() ?: ""
            if (response.isSuccessful) {
                val jsonResponse = JSONObject(responseStr)
                return@withContext jsonResponse.optBoolean("ok", false)
            }
            false
        } catch (e: Exception) {
            Log.e("HTTP_REPO", "createGradeItem error", e)
            false
        }
    }

    override suspend fun updateGradeItem(itemId: Int, title: String, maxScore: Int, itemType: String, deadline: String?): Boolean = withContext(Dispatchers.IO) {
        try {
            val token = sharedPrefs.getString("auth_token", "") ?: ""
            if (token.isEmpty()) return@withContext false

            val jsonRequest = JSONObject().apply {
                put("item_id", itemId)
                put("title", title)
                put("max_score", maxScore)
                put("item_type", itemType)
                if (deadline != null) put("deadline", deadline)
            }

            val request = Request.Builder()
                .url("$BASE_URL/api/teacher/grades/items/update")
                .post(jsonRequest.toString().toRequestBody(JSON_TYPE))
                .addHeader("Authorization", "Bearer $token")
                .build()

            val response = client.newCall(request).execute()
            val responseStr = response.body?.string() ?: ""
            if (response.isSuccessful) {
                val jsonResponse = JSONObject(responseStr)
                return@withContext jsonResponse.optBoolean("ok", false)
            }
            false
        } catch (e: Exception) {
            Log.e("HTTP_REPO", "updateGradeItem error", e)
            false
        }
    }

    override suspend fun setStudentGrade(studentId: Int, itemId: Int, score: Int, comment: String?): Boolean = withContext(Dispatchers.IO) {
        try {
            val token = sharedPrefs.getString("auth_token", "") ?: ""
            if (token.isEmpty()) {
                studentDao.insertOfflineGradeAction(com.example.kotlinroomdatabase.model.OfflineGradeAction(
                    studentId = studentId, itemId = itemId, score = score, comment = comment
                ))
                return@withContext true // Optimistic offline success
            }
            
            val jsonRequest = JSONObject().apply { 
                put("student_id", studentId)
                put("item_id", itemId)
                put("score", score)
                if (comment != null) put("comment", comment)
            }
            val request = Request.Builder()
                .url("$BASE_URL/api/teacher/grades")
                .post(jsonRequest.toString().toRequestBody(JSON_TYPE))
                .addHeader("Authorization", "Bearer $token")
                .build()

            val response = client.newCall(request).execute()
            val responseStr = response.body?.string() ?: ""
            if (response.isSuccessful) {
                val jsonResponse = JSONObject(responseStr)
                return@withContext jsonResponse.optBoolean("ok", false)
            } else {
                studentDao.insertOfflineGradeAction(com.example.kotlinroomdatabase.model.OfflineGradeAction(
                    studentId = studentId, itemId = itemId, score = score, comment = comment
                ))
                return@withContext true // Saved offline
            }
        } catch (e: Exception) {
            Log.e("HTTP_REPO", "setStudentGrade error", e)
            studentDao.insertOfflineGradeAction(com.example.kotlinroomdatabase.model.OfflineGradeAction(
                studentId = studentId, itemId = itemId, score = score, comment = comment
            ))
            return@withContext true // Saved offline
        }
    }

    override suspend fun createRewardPunishment(studentId: Int, subjectId: Int, score: Int, reason: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val token = sharedPrefs.getString("auth_token", "") ?: ""
            if (token.isEmpty()) return@withContext false

            val jsonRequest = JSONObject().apply {
                put("student_id", studentId)
                put("subject_id", subjectId)
                put("score", score)
                put("reason", reason)
            }

            val request = Request.Builder()
                .url("$BASE_URL/api/teacher/grades/rewards")
                .post(jsonRequest.toString().toRequestBody(JSON_TYPE))
                .addHeader("Authorization", "Bearer $token")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val jsonResponse = JSONObject(response.body?.string() ?: "")
                return@withContext jsonResponse.optBoolean("ok", false)
            }
            return@withContext false
        } catch (e: java.lang.Exception) {
            Log.e("HTTP_REPO", "createRewardPunishment error", e)
            return@withContext false
        }
    }

    override suspend fun syncOfflineGrades(): Boolean = withContext(Dispatchers.IO) {
        try {
            val token = sharedPrefs.getString("auth_token", "") ?: ""
            if (token.isEmpty()) return@withContext false

            val unsynced = studentDao.getUnsyncedGradeActions()
            if (unsynced.isEmpty()) return@withContext true

            var allSynced = true
            for (action in unsynced) {
                val jsonRequest = JSONObject().apply { 
                    put("student_id", action.studentId)
                    put("item_id", action.itemId)
                    put("score", action.score)
                    if (action.comment != null) put("comment", action.comment)
                }
                val request = Request.Builder()
                    .url("$BASE_URL/api/teacher/grades")
                    .post(jsonRequest.toString().toRequestBody(JSON_TYPE))
                    .addHeader("Authorization", "Bearer $token")
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful && JSONObject(response.body?.string() ?: "{}").optBoolean("ok", false)) {
                    studentDao.markGradeActionSynced(action.id)
                } else {
                    allSynced = false
                }
            }
            return@withContext allSynced
        } catch (e: Exception) {
            Log.e("HTTP_REPO", "syncOfflineGrades error", e)
            return@withContext false
        }
    }

    override suspend fun forgotPassword(identity: String): GenericResult<String> = withContext(Dispatchers.IO) {
        try {
            val jsonRequest = JSONObject().apply { put("identity", identity) }
            val request = Request.Builder().url("$BASE_URL/api/auth/forgot-password")
                .post(jsonRequest.toString().toRequestBody(JSON_TYPE)).build()
            val response = client.newCall(request).execute()
            val respStr = response.body?.string() ?: ""
            if (response.isSuccessful) GenericResult.Success("OK")
            else GenericResult.Error(JSONObject(respStr).optString("error", "Error ${response.code}"))
        } catch (e: Exception) { GenericResult.Error("Network error") }
    }

    override suspend fun resetPassword(token: String, newPassword: String): GenericResult<String> = withContext(Dispatchers.IO) {
        try {
            val jsonRequest = JSONObject().apply { put("token", token); put("new_password", newPassword) }
            val request = Request.Builder().url("$BASE_URL/api/auth/reset-password")
                .post(jsonRequest.toString().toRequestBody(JSON_TYPE)).build()
            val response = client.newCall(request).execute()
            val respStr = response.body?.string() ?: ""
            if (response.isSuccessful) GenericResult.Success("OK")
            else GenericResult.Error(JSONObject(respStr).optString("error", "Error ${response.code}"))
        } catch (e: Exception) { GenericResult.Error("Network error") }
    }

    @OptIn(InternalSerializationApi::class)
    override suspend fun registerByInvite(inviteCode: String, login: String, passwordRaw: String): LoginResult = withContext(Dispatchers.IO) {
        try {
            val jsonRequest = JSONObject().apply {
                put("invite_code", inviteCode.trim())
                put("login", login.trim())
                put("password", passwordRaw)
            }
            val request = Request.Builder().url("$BASE_URL/register/by-invite")
                .post(jsonRequest.toString().toRequestBody(JSON_TYPE)).build()
            val response = client.newCall(request).execute()
            val responseStr = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                val errObj = try { JSONObject(responseStr) } catch (_: Exception) { null }
                val rawErr = errObj?.optString("error") ?: "Registration Error"
                return@withContext LoginResult.Error(com.example.kotlinroomdatabase.util.ApiErrorMapper.mapErrorMessage(rawErr))
            }
            
            val jsonResponse = JSONObject(responseStr)
            if (jsonResponse.optBoolean("ok")) {
                val result = jsonResponse.getJSONObject("result")
                val token = result.optString("token")
                saveToken(token)

                val userIdStr = result.optString("user_id", result.optString("user_ID", "0"))
                val displayName = result.optString("student_name").takeIf { it.isNotBlank() }
                    ?: result.optString("teacher_name").takeIf { it.isNotBlank() }
                    ?: result.optString("name").takeIf { it.isNotBlank() }
                    ?: result.optString("login", login)
                val groupName = result.optString("group_name").takeIf { it.isNotBlank() }
                    ?: result.optString("group", "")
                val role = result.optString("role", "student")

                sharedPrefs.edit().apply {
                    putString("student_name", displayName)
                    putString("user_role", role)
                    putString("group_name", groupName)
                }.apply()

                val s = Student(
                    id = userIdStr.hashCode(),
                    studentName = displayName,
                    studentGroup = groupName,
                    studentNFC = "",
                    attendance = false,
                    role = role
                )
                studentDao.insertStudent(s)
                LoginResult.Success(s)
            } else {
                LoginResult.Error("Не удалось зарегистрироваться")
            }
        } catch (e: Exception) {
            LoginResult.Error(com.example.kotlinroomdatabase.util.ApiErrorMapper.mapError(e))
        }
    }

    override suspend fun getStaffOverview(): GenericResult<String> = withContext(Dispatchers.IO) {
        try {
            val t = sharedPrefs.getString("auth_token", "") ?: ""
            val request = Request.Builder().url("$BASE_URL/api/staff/overview")
                .addHeader("Authorization", "Bearer $t").build()
            val response = client.newCall(request).execute()
            val respStr = response.body?.string() ?: ""
            if (response.isSuccessful) GenericResult.Success(respStr)
            else GenericResult.Error("Error")
        } catch (e: Exception) { GenericResult.Error("Network error") }
    }

    override suspend fun teacherMarkAttendance(lessonId: Int, studentId: Int, status: String): GenericResult<String> = withContext(Dispatchers.IO) {
        try {
            val t = sharedPrefs.getString("auth_token", "") ?: ""
            val jsonRequest = JSONObject().apply { put("lesson_id", lessonId); put("student_id", studentId); put("status", status) }
            val request = Request.Builder().url("$BASE_URL/api/teacher/attendance/mark")
                .post(jsonRequest.toString().toRequestBody(JSON_TYPE))
                .addHeader("Authorization", "Bearer $t").build()
            val response = client.newCall(request).execute()
            val respStr = response.body?.string() ?: ""
            if (response.isSuccessful) GenericResult.Success("OK")
            else GenericResult.Error(JSONObject(respStr).optString("error", "Error"))
        } catch (e: Exception) { GenericResult.Error("Network error") }
    }

    override suspend fun getSessionMarkedCount(lessonId: Int): GenericResult<Int> = withContext(Dispatchers.IO) {
        try {
            val t = sharedPrefs.getString("auth_token", "") ?: ""
            val request = Request.Builder().url("$BASE_URL/api/teacher/attendance/session/marked-count?lesson_id=$lessonId")
                .addHeader("Authorization", "Bearer $t").build()
            val response = client.newCall(request).execute()
            val respStr = response.body?.string() ?: ""
            if (response.isSuccessful) GenericResult.Success(JSONObject(respStr).getJSONObject("result").optInt("marked_count", 0))
            else GenericResult.Error("Error")
        } catch (e: Exception) { GenericResult.Error("Network error") }
    }

    override suspend fun getSessionTimer(lessonId: Int): GenericResult<Int> = withContext(Dispatchers.IO) {
        try {
            val t = sharedPrefs.getString("auth_token", "") ?: ""
            val request = Request.Builder().url("$BASE_URL/api/teacher/attendance/session/timer?lesson_id=$lessonId")
                .addHeader("Authorization", "Bearer $t").build()
            val response = client.newCall(request).execute()
            val respStr = response.body?.string() ?: ""
            if (response.isSuccessful) GenericResult.Success(JSONObject(respStr).getJSONObject("result").optInt("remaining_seconds", 0))
            else GenericResult.Error("Error")
        } catch (e: Exception) { GenericResult.Error("Network error") }
    }

    override suspend fun getStudentScheduleDay(date: String?): GenericResult<String> = withContext(Dispatchers.IO) {
        try {
            val t = sharedPrefs.getString("auth_token", "") ?: ""
            val url = if (date == null) "$BASE_URL/api/student/schedule/day" else "$BASE_URL/api/student/schedule/day?date=$date"
            val request = Request.Builder().url(url)
                .addHeader("Authorization", "Bearer $t").build()
            val response = client.newCall(request).execute()
            val respStr = response.body?.string() ?: ""
            if (response.isSuccessful) GenericResult.Success(respStr)
            else GenericResult.Error("Error")
        } catch (e: Exception) { GenericResult.Error("Network error") }
    }

    override suspend fun getScheduleForDay(date: String): GenericResult<com.example.kotlinroomdatabase.model.DayScheduleResult> = withContext(Dispatchers.IO) {
        try {
            val token = sharedPrefs.getString("auth_token", null)?.takeIf { it.isNotBlank() } ?: return@withContext GenericResult.Error("Not authorized")
            val role = sharedPrefs.getString("user_role", "student") ?: "student"
            val endpoint = if (role == "teacher" || role == "admin") {
                "$BASE_URL/api/teacher/schedule/day?date=$date"
            } else {
                "$BASE_URL/api/student/schedule/day?date=$date"
            }

            val request = Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseStr = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext GenericResult.Error("HTTP ${response.code}: $responseStr")
            }

            val json = JSONObject(responseStr)
            if (!json.optBoolean("ok", false)) {
                return@withContext GenericResult.Error(json.optString("error", "Failed to load schedule"))
            }

            val resultObj = json.getJSONObject("result")
            val lessonsArray = resultObj.optJSONArray("lessons") ?: org.json.JSONArray()
            val lessonsList = mutableListOf<com.example.kotlinroomdatabase.model.LessonScheduleItem>()

            for (i in 0 until lessonsArray.length()) {
                val item = lessonsArray.getJSONObject(i)
                lessonsList.add(
                    com.example.kotlinroomdatabase.model.LessonScheduleItem(
                        lesson_num = item.optInt("lesson_num", i + 1),
                        start_time = item.optString("start_time", ""),
                        end_time = item.optString("end_time", ""),
                        subject_id = item.optInt("subject_id", 0),
                        subject_name = item.optString("subject_name", ""),
                        teacher_name = if (item.has("teacher_name")) item.optString("teacher_name") else null,
                        group_name = if (item.has("group_name")) item.optString("group_name") else null,
                        group_id = if (item.has("group_id")) item.optInt("group_id") else null,
                        lesson_type = item.optString("lesson_type", "Практика"),
                        room_info = item.optString("room_info", ""),
                        subgroup = item.optString("subgroup", "")
                    )
                )
            }

            val scheduleResult = com.example.kotlinroomdatabase.model.DayScheduleResult(
                date = resultObj.optString("date", date),
                weekday = resultObj.optString("weekday", ""),
                day_idx = resultObj.optInt("day_idx", 0),
                week_type = resultObj.optInt("week_type", 1),
                teacher_id = if (resultObj.has("teacher_id")) resultObj.optInt("teacher_id") else null,
                lessons = lessonsList
            )

            // Schedule background lesson reminder alarms for this day
            com.example.kotlinroomdatabase.reminders.LessonReminderScheduler.scheduleAlarmsForDay(context, scheduleResult)

            GenericResult.Success(scheduleResult)
        } catch (e: Exception) {
            Log.e("HTTP_REPO", "getScheduleForDay error", e)
            GenericResult.Error("Network error: ${e.localizedMessage}")
        }
    }

    override suspend fun updateUserEmail(email: String): GenericResult<String> = withContext(Dispatchers.IO) {
        try {
            val t = sharedPrefs.getString("auth_token", "") ?: ""
            val jsonRequest = JSONObject().apply { put("email", email) }
            val request = Request.Builder().url("$BASE_URL/api/user/email")
                .post(jsonRequest.toString().toRequestBody(JSON_TYPE))
                .addHeader("Authorization", "Bearer $t").build()
            val response = client.newCall(request).execute()
            val respStr = response.body?.string() ?: ""
            if (response.isSuccessful) GenericResult.Success("OK")
            else GenericResult.Error("Error")
        } catch (e: Exception) { GenericResult.Error("Network error") }
    }

    override suspend fun getUserAgreementCurrent(): GenericResult<UserAgreementStatus> = withContext(Dispatchers.IO) {
        try {
            val t = sharedPrefs.getString("auth_token", "") ?: ""
            if (t.isEmpty()) return@withContext GenericResult.Error("No token")
            val request = Request.Builder().url("$BASE_URL/api/user/agreements/current")
                .addHeader("Authorization", "Bearer $t").build()
            val response = client.newCall(request).execute()
            val respStr = response.body?.string() ?: ""
            if (response.isSuccessful) {
                val jsonObj = JSONObject(respStr)
                if (jsonObj.optBoolean("ok")) {
                    val resultObjStr = jsonObj.getJSONObject("result").toString()
                    val status = jsonSerializer.decodeFromString<UserAgreementStatus>(resultObjStr)
                    return@withContext GenericResult.Success(status)
                }
            }
            GenericResult.Error(try { JSONObject(respStr).optString("error", "Error ${response.code}") } catch(e: Exception) { "Error ${response.code}" })
        } catch (e: Exception) { GenericResult.Error("Network error: ${e.message}") }
    }

    override suspend fun setUserAgreementDecision(version: String, decision: String): GenericResult<Boolean> = withContext(Dispatchers.IO) {
        try {
            val t = sharedPrefs.getString("auth_token", "") ?: ""
            if (t.isEmpty()) return@withContext GenericResult.Error("No token")
            val jsonRequest = JSONObject().apply {
                put("agreement", "user_agreement")
                put("version", version)
                put("decision", decision)
            }
            val request = Request.Builder().url("$BASE_URL/api/user/agreements/decision")
                .post(jsonRequest.toString().toRequestBody(JSON_TYPE))
                .addHeader("Authorization", "Bearer $t").build()
            val response = client.newCall(request).execute()
            val respStr = response.body?.string() ?: ""
            if (response.isSuccessful && JSONObject(respStr).optBoolean("ok")) {
                GenericResult.Success(true)
            } else {
                GenericResult.Error(try { JSONObject(respStr).optString("error", "Error ${response.code}") } catch(e: Exception) { "Error ${response.code}" })
            }
        } catch (e: Exception) { GenericResult.Error("Network error: ${e.message}") }
    }

    override suspend fun registerDeviceToken(token: String, platform: String): GenericResult<Boolean> = withContext(Dispatchers.IO) {
        try {
            val t = sharedPrefs.getString("auth_token", "") ?: ""
            if (t.isEmpty()) return@withContext GenericResult.Error("No token")
            val jsonRequest = JSONObject().apply {
                put("device_token", token)
                put("platform", platform)
            }
            val request = Request.Builder().url("$BASE_URL/api/user/device-token")
                .post(jsonRequest.toString().toRequestBody(JSON_TYPE))
                .addHeader("Authorization", "Bearer $t").build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) GenericResult.Success(true)
            else GenericResult.Error("Failed to register token")
        } catch (e: Exception) { GenericResult.Error("Network error") }
    }

    override suspend fun deleteDeviceToken(token: String): GenericResult<Boolean> = withContext(Dispatchers.IO) {
        try {
            val t = sharedPrefs.getString("auth_token", "") ?: ""
            if (t.isEmpty()) return@withContext GenericResult.Error("No token")
            val jsonRequest = JSONObject().apply { put("device_token", token) }
            val request = Request.Builder().url("$BASE_URL/api/user/device-token")
                .delete(jsonRequest.toString().toRequestBody(JSON_TYPE))
                .addHeader("Authorization", "Bearer $t").build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) GenericResult.Success(true)
            else GenericResult.Error("Failed to delete token")
        } catch (e: Exception) { GenericResult.Error("Network error") }
    }

    override suspend fun getUserNotifications(): GenericResult<List<UserNotification>> = withContext(Dispatchers.IO) {
        try {
            val t = sharedPrefs.getString("auth_token", "") ?: ""
            if (t.isEmpty()) return@withContext GenericResult.Error("No token")
            val request = Request.Builder().url("$BASE_URL/api/user/notifications")
                .addHeader("Authorization", "Bearer $t").build()
            val response = client.newCall(request).execute()
            val respStr = response.body?.string() ?: ""
            if (response.isSuccessful) {
                val jsonObj = JSONObject(respStr)
                if (jsonObj.optBoolean("ok")) {
                    val resultObj = jsonObj.getJSONObject("result")
                    val itemsArr = (resultObj.optJSONArray("items") ?: resultObj.optJSONArray("notifications"))?.toString() ?: "[]"
                    val list = jsonSerializer.decodeFromString<List<UserNotification>>(itemsArr)

                    // Trigger local system notification for any unread 2FA code or system update
                    list.filter { !it.is_read }.forEach { notification ->
                        val totpCode = if (notification.message.contains("code is:")) {
                            notification.message.substringAfter("code is:").trim()
                        } else null

                        com.example.kotlinroomdatabase.util.LocalNotificationHelper.showSystemNotificationOnce(
                            context = this@StudentRepositoryHTTPS.context,
                            notificationId = notification.realId.toInt(),
                            title = notification.title,
                            message = notification.message,
                            totpCode = totpCode
                        )
                    }

                    return@withContext GenericResult.Success(list)
                }
            }
            GenericResult.Error("Failed to load notifications")
        } catch (e: Exception) { GenericResult.Error("Network error") }
    }

    override suspend fun getUnreadNotificationsCount(): GenericResult<Int> = withContext(Dispatchers.IO) {
        try {
            val t = sharedPrefs.getString("auth_token", "") ?: ""
            if (t.isEmpty()) return@withContext GenericResult.Error("No token")
            val request = Request.Builder().url("$BASE_URL/api/user/notifications/unread-count")
                .addHeader("Authorization", "Bearer $t").build()
            val response = client.newCall(request).execute()
            val respStr = response.body?.string() ?: ""
            if (response.isSuccessful) {
                val jsonObj = JSONObject(respStr)
                if (jsonObj.optBoolean("ok")) {
                    val count = jsonObj.getJSONObject("result").optInt("unread_count", 0)
                    return@withContext GenericResult.Success(count)
                }
            }
            GenericResult.Error("Error loading count")
        } catch (e: Exception) { GenericResult.Error("Network error") }
    }

    override suspend fun markNotificationRead(notificationId: Long): GenericResult<Boolean> = withContext(Dispatchers.IO) {
        try {
            val t = sharedPrefs.getString("auth_token", "") ?: ""
            if (t.isEmpty()) return@withContext GenericResult.Error("No token")
            val request = Request.Builder().url("$BASE_URL/api/user/notifications/$notificationId/read")
                .patch("{}".toRequestBody(JSON_TYPE))
                .addHeader("Authorization", "Bearer $t").build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) GenericResult.Success(true)
            else GenericResult.Error("Error marking notification")
        } catch (e: Exception) { GenericResult.Error("Network error") }
    }

    override suspend fun deleteNotification(notificationId: Long): GenericResult<Boolean> = withContext(Dispatchers.IO) {
        try {
            val t = sharedPrefs.getString("auth_token", "") ?: ""
            if (t.isEmpty()) return@withContext GenericResult.Error("No token")
            val request = Request.Builder().url("$BASE_URL/api/user/notifications/$notificationId")
                .delete()
                .addHeader("Authorization", "Bearer $t").build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) GenericResult.Success(true)
            else GenericResult.Error("Error deleting notification")
        } catch (e: Exception) { GenericResult.Error("Network error") }
    }

    override suspend fun markAllNotificationsRead(): GenericResult<Boolean> = withContext(Dispatchers.IO) {
        try {
            val t = sharedPrefs.getString("auth_token", "") ?: ""
            if (t.isEmpty()) return@withContext GenericResult.Error("No token")
            val request = Request.Builder().url("$BASE_URL/api/user/notifications/read-all")
                .patch("{}".toRequestBody(JSON_TYPE))
                .addHeader("Authorization", "Bearer $t").build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) GenericResult.Success(true)
            else GenericResult.Error("Error marking all read")
        } catch (e: Exception) { GenericResult.Error("Network error") }
    }

    override suspend fun getSemesters(): GenericResult<List<SemesterInfo>> = withContext(Dispatchers.IO) {
        try {
            val t = sharedPrefs.getString("auth_token", "") ?: ""
            val request = Request.Builder().url("$BASE_URL/api/semesters")
                .addHeader("Authorization", "Bearer $t").build()
            val response = client.newCall(request).execute()
            val respStr = response.body?.string() ?: ""
            if (response.isSuccessful) {
                val jsonObj = JSONObject(respStr)
                if (jsonObj.optBoolean("ok")) {
                    val resObj = jsonObj.optJSONObject("result")
                    val itemsArr = (resObj?.optJSONArray("items") ?: resObj?.optJSONArray("semesters") ?: jsonObj.optJSONArray("result"))?.toString() ?: "[]"
                    val list = jsonSerializer.decodeFromString<List<SemesterInfo>>(itemsArr)
                    return@withContext GenericResult.Success(list)
                }
            }
            GenericResult.Error("Failed to fetch semesters")
        } catch (e: Exception) { GenericResult.Error("Network error") }
    }

    override suspend fun getCurrentSemester(): GenericResult<SemesterInfo> = withContext(Dispatchers.IO) {
        try {
            val t = sharedPrefs.getString("auth_token", "") ?: ""
            val request = Request.Builder().url("$BASE_URL/api/semesters/current")
                .addHeader("Authorization", "Bearer $t").build()
            val response = client.newCall(request).execute()
            val respStr = response.body?.string() ?: ""
            if (response.isSuccessful) {
                val jsonObj = JSONObject(respStr)
                if (jsonObj.optBoolean("ok")) {
                    val semStr = jsonObj.getJSONObject("result").toString()
                    val info = jsonSerializer.decodeFromString<SemesterInfo>(semStr)
                    return@withContext GenericResult.Success(info)
                } else {
                    val err = jsonObj.optString("error", "Failed to fetch current semester")
                    return@withContext GenericResult.Error(err)
                }
            }
            GenericResult.Error("Failed to fetch current semester (${response.code})")
        } catch (e: Exception) { GenericResult.Error("Network error") }
    }

    override suspend fun downloadPerformanceReport(format: String, semesterId: Int?, outputFile: java.io.File): GenericResult<java.io.File> = withContext(Dispatchers.IO) {
        try {
            val t = sharedPrefs.getString("auth_token", "") ?: ""
            if (t.isEmpty()) return@withContext GenericResult.Error("No token")
            
            val ext = if (format.lowercase().contains("pdf")) "pdf" else "xlsx"
            var url = "$BASE_URL/api/staff/reports/performance.$ext"
            if (semesterId != null && semesterId > 0) {
                url += "?semester_id=$semesterId"
            }
            
            val request = Request.Builder().url(url)
                .addHeader("Authorization", "Bearer $t").build()
                
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext GenericResult.Error("Server returned code ${response.code}")
            }
            
            val bodyBytes = response.body?.bytes()
            if (bodyBytes == null || bodyBytes.isEmpty()) {
                return@withContext GenericResult.Error("Empty report body")
            }
            
            outputFile.parentFile?.mkdirs()
            outputFile.writeBytes(bodyBytes)
            GenericResult.Success(outputFile)
        } catch (e: Exception) {
            GenericResult.Error("Failed to download report: ${e.message}")
        }
    }

    override suspend fun deleteTeacherGrade(gradeId: Long): GenericResult<Boolean> = withContext(Dispatchers.IO) {
        try {
            val t = sharedPrefs.getString("auth_token", "") ?: ""
            if (t.isEmpty()) return@withContext GenericResult.Error("No token")
            val request = Request.Builder()
                .url("$BASE_URL/api/teacher/grades/$gradeId")
                .delete()
                .addHeader("Authorization", "Bearer $t")
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val respStr = response.body?.string() ?: ""
                val jsonObj = org.json.JSONObject(respStr)
                if (jsonObj.optBoolean("ok")) {
                    return@withContext GenericResult.Success(true)
                }
            }
            GenericResult.Error("Failed to delete grade")
        } catch (e: Exception) {
            Log.e("HTTP_REPO", "deleteTeacherGrade failed", e)
            GenericResult.Error("Network error")
        }
    }

    override suspend fun deleteTeacherGradeItem(itemId: Long): GenericResult<Boolean> = withContext(Dispatchers.IO) {
        try {
            val t = sharedPrefs.getString("auth_token", "") ?: ""
            if (t.isEmpty()) return@withContext GenericResult.Error("No token")
            val request = Request.Builder()
                .url("$BASE_URL/api/teacher/grades/items/$itemId")
                .delete()
                .addHeader("Authorization", "Bearer $t")
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val respStr = response.body?.string() ?: ""
                val jsonObj = org.json.JSONObject(respStr)
                if (jsonObj.optBoolean("ok")) {
                    return@withContext GenericResult.Success(true)
                }
            }
            GenericResult.Error("Failed to delete grade item")
        } catch (e: Exception) {
            Log.e("HTTP_REPO", "deleteTeacherGradeItem failed", e)
            GenericResult.Error("Network error")
        }
    }

    override suspend fun getActiveStudentLesson(): GenericResult<com.example.kotlinroomdatabase.model.ActiveStudentLessonInfo> = withContext(Dispatchers.IO) {
        try {
            val token = sharedPrefs.getString("auth_token", "") ?: ""
            if (token.isBlank()) return@withContext GenericResult.Error("No token")

            val request = Request.Builder()
                .url("$BASE_URL/api/student/attendance/active-session")
                .get()
                .addHeader("Authorization", "Bearer $token")
                .build()

            val response = client.newCall(request).execute()
            val responseStr = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val json = JSONObject(responseStr)
                if (json.optBoolean("ok")) {
                    val res = json.optJSONObject("result")
                    if (res != null) {
                        val info = com.example.kotlinroomdatabase.model.ActiveStudentLessonInfo(
                            is_active = res.optBoolean("is_active", false),
                            session_id = res.optInt("session_id", 0),
                            subject_id = res.optInt("subject_id", 0),
                            lesson_name = res.optString("lesson_name", res.optString("subject_name", "")),
                            subject_name = res.optString("subject_name", ""),
                            expires_at = res.optString("expires_at", ""),
                            marked_at = res.optString("marked_at", "")
                        )
                        return@withContext GenericResult.Success(info)
                    }
                }
            }
            GenericResult.Success(com.example.kotlinroomdatabase.model.ActiveStudentLessonInfo(is_active = false))
        } catch (e: Exception) {
            GenericResult.Error("Network error: ${e.message}")
        }
    }

    override suspend fun getTeacherActiveSession(): GenericResult<com.example.kotlinroomdatabase.model.ActiveSessionInfo> = withContext(Dispatchers.IO) {
        try {
            val token = sharedPrefs.getString("auth_token", "") ?: ""
            if (token.isBlank()) return@withContext GenericResult.Error("No token")

            val request = Request.Builder()
                .url("$BASE_URL/api/teacher/attendance/session/active")
                .get()
                .addHeader("Authorization", "Bearer $token")
                .build()

            val response = client.newCall(request).execute()
            val responseStr = response.body?.string() ?: ""
            Log.d("HTTP_REPO", "getTeacherActiveSession response: $responseStr")

            if (response.isSuccessful) {
                val json = JSONObject(responseStr)
                if (json.optBoolean("ok")) {
                    val result = json.getJSONObject("result")
                    if (result.optBoolean("active")) {
                        val session = result.getJSONObject("session")
                        val sessionId = session.optInt("id", session.optInt("lesson_id", -1))
                        val subjectId = session.optInt("subject_id", 0)
                        val createdAt = session.optString("created_at", "")
                        val expiresAt = session.optString("expires_at", "")
                        val remainingSeconds = result.optInt("remaining_seconds", session.optInt("remaining_seconds", 0))
                        val markedCount = session.optInt("marked_count", 0)
                        val rosterSize = session.optInt("roster_size", 0)

                        val subjects = getTeacherSubjects()
                        val matchingSubject = subjects.firstOrNull { it.subject_id == subjectId }
                        val subjectName = matchingSubject?.subject_name ?: session.optString("lesson_name", "Занятие")
                        val groupNames = matchingSubject?.groups?.map { it.name } ?: emptyList()
                        val groupIds = matchingSubject?.groups?.map { it.id } ?: emptyList()

                        return@withContext GenericResult.Success(
                            com.example.kotlinroomdatabase.model.ActiveSessionInfo(
                                id = sessionId,
                                lessonId = sessionId,
                                subjectId = subjectId,
                                subjectName = subjectName,
                                groupIds = groupIds,
                                groupNames = groupNames,
                                createdAt = createdAt,
                                expiresAt = expiresAt,
                                remainingSeconds = remainingSeconds,
                                markedCount = markedCount,
                                rosterSize = rosterSize,
                                isActive = true
                            )
                        )
                    }
                }
            }
            GenericResult.Success(com.example.kotlinroomdatabase.model.ActiveSessionInfo(isActive = false))
        } catch (e: Exception) {
            Log.e("HTTP_REPO", "getTeacherActiveSession error", e)
            GenericResult.Error("Network error: ${e.message}")
        }
    }

    override suspend fun getAttendanceSessionRoster(lessonId: Int): GenericResult<com.example.kotlinroomdatabase.model.TeacherAttendanceRosterResult> = withContext(Dispatchers.IO) {
        try {
            val token = sharedPrefs.getString("auth_token", "") ?: ""
            if (token.isBlank()) return@withContext GenericResult.Error("Отсутствует токен авторизации")

            val request = Request.Builder()
                .url("$BASE_URL/api/teaching/attendance/session/roster?lesson_id=$lessonId")
                .get()
                .header("Authorization", "Bearer $token")
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val jsonObj = JSONObject(responseBody)
                if (jsonObj.optBoolean("ok")) {
                    val res = jsonObj.getJSONObject("result")
                    val studentsArr = res.optJSONArray("students")
                    val studentsList = mutableListOf<com.example.kotlinroomdatabase.model.AttendanceRosterStudent>()
                    if (studentsArr != null) {
                        for (i in 0 until studentsArr.length()) {
                            val s = studentsArr.getJSONObject(i)
                            studentsList.add(com.example.kotlinroomdatabase.model.AttendanceRosterStudent(
                                student_id = s.optInt("student_id"),
                                student_name = s.optString("student_name"),
                                group_id = s.optInt("group_id"),
                                group_name = s.optString("group_name"),
                                status = s.optString("status", "absent"),
                                marked_by = s.optString("marked_by", ""),
                                marked_at = if (s.has("marked_at") && !s.isNull("marked_at")) s.optString("marked_at") else null,
                                is_fraud = s.optBoolean("is_fraud", false),
                                fraud_reason = s.optString("fraud_reason", "")
                            ))
                        }
                    }
                    val rosterResult = com.example.kotlinroomdatabase.model.TeacherAttendanceRosterResult(
                        lesson_id = res.optInt("lesson_id"),
                        lesson_name = res.optString("lesson_name"),
                        subject_id = res.optInt("subject_id"),
                        server_time = res.optString("server_time"),
                        timezone = res.optString("timezone", "Asia/Novosibirsk"),
                        roster_size = res.optInt("roster_size", studentsList.size),
                        marked_count = res.optInt("marked_count", 0),
                        attendance_percent = res.optDouble("attendance_percent", 0.0),
                        students = studentsList
                    )
                    GenericResult.Success(rosterResult)
                } else {
                    GenericResult.Error(com.example.kotlinroomdatabase.util.ApiErrorMapper.mapErrorMessage(jsonObj.optString("error")))
                }
            } else {
                GenericResult.Error(com.example.kotlinroomdatabase.util.ApiErrorMapper.mapHttpStatus(response.code, responseBody))
            }
        } catch (e: Exception) {
            GenericResult.Error(com.example.kotlinroomdatabase.util.ApiErrorMapper.mapError(e))
        }
    }

    override suspend fun switchRole(role: String): GenericResult<com.example.kotlinroomdatabase.model.SwitchRoleResult> = withContext(Dispatchers.IO) {
        try {
            val token = sharedPrefs.getString("auth_token", "") ?: ""
            if (token.isBlank()) return@withContext GenericResult.Error("Отсутствует токен авторизации")

            val payload = JSONObject().apply {
                put("role", role.trim().lowercase())
            }
            val request = Request.Builder()
                .url("$BASE_URL/api/auth/switch-role")
                .post(payload.toString().toRequestBody(JSON_TYPE))
                .header("Authorization", "Bearer $token")
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val jsonObj = JSONObject(responseBody)
                if (jsonObj.optBoolean("ok")) {
                    val res = jsonObj.getJSONObject("result")
                    val newToken = res.optString("token")
                    val returnedRole = res.optString("active_role", res.optString("role", role)).trim().lowercase()
                    val primaryRole = res.optString("primary_role", "")
                    val rolesArr = res.optJSONArray("roles")
                    val rolesList = mutableListOf<String>()
                    if (rolesArr != null) {
                        for (i in 0 until rolesArr.length()) {
                            rolesList.add(rolesArr.getString(i))
                        }
                    }

                    if (newToken.isNotBlank()) {
                        sharedPrefs.edit().putString("auth_token", newToken).apply()
                    }
                    val studentPrefs = context.getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
                    studentPrefs.edit().apply {
                        putString("user_role", returnedRole)
                        putString("primary_role", primaryRole)
                        if (rolesList.isNotEmpty()) putString("user_roles", rolesList.joinToString(","))
                    }.apply()

                    clearLocalRoomData()
                    syncAllStudents()

                    GenericResult.Success(com.example.kotlinroomdatabase.model.SwitchRoleResult(
                        token = newToken,
                        role = returnedRole,
                        active_role = returnedRole,
                        primary_role = primaryRole,
                        roles = rolesList,
                        expires_at = res.optString("expires_at")
                    ))
                } else {
                    GenericResult.Error(com.example.kotlinroomdatabase.util.ApiErrorMapper.mapErrorMessage(jsonObj.optString("error"), "Не удалось сменить роль"))
                }
            } else {
                GenericResult.Error(com.example.kotlinroomdatabase.util.ApiErrorMapper.mapHttpStatus(response.code, responseBody))
            }
        } catch (e: Exception) {
            GenericResult.Error(com.example.kotlinroomdatabase.util.ApiErrorMapper.mapError(e))
        }
    }

    override suspend fun getFullUserProfile(): GenericResult<com.example.kotlinroomdatabase.model.UserProfile> = withContext(Dispatchers.IO) {
        try {
            val token = sharedPrefs.getString("auth_token", "") ?: ""
            if (token.isBlank()) return@withContext GenericResult.Error("Отсутствует токен")

            val request = Request.Builder()
                .url("$BASE_URL/profile")
                .get()
                .header("Authorization", "Bearer $token")
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val jsonObj = JSONObject(responseBody)
                if (jsonObj.optBoolean("ok")) {
                    val res = jsonObj.getJSONObject("result")
                    val rolesArr = res.optJSONArray("roles")
                    val rolesList = mutableListOf<String>()
                    if (rolesArr != null) {
                        for (i in 0 until rolesArr.length()) {
                            rolesList.add(rolesArr.getString(i))
                        }
                    }
                    val profile = com.example.kotlinroomdatabase.model.UserProfile(
                        user_id = res.optInt("user_id", res.optInt("user_ID", 0)),
                        login = res.optString("login", ""),
                        name = res.optString("name", ""),
                        student_name = res.optString("student_name", ""),
                        teacher_name = res.optString("teacher_name", ""),
                        role = res.optString("role", "student"),
                        active_role = res.optString("active_role", ""),
                        primary_role = res.optString("primary_role", ""),
                        roles = rolesList,
                        email = res.optString("email", ""),
                        avatar = res.optString("avatar", ""),
                        group_id = if (res.has("group_id")) res.optInt("group_id") else null,
                        group_name = res.optString("group_name", ""),
                        group = res.optString("group", ""),
                        lectern_id = if (res.has("lectern_id")) res.optInt("lectern_id") else null,
                        job_title = res.optString("job_title", ""),
                        nfc_tag = res.optString("nfc_tag", ""),
                        total_cheat_attempts = res.optInt("total_cheat_attempts", 0)
                    )
                    if (profile.avatar.isNotBlank()) {
                        sharedPrefs.edit().putString("avatar_url", profile.avatar).apply()
                    } else {
                        sharedPrefs.edit().remove("avatar_url").apply()
                    }
                    GenericResult.Success(profile)
                } else {
                    GenericResult.Error(com.example.kotlinroomdatabase.util.ApiErrorMapper.mapErrorMessage(jsonObj.optString("error")))
                }
            } else {
                GenericResult.Error(com.example.kotlinroomdatabase.util.ApiErrorMapper.mapHttpStatus(response.code))
            }
        } catch (e: Exception) {
            GenericResult.Error(com.example.kotlinroomdatabase.util.ApiErrorMapper.mapError(e))
        }
    }

    override suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$BASE_URL/api/semesters/current").get().build()
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            Log.e("HTTP_REPO", "testConnection failed", e)
            false
        }
    }
}
