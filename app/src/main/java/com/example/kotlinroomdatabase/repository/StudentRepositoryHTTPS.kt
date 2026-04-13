package com.example.kotlinroomdatabase.repository

import android.content.Context
import android.util.Log
import com.example.kotlinroomdatabase.data.StudentDao
import com.example.kotlinroomdatabase.model.Student
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.InternalSerializationApi
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class StudentRepositoryHTTPS(
    private val context: Context,
    private val studentDao: StudentDao
) : IStudentRepository {

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
    private val BASE_URL = "http://192.168.0.55:20077"
    private val sharedPrefs = context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)

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

                val student = Student(
                    id = result.optString("user_ID").hashCode(),
                    studentName = result.getString("login"),
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

                val student = Student(
                    id = result.optString("user_ID").hashCode(),
                    studentName = result.getString("login"),
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
            sharedPrefs.edit().clear().apply()
        } catch (e: Exception) {
            Log.e("HTTP_REPO", "Clear Data Exception", e)
        }
    }

    override suspend fun syncAllStudents(): SyncResult = withContext(Dispatchers.IO) {
        try {
            val token = sharedPrefs.getString("auth_token", "") ?: ""
            if (token.isEmpty()) return@withContext SyncResult.Error("No token found")

            val request = Request.Builder()
                .url("$BASE_URL/profile")
                .get()
                .addHeader("Authorization", token)
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                SyncResult.Success(1, "Profile Synced")
            } else {
                SyncResult.Error("Sync Error: ${response.code}")
            }
        } catch (e: Exception) {
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

    override suspend fun getAttendanceLink(lessonId: Int): AttendanceLinkResult = withContext(Dispatchers.IO) {
        try {
            val token = sharedPrefs.getString("auth_token", "") ?: ""
            if (token.isEmpty()) return@withContext AttendanceLinkResult.Error("Отсутствует токен авторизации")

            val jsonRequest = JSONObject().apply {
                put("lesson_id", lessonId) // BACKend ждет lesson_id???? (логично вроде)
            }

            val body = jsonRequest.toString().toRequestBody(JSON_TYPE)
            val request = Request.Builder()
                .url("$BASE_URL/api/teacher/attendance-link")
                .post(body)
                .addHeader("Authorization", token) // ну и токен
                .build()

            val response = client.newCall(request).execute()
            val responseStr = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                val errorMsg = JSONObject(responseStr).optString("error", "Ошибка сервера ${response.code}")
                return@withContext AttendanceLinkResult.Error(errorMsg)
            }

            val jsonResponse = JSONObject(responseStr)
            if (jsonResponse.optBoolean("ok")) {
                val resultData = jsonResponse.optString("result", "")
                AttendanceLinkResult.Success(resultData)
            } else {
                AttendanceLinkResult.Error(jsonResponse.optString("error", "Не удалось получить ссылку"))
            }
        } catch (e: Exception) {
            Log.e("HTTP_REPO", "Link Exception", e)
            AttendanceLinkResult.Error("Ошибка сети: ${e.message}")
        }
    }
    override suspend fun getAllUniqueGroups(): List<String> = withContext(Dispatchers.IO) {
        studentDao.getAllGroups().firstOrNull() ?: emptyList()
    }
    override suspend fun createLesson(subject: String, teacherId: Int, groups: List<String>): Int? = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("subject", subject)
                put("teacher_id", teacherId)
                put("groups", org.json.JSONArray(groups))
            }
            val request = Request.Builder()
                .url("$BASE_URL/lessons/create")
                .post(json.toString().toRequestBody(JSON_TYPE))
                .build()

            val response = client.newCall(request).execute()
            val responseStr = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val jsonObj = JSONObject(responseStr)
                if (jsonObj.has("id")) jsonObj.getInt("id") else null
            } else null
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun finishLesson(lessonId: Int): FinishLessonResult =
        FinishLessonResult.Error("Not implemented via HTTP")

    override suspend fun markAttendanceInLesson(lessonId: Int, nfcTag: String): AttendanceResult =
        AttendanceResult.Error("Not implemented via HTTP")

    private fun saveToken(token: String?) {
        if (!token.isNullOrBlank()) {
            sharedPrefs.edit().putString("auth_token", token).apply()
        }
    }
}