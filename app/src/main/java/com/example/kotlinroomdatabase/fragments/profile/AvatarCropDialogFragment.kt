package com.example.kotlinroomdatabase.fragments.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import com.example.kotlinroomdatabase.databinding.DialogAvatarCropBinding
import com.example.kotlinroomdatabase.util.RoleUtils
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

class AvatarCropDialogFragment : DialogFragment() {

    private var _binding: DialogAvatarCropBinding? = null
    private val binding get() = _binding!!

    private var imageUri: Uri? = null
    var onCropConfirmed: ((Uri) -> Unit)? = null

    companion object {
        private const val ARG_URI = "arg_image_uri"

        fun newInstance(uri: Uri): AvatarCropDialogFragment {
            val fragment = AvatarCropDialogFragment()
            val args = Bundle()
            args.putParcelable(ARG_URI, uri)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        imageUri = arguments?.getParcelable(ARG_URI)
        isCancelable = true
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogAvatarCropBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupUserInfoPreview()
        setupControls()

        val uri = imageUri
        if (uri == null) {
            Toast.makeText(requireContext(), "Не удалось загрузить изображение", Toast.LENGTH_SHORT).show()
            dismiss()
            return
        }

        val bitmap = loadAndOrientBitmap(uri)
        if (bitmap == null) {
            Toast.makeText(requireContext(), "Ошибка обработки файла изображения", Toast.LENGTH_SHORT).show()
            dismiss()
            return
        }

        binding.avatarCropView.setSourceBitmap(bitmap)
        binding.avatarCropView.onCropTransformChanged = {
            updateLivePreview()
        }

        // Initial preview update once layout finishes
        binding.avatarCropView.post {
            updateLivePreview()
        }
    }

    private fun setupUserInfoPreview() {
        val prefs = requireContext().getSharedPreferences("student_prefs", Context.MODE_PRIVATE)
        val name = prefs.getString("student_name", "Студент") ?: "Студент"
        val role = prefs.getString("user_role", "student") ?: "student"

        binding.tvPreviewName.text = RoleUtils.formatShortName(name)
        binding.tvPreviewRole.text = RoleUtils.getRoleLabel(role)
    }

    private fun setupControls() {
        binding.btnZoomIn.setOnClickListener {
            binding.avatarCropView.zoomIn()
        }

        binding.btnZoomOut.setOnClickListener {
            binding.avatarCropView.zoomOut()
        }

        binding.btnRotateRight.setOnClickListener {
            binding.avatarCropView.rotate90Clockwise()
        }

        binding.btnResetCrop.setOnClickListener {
            binding.avatarCropView.resetTransform()
        }

        binding.btnCropClose.setOnClickListener {
            dismiss()
        }

        binding.btnCancelCrop.setOnClickListener {
            dismiss()
        }

        binding.btnApplyCrop.setOnClickListener {
            saveCroppedAvatarAndFinish()
        }
    }

    private fun updateLivePreview() {
        if (_binding == null) return
        val previewBitmap = binding.avatarCropView.getCroppedPreviewBitmap(160)
        if (previewBitmap != null) {
            binding.previewAvatar.setImageBitmap(previewBitmap)
            binding.previewAvatar.imageTintList = null
            binding.previewAvatar.setPadding(0, 0, 0, 0)
        }
    }

    private fun saveCroppedAvatarAndFinish() {
        val cropped = binding.avatarCropView.getCroppedBitmap(512)
        if (cropped == null) {
            Toast.makeText(requireContext(), "Ошибка кадрирования", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val file = File(requireContext().filesDir, "current_avatar.jpg")
            val fos = FileOutputStream(file)
            fos.use { out ->
                cropped.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            val resultUri = Uri.fromFile(file)
            onCropConfirmed?.invoke(resultUri)
            dismiss()
        } catch (e: Exception) {
            Log.e("AvatarCropDialog", "Failed to save cropped avatar", e)
            Toast.makeText(requireContext(), "Не удалось сохранить фото", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadAndOrientBitmap(uri: Uri): Bitmap? {
        val context = context ?: return null
        return try {
            // Read EXIF orientation
            var orientation = ExifInterface.ORIENTATION_NORMAL
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val exif = ExifInterface(stream)
                    orientation = exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                }
            } catch (e: Exception) {
                Log.w("AvatarCropDialog", "Could not read EXIF orientation", e)
            }

            // Downsample if image is huge
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            val maxDimension = max(options.outWidth, options.outHeight)
            var sampleSize = 1
            while (maxDimension / sampleSize > 2048) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            val rawBitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            } ?: return null

            // Apply EXIF rotation if needed
            val degrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }

            if (degrees != 0f) {
                val matrix = Matrix().apply { postRotate(degrees) }
                Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
            } else {
                rawBitmap
            }
        } catch (e: Exception) {
            Log.e("AvatarCropDialog", "Error loading bitmap from uri", e)
            null
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window ->
            val displayMetrics = resources.displayMetrics
            val width = (displayMetrics.widthPixels * 0.94).toInt()
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            window.setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
