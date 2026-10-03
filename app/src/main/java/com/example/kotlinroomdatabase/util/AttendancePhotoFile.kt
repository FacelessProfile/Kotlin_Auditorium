package com.example.kotlinroomdatabase.util

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import java.io.File

object AttendancePhotoFile {
    const val MAX_BYTES = 15 * 1024 * 1024

    /** Preserve resolution and bytes so small faces stay readable and duplicate uploads hash identically. */
    suspend fun <T> use(context: Context, uri: Uri, upload: suspend (File) -> T): T = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "attendance_photo_uploads").apply { mkdirs() }
        val file = File.createTempFile("photo_", ".upload", directory)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        coroutineContext.ensureActive()
                        val size = input.read(buffer)
                        if (size < 0) break
                        total += size
                        require(total <= MAX_BYTES) { "Размер фото должен быть не больше 15 МБ" }
                        output.write(buffer, 0, size)
                    }
                    require(total > 0) { "Фотография пуста" }
                }
            } ?: error("Не удалось открыть фотографию")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            require(bounds.outMimeType in setOf("image/jpeg", "image/png", "image/webp")) {
                "Выберите фото в формате JPEG, PNG или WebP"
            }
            require(bounds.outWidth > 0 && bounds.outHeight > 0 &&
                bounds.outWidth.toLong() * bounds.outHeight <= 20_000_000L) {
                "Выберите фото размером до 20 мегапикселей"
            }
            upload(file)
        } finally {
            file.delete()
        }
    }
}
