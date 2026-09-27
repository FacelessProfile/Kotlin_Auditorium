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
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

object AvatarManager {
    private const val TAG = "AvatarManager"
    private const val AVATAR_FILE_NAME = "current_avatar.jpg"
    private const val MAX_AVATAR_BYTES = 10 * 1024 * 1024L // 10 MB limit (KA-14)

    fun getCachedAvatarFile(context: Context): File {
        return File(context.filesDir, AVATAR_FILE_NAME)
    }

    /**
     * Reads response body safely up to maxBytes to prevent memory or DoS exhaustion.
     */
    private fun readLimitedBody(body: ResponseBody?, maxBytes: Long = MAX_AVATAR_BYTES): ByteArray? {
        if (body == null) return null
        val contentLength = body.contentLength()
        if (contentLength > maxBytes) {
            Log.w(TAG, "Avatar response size exceeds limit: $contentLength > $maxBytes")
            return null
        }
        return try {
            body.byteStream().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var totalRead = 0L
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    totalRead += bytesRead
                    if (totalRead > maxBytes) {
                        Log.w(TAG, "Aborting avatar read: stream exceeded $maxBytes bytes")
                        return null
                    }
                    output.write(buffer, 0, bytesRead)
                }
                output.toByteArray()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading avatar stream", e)
            null
        }
    }

    private fun decodeSampledBitmap(data: ByteArray, maxDim: Int = 4096): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) return null
            if (options.outWidth > maxDim || options.outHeight > maxDim) {
                var sampleSize = 1
                while ((options.outWidth / sampleSize) > maxDim || (options.outHeight / sampleSize) > maxDim) {
                    sampleSize *= 2
                }
                options.inSampleSize = sampleSize
            }
            options.inJustDecodeBounds = false
            BitmapFactory.decodeByteArray(data, 0, data.size, options)
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding bitmap", e)
            null
        }
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

                val avatarUrlFromBackend = profile.avatar.trim()
                if (avatarUrlFromBackend.isNullOrBlank()) {
                    Log.d(TAG, "No avatar configured on server")
                    return@launch
                }

                val localFile = getCachedAvatarFile(appContext)
                val lastSyncedUrl = studentPrefs.getString("synced_avatar_url", null)
                val shouldDownload = forceRefresh || (avatarUrlFromBackend != lastSyncedUrl) || !localFile.exists()

                if (!shouldDownload && localFile.exists()) {
                    Log.d(TAG, "Avatar is up-to-date, skipping download")
                    return@launch
                }

                Log.d(TAG, "Downloading fresh avatar from: $avatarUrlFromBackend")
                val finalUrl = ServerConfig.resolveMediaUrl(appContext, avatarUrlFromBackend)
                val client = repo.getUnsafeOkHttpClient()
                val requestBuilder = Request.Builder()
                    .url(finalUrl)
                    .header("Cache-Control", "no-cache")

                // KA-04: Only attach Authorization if the origin is trusted (our backend)
                if (ServerConfig.isTrustedOrigin(appContext, finalUrl) && token.isNotBlank()) {
                    requestBuilder.header("Authorization", "Bearer $token")
                }

                val response = client.newCall(requestBuilder.build()).execute()
                if (response.isSuccessful) {
                    val bytes = readLimitedBody(response.body, MAX_AVATAR_BYTES)
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

    /**
     * Checks whether an avatar URL points to a custom uploaded avatar rather than empty or default placeholder.
     */
    fun isCustomAvatar(avatarUrl: String?): Boolean {
        if (avatarUrl.isNullOrBlank()) return false
        val trimmed = avatarUrl.trim()
        if (trimmed.equals("null", ignoreCase = true) || trimmed.isEmpty()) return false
        val lower = trimmed.lowercase()
        if (lower.contains("default") || lower.contains("placeholder") ||
            lower.contains("no_avatar") || lower.contains("noavatar") ||
            lower.endsWith("/ic_person.png") || lower.endsWith("/default.png") ||
            (lower.endsWith("/avatar.png") && lower.contains("example.com"))
        ) {
            return false
        }
        return true
    }

    /**
     * Loads avatar from URL with local caching into any ImageView, supporting Recycler view recycling.
     */
    fun loadAvatarUrl(
        context: Context,
        imageView: ImageView,
        url: String? = null,
        avatarUrl: String? = null,
        defaultPadDp: Int = 10,
        showDefaultPlaceholder: Boolean = true,
        onSuccess: ((Bitmap) -> Unit)? = null,
        onError: (() -> Unit)? = null
    ) {
        val appContext = context.applicationContext
        val effectiveUrl = avatarUrl ?: url
        if (!isCustomAvatar(effectiveUrl)) {
            if (showDefaultPlaceholder) {
                val pad = (defaultPadDp * context.resources.displayMetrics.density).toInt()
                imageView.setPadding(pad, pad, pad, pad)
                imageView.setImageResource(R.drawable.ic_person)
                imageView.imageTintList = ContextCompat.getColorStateList(context, R.color.sib_blue_primary)
            }
            onError?.invoke()
            return
        }

        val finalUrl = ServerConfig.resolveMediaUrl(appContext, effectiveUrl)
        imageView.tag = finalUrl

        val cacheDir = File(appContext.cacheDir, "avatars").apply { if (!exists()) mkdirs() }
        val cacheKey = finalUrl.hashCode().toString()
        val cacheFile = File(cacheDir, "$cacheKey.jpg")

        if (cacheFile.exists() && cacheFile.length() > 0) {
            try {
                val bitmap = BitmapFactory.decodeFile(cacheFile.absolutePath)
                if (bitmap != null) {
                    imageView.setPadding(0, 0, 0, 0)
                    imageView.setImageBitmap(bitmap)
                    imageView.imageTintList = null
                    onSuccess?.invoke(bitmap)
                    return
                }
            } catch (_: Exception) {}
        }

        // Placeholder while loading
        if (showDefaultPlaceholder) {
            val pad = (defaultPadDp * context.resources.displayMetrics.density).toInt()
            imageView.setPadding(pad, pad, pad, pad)
            imageView.setImageResource(R.drawable.ic_person)
            imageView.imageTintList = ContextCompat.getColorStateList(context, R.color.sib_blue_primary)
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val authPrefs = appContext.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
                val token = authPrefs.getString("auth_token", "") ?: ""
                val db = StudentDatabase.getInstance(appContext)
                val repo = StudentRepositoryHTTPS(appContext, db.studentDao())
                val okClient = repo.getUnsafeOkHttpClient()

                val reqBuilder = Request.Builder().url(finalUrl)
                // KA-04: Only attach Authorization if the origin is trusted (our backend)
                if (ServerConfig.isTrustedOrigin(appContext, finalUrl) && token.isNotBlank()) {
                    reqBuilder.header("Authorization", "Bearer $token")
                }

                val resp = okClient.newCall(reqBuilder.build()).execute()
                if (resp.isSuccessful) {
                    val bytes = readLimitedBody(resp.body, MAX_AVATAR_BYTES)
                    if (bytes != null && bytes.isNotEmpty()) {
                        FileOutputStream(cacheFile).use { it.write(bytes) }
                        val bitmap = decodeSampledBitmap(bytes)
                        if (bitmap != null) {
                            withContext(Dispatchers.Main) {
                                if (imageView.tag == finalUrl) {
                                    imageView.setPadding(0, 0, 0, 0)
                                    imageView.setImageBitmap(bitmap)
                                    imageView.imageTintList = null
                                    onSuccess?.invoke(bitmap)
                                }
                            }
                            return@launch
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    onError?.invoke()
                }
            } catch (e: Exception) {
                Log.d(TAG, "Error loading avatar from url: ${e.message}")
                withContext(Dispatchers.Main) {
                    onError?.invoke()
                }
            }
        }
    }
}
