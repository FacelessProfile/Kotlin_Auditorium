package com.example.kotlinroomdatabase.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object StudentFacePhotoManager {
    const val MAX_BYTES = 15 * 1024 * 1024 // 15 MiB
    const val MAX_PIXELS = 20_000_000L // 20 Megapixels

    fun getSamplesDirectory(context: Context): File {
        return File(context.cacheDir, "student_face_samples").apply {
            if (!exists()) mkdirs()
        }
    }

    fun getSampleFile(context: Context, angle: String): File {
        return File(getSamplesDirectory(context), "sample_${angle.lowercase()}.jpg")
    }

    fun createTempCaptureFile(context: Context, angle: String): File {
        return File.createTempFile("capture_${angle}_", ".jpg", getSamplesDirectory(context))
    }

    suspend fun processAndValidatePhoto(
        context: Context,
        sourceFile: File,
        targetAngle: String
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            require(sourceFile.exists() && sourceFile.length() > 0) {
                "Файл фотографии пуст или не найден"
            }
            require(sourceFile.length() <= MAX_BYTES) {
                "Размер фото должен быть не больше 15 МБ"
            }

            // 1. Check dimensions and MIME type
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(sourceFile.absolutePath, bounds)

            val mime = bounds.outMimeType ?: "image/jpeg"
            require(mime in setOf("image/jpeg", "image/png", "image/webp")) {
                "Выберите фото в формате JPEG, PNG или WebP"
            }

            val width = bounds.outWidth
            val height = bounds.outHeight
            require(width > 0 && height > 0) { "Не удалось распознать изображение" }
            require(width.toLong() * height <= MAX_PIXELS) {
                "Выберите фото размером до 20 мегапикселей"
            }

            // 2. Read Exif orientation and rotate if needed
            val exif = ExifInterface(sourceFile.absolutePath)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )

            val rotationDegrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }

            val targetFile = getSampleFile(context, targetAngle)

            if (rotationDegrees != 0f) {
                val fullBitmap = BitmapFactory.decodeFile(sourceFile.absolutePath)
                    ?: error("Ошибка декодирования фотографии")
                val matrix = Matrix().apply { postRotate(rotationDegrees) }
                val rotatedBitmap = Bitmap.createBitmap(
                    fullBitmap, 0, 0, fullBitmap.width, fullBitmap.height, matrix, true
                )
                if (rotatedBitmap != fullBitmap) fullBitmap.recycle()

                FileOutputStream(targetFile).use { out ->
                    rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
                rotatedBitmap.recycle()
            } else {
                sourceFile.copyTo(targetFile, overwrite = true)
            }

            // Cleanup temp source if it wasn't the target file itself
            if (sourceFile.absolutePath != targetFile.absolutePath) {
                sourceFile.delete()
            }

            targetFile
        }
    }

    suspend fun loadThumbnail(file: File, targetSizePx: Int = 300): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            if (!file.exists() || file.length() == 0L) return@runCatching null

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)

            var sampleSize = 1
            val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
            while (maxDim / (sampleSize * 2) >= targetSizePx) {
                sampleSize *= 2
            }

            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        }.getOrNull()
    }

    fun deleteSample(context: Context, angle: String) {
        runCatching {
            val file = getSampleFile(context, angle)
            if (file.exists()) file.delete()
        }
    }

    fun clearAllSamples(context: Context) {
        runCatching {
            getSamplesDirectory(context).listFiles()?.forEach { it.delete() }
        }
    }
}
