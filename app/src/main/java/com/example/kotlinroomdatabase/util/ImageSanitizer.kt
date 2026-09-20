package com.example.kotlinroomdatabase.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

object ImageSanitizer {

    private const val TAG = "ImageSanitizer"
    private const val MAX_DIMENSION = 2048
    private const val JPEG_QUALITY = 80
    private const val MAX_ALLOWED_RAW_BYTES = 15 * 1024 * 1024 // 15MB limit for raw input

    /**
     * Safely decodes, downsamples, and re-compresses an image from Uri into a clean JPEG.
     * Strips all EXIF, polyglot payloads, embedded scripts, and prevents decompression bombs.
     */
    suspend fun sanitizeImage(context: Context, uri: Uri): File? = withContext(Dispatchers.IO) {
        var inputStream: InputStream? = null
        try {
            // 1. Check stream availability & basic size
            val contentResolver = context.contentResolver
            val assetFileDescriptor = contentResolver.openAssetFileDescriptor(uri, "r")
            val rawLength = assetFileDescriptor?.length ?: -1L
            assetFileDescriptor?.close()

            if (rawLength > MAX_ALLOWED_RAW_BYTES) {
                Log.w(TAG, "File exceeds raw size limit: $rawLength bytes")
                return@withContext null
            }

            // 2. Decode bounds only to prevent OOM / decompression bomb
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            inputStream = contentResolver.openInputStream(uri)
            BitmapFactory.decodeStream(inputStream, null, options)
            inputStream?.close()

            val origWidth = options.outWidth
            val origHeight = options.outHeight

            if (origWidth <= 0 || origHeight <= 0) {
                Log.w(TAG, "Invalid image dimensions: ${origWidth}x${origHeight}")
                return@withContext null
            }

            // 3. Compute sample size to keep within MAX_DIMENSION
            var sampleSize = 1
            while ((origWidth / sampleSize) > MAX_DIMENSION || (origHeight / sampleSize) > MAX_DIMENSION) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565 // Memory-efficient config
            }

            // 4. Decode actual bitmap
            inputStream = contentResolver.openInputStream(uri)
            val decodedBitmap = BitmapFactory.decodeStream(inputStream, null, decodeOptions)
            inputStream?.close()

            if (decodedBitmap == null) {
                Log.w(TAG, "BitmapFactory failed to decode image stream")
                return@withContext null
            }

            // 5. Re-encode into clean JPEG in cache directory
            val feedbackDir = File(context.cacheDir, "feedback_uploads").apply {
                if (!exists()) mkdirs()
            }
            val sanitizedFile = File(feedbackDir, "sanitized_${UUID.randomUUID()}.jpg")

            FileOutputStream(sanitizedFile).use { outStream ->
                decodedBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outStream)
                outStream.flush()
            }

            // 6. Recycle bitmap immediately
            decodedBitmap.recycle()

            Log.d(TAG, "Sanitized image: ${sanitizedFile.absolutePath} (${sanitizedFile.length()} bytes)")
            return@withContext sanitizedFile

        } catch (oom: OutOfMemoryError) {
            Log.e(TAG, "OOM while sanitizing image", oom)
            System.gc()
            return@withContext null
        } catch (e: Exception) {
            Log.e(TAG, "Error sanitizing image", e)
            return@withContext null
        } finally {
            try {
                inputStream?.close()
            } catch (_: Exception) {}
        }
    }
}
