package com.example.kotlinroomdatabase.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.config.ServerConfig
import com.example.kotlinroomdatabase.data.StudentDatabase
import com.example.kotlinroomdatabase.repository.GenericResult
import com.example.kotlinroomdatabase.repository.StudentRepositoryHTTPS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

object AvatarManager {
    private const val TAG = "AvatarManager"
    private const val AVATAR_FILE_NAME = "current_avatar.jpg"

    fun getCachedAvatarFile(context: Context): File {
        return File(context.filesDir, AVATAR_FILE_NAME)
    }

    /**
     * Instantly loads the cached avatar from local disk into imageView without network lag.
     * Returns true if a cached image was found and set; false if fallback placeholder was used.
     */
    fun loadCachedAvatar(
        context: Context,
        imageView: ImageView,
        defaultPadDp: Int = 10,
        placeholderTintRes: Int = R.color.sib_blue_primary
    ): Boolean {
        val prefs = context.getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
        val avatarPath = prefs.getString("avatar_path", null)
        val file = if (avatarPath != null) File(avatarPath) else getCachedAvatarFile(context)

        if (file.exists() && file.length() > 0) {
            try {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) {
                    imageView.setPadding(0, 0, 0, 0)
                    imageView.setImageBitmap(bitmap)
                    imageView.imageTintList = null
                    return true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error decoding cached avatar", e)
            }
        }

        // Fallback to placeholder
        val pad = (defaultPadDp * context.resources.displayMetrics.density).toInt()
        imageView.setPadding(pad, pad, pad, pad)
        imageView.setImageResource(R.drawable.ic_person)
        imageView.imageTintList = ContextCompat.getColorStateList(context, placeholderTintRes)
        return false
    }

    /**
     * Performs a background check against the server:
     * 1. Fetches current user profile
     * 2. Compares avatar URL with synced_avatar_url
     * 3. Downloads fresh avatar only if URL changed or local file is missing
     * 4. Updates preferences and notifies onUpdated callback on Main dispatcher
     */
    fun syncAvatarFromServer(
        context: Context,
        forceRefresh: Boolean = false,
        onUpdated: (() -> Unit)? = null
    ): Job {
        val appContext = context.applicationContext
        return CoroutineScope(Dispatchers.IO).launch {
            try {
                val authPrefs = appContext.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
                val token = authPrefs.getString("auth_token", "") ?: ""
                if (token.isBlank()) {
                    Log.d(TAG, "Cannot sync avatar: unauthenticated")
                    return@launch
                }

                val db = StudentDatabase.getInstance(appContext)
                val repo = StudentRepositoryHTTPS(appContext, db.studentDao())
                val profileResult = repo.getFullUserProfile()

                if (profileResult !is GenericResult.Success) {
                    Log.w(TAG, "Profile fetch failed during avatar sync: ${(profileResult as? GenericResult.Error)?.message}")
                    return@launch
                }

                val profile = profileResult.data
                val studentPrefs = appContext.getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
                val localFile = getCachedAvatarFile(appContext)

                // Update profile info in preferences if present
                studentPrefs.edit().apply {
                    if (profile.name.isNotBlank()) putString("student_name", profile.name)
                    if (profile.role.isNotBlank()) putString("user_role", profile.role)
                    if (profile.email.isNotBlank()) putString("user_email", profile.email)
                    if (profile.group.isNotBlank()) putString("student_group", profile.group)
                }.apply()

                val avatarUrlFromBackend = profile.avatar.trim()
                if (avatarUrlFromBackend.isBlank() || avatarUrlFromBackend == "null") {
                    // Avatar was removed on server
                    if (localFile.exists()) {
                        try { localFile.delete() } catch (_: Exception) {}
                    }
                    studentPrefs.edit().remove("avatar_path").remove("synced_avatar_url").apply()
                    authPrefs.edit().remove("avatar_url").apply()
                    withContext(Dispatchers.Main) {
                        onUpdated?.invoke()
                    }
                    return@launch
                }

                val lastSyncedUrl = studentPrefs.getString("synced_avatar_url", null)
                val shouldDownload = forceRefresh || (avatarUrlFromBackend != lastSyncedUrl) || !localFile.exists()

                if (!shouldDownload && localFile.exists()) {
                    Log.d(TAG, "Avatar is up-to-date, skipping download")
                    return@launch
                }

                Log.d(TAG, "Downloading fresh avatar from: $avatarUrlFromBackend")
                val finalUrl = ServerConfig.resolveMediaUrl(appContext, avatarUrlFromBackend)
                val client = repo.getUnsafeOkHttpClient()
                val request = Request.Builder()
                    .url(finalUrl)
                    .header("Cache-Control", "no-cache")
                    .header("Authorization", "Bearer $token")
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val bytes = response.body?.bytes()
                    if (bytes != null && bytes.isNotEmpty()) {
                        FileOutputStream(localFile).use { it.write(bytes) }
                        studentPrefs.edit()
                            .putString("avatar_path", localFile.absolutePath)
                            .putString("synced_avatar_url", avatarUrlFromBackend)
                            .apply()
                        authPrefs.edit()
                            .putString("avatar_url", avatarUrlFromBackend)
                            .apply()

                        Log.d(TAG, "Avatar synced and saved successfully")
                        withContext(Dispatchers.Main) {
                            onUpdated?.invoke()
                        }
                    }
                } else {
                    Log.e(TAG, "Failed to download avatar: HTTP ${response.code}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in syncAvatarFromServer", e)
            }
        }
    }

    /**
     * Saves a locally created or cropped avatar image to disk and updates preferences.
     */
    fun saveLocally(context: Context, bytes: ByteArray, syncedUrl: String? = null): File {
        val file = getCachedAvatarFile(context)
        FileOutputStream(file).use { it.write(bytes) }
        val studentPrefs = context.getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
        val editor = studentPrefs.edit().putString("avatar_path", file.absolutePath)
        if (!syncedUrl.isNullOrBlank()) {
            editor.putString("synced_avatar_url", syncedUrl)
            context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
                .edit().putString("avatar_url", syncedUrl).apply()
        }
        editor.apply()
        return file
    }
}
