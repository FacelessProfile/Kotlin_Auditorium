package com.example.kotlinroomdatabase.fragments.feedback

import android.app.Dialog
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.config.ServerConfig
import com.example.kotlinroomdatabase.data.StudentDatabase
import com.example.kotlinroomdatabase.repository.StudentRepositoryHTTPS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

class PhotoViewerDialog : DialogFragment() {

    companion object {
        const val TAG = "PhotoViewerDialog"
        private const val ARG_IMAGE_URL = "arg_image_url"
        private const val ARG_TITLE = "arg_title"

        fun newInstance(imageUrl: String, title: String = "Просмотр фото"): PhotoViewerDialog {
            val dialog = PhotoViewerDialog()
            val args = Bundle().apply {
                putString(ARG_IMAGE_URL, imageUrl)
                putString(ARG_TITLE, title)
            }
            dialog.arguments = args
            return dialog
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.apply {
            requestFeature(Window.FEATURE_NO_TITLE)
            setBackgroundDrawable(ColorDrawable(Color.parseColor("#F0080E1E")))
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        val imageUrl = arguments?.getString(ARG_IMAGE_URL) ?: ""
        val titleText = arguments?.getString(ARG_TITLE) ?: "Просмотр фото"

        val root = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#F0080E1E"))
        }

        // Image View
        val imageView = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        }
        root.addView(imageView)

        // Progress bar
        val progressBar = ProgressBar(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.CENTER
            }
        }
        root.addView(progressBar)

        // Top bar
        val topBar = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                160
            ).apply {
                gravity = android.view.Gravity.TOP
            }
            setPadding(32, 48, 32, 0)
        }

        val tvTitle = TextView(context).apply {
            text = titleText
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
                marginStart = 16
            }
        }
        topBar.addView(tvTitle)

        val btnClose = ImageButton(context).apply {
            layoutParams = FrameLayout.LayoutParams(96, 96).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.END
            }
            setImageResource(R.drawable.ic_cross)
            setColorFilter(Color.WHITE)
            setBackgroundResource(R.drawable.circle_shape)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#55000000"))
            setPadding(18, 18, 18, 18)
            setOnClickListener { dismiss() }
        }
        topBar.addView(btnClose)

        root.addView(topBar)

        // Load image
        loadImage(imageUrl, imageView, progressBar)

        return root
    }

    private fun loadImage(url: String, imageView: ImageView, progressBar: ProgressBar) {
        if (url.isBlank()) {
            progressBar.visibility = View.GONE
            return
        }

        val context = requireContext()
        val finalUrl = ServerConfig.resolveMediaUrl(context, url)

        val cacheDir = File(context.cacheDir, "attachments_cache").apply { mkdirs() }
        val cacheFile = File(cacheDir, "${url.hashCode()}.jpg")

        if (cacheFile.exists() && cacheFile.length() > 0) {
            val bitmap = BitmapFactory.decodeFile(cacheFile.absolutePath)
            if (bitmap != null) {
                progressBar.visibility = View.GONE
                imageView.setImageBitmap(bitmap)
                return
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = StudentDatabase.getInstance(context)
                val repo = StudentRepositoryHTTPS(context, db.studentDao())
                val client = repo.getUnsafeOkHttpClient()

                val authPrefs = context.getSharedPreferences("auth_prefs", android.content.Context.MODE_PRIVATE)
                val token = authPrefs.getString("auth_token", "") ?: ""

                val reqBuilder = Request.Builder().url(finalUrl)
                // KA-04: Only attach Authorization header if destination matches trusted backend origin
                if (ServerConfig.isTrustedOrigin(context, finalUrl) && token.isNotBlank()) {
                    reqBuilder.header("Authorization", "Bearer $token")
                }

                val resp = client.newCall(reqBuilder.build()).execute()
                if (resp.isSuccessful) {
                    // KA-14: Bounded stream read (max 15 MB)
                    val maxBytes = 15 * 1024 * 1024L
                    val body = resp.body
                    var readBytes: ByteArray? = null
                    if (body != null && (body.contentLength() <= 0 || body.contentLength() <= maxBytes)) {
                        body.byteStream().use { input ->
                            val out = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            var total = 0L
                            var n: Int
                            while (input.read(buffer).also { n = it } != -1) {
                                total += n
                                if (total > maxBytes) {
                                    out.reset()
                                    break
                                }
                                out.write(buffer, 0, n)
                            }
                            if (out.size() > 0) readBytes = out.toByteArray()
                        }
                    }

                    if (readBytes != null && readBytes.isNotEmpty()) {
                        FileOutputStream(cacheFile).use { it.write(readBytes) }
                        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(readBytes, 0, readBytes.size, options)
                        if (options.outWidth > 0 && options.outHeight > 0) {
                            var sampleSize = 1
                            while ((options.outWidth / sampleSize) > 4096 || (options.outHeight / sampleSize) > 4096) {
                                sampleSize *= 2
                            }
                            options.inSampleSize = sampleSize
                            options.inJustDecodeBounds = false
                            val bitmap = BitmapFactory.decodeByteArray(readBytes, 0, readBytes.size, options)
                            withContext(Dispatchers.Main) {
                                progressBar.visibility = View.GONE
                                if (bitmap != null) {
                                    imageView.setImageBitmap(bitmap)
                                }
                            }
                        } else {
                            withContext(Dispatchers.Main) { progressBar.visibility = View.GONE }
                        }
                    } else {
                        withContext(Dispatchers.Main) { progressBar.visibility = View.GONE }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        progressBar.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                }
            }
        }
    }
}
