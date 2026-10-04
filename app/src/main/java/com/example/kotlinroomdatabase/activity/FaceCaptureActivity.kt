package com.example.kotlinroomdatabase.activity

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.kotlinroomdatabase.databinding.ActivityFaceCaptureBinding
import com.example.kotlinroomdatabase.model.StudentFaceSamplesStatus
import com.example.kotlinroomdatabase.util.StudentFacePhotoManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class FaceCaptureActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFaceCaptureBinding
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null

    private var lensFacing: Int = CameraSelector.LENS_FACING_FRONT
    private var isTorchOn: Boolean = false
    private lateinit var cameraExecutor: ExecutorService

    private var activeAngle: String = StudentFaceSamplesStatus.ANGLE_FRONTAL
    private var capturedFile: File? = null

    private val requestCameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                startCamera()
            } else {
                Toast.makeText(this, "Для съемки лица необходим доступ к камере", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFaceCaptureBinding.inflate(layoutInflater)
        setContentView(binding.root)

        activeAngle = intent.getStringExtra(EXTRA_ANGLE) ?: StudentFaceSamplesStatus.ANGLE_FRONTAL
        cameraExecutor = Executors.newSingleThreadExecutor()

        setupUI()
        checkPermissionAndStartCamera()
    }

    private fun setupUI() {
        binding.faceOverlayView.activeAngle = activeAngle
        binding.tvAngleTitle.text = StudentFaceSamplesStatus.titleForAngle(activeAngle)
        binding.tvAngleGuidance.text = StudentFaceSamplesStatus.cameraGuidanceForAngle(activeAngle)

        if (activeAngle == StudentFaceSamplesStatus.ANGLE_PROFILE) {
            binding.tvCameraHintBanner.visibility = View.VISIBLE
            binding.tvCameraHintBanner.text = "Снимать профиль легче, если переключить камеру и попросить друга!"
        } else {
            binding.tvCameraHintBanner.visibility = View.GONE
        }

        binding.btnCloseCamera.setOnClickListener {
            cleanupTempFiles()
            setResult(Activity.RESULT_CANCELED)
            finish()
        }

        binding.btnSwitchCamera.setOnClickListener {
            toggleCameraFacing()
        }

        binding.btnToggleTorch.setOnClickListener {
            toggleTorch()
        }

        binding.btnShutterContainer.setOnClickListener {
            takePhoto()
        }

        binding.btnRetakePhoto.setOnClickListener {
            cleanupTempFiles()
            binding.layoutReviewOverlay.visibility = View.GONE
        }

        binding.btnAcceptPhoto.setOnClickListener {
            confirmCapturedPhoto()
        }
    }

    private fun checkPermissionAndStartCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize camera provider", e)
                Toast.makeText(this, "Не удалось открыть камеру: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return

        val preview = Preview.Builder()
            .build()
            .also {
                it.setSurfaceProvider(binding.cameraPreviewView.surfaceProvider)
            }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        // Fallback to back camera if front camera is not available
        val cameraSelector = if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) && lensFacing == CameraSelector.LENS_FACING_FRONT) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageCapture
            )

            // Update torch availability
            val hasFlash = camera?.cameraInfo?.hasFlashUnit() == true
            binding.btnToggleTorch.visibility = if (hasFlash) View.VISIBLE else View.GONE
            isTorchOn = false
            updateTorchButtonIcon()

        } catch (e: Exception) {
            Log.e(TAG, "Binding camera failed", e)
            Toast.makeText(this, "Ошибка запуска камеры: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleCameraFacing() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            CameraSelector.LENS_FACING_BACK
        } else {
            CameraSelector.LENS_FACING_FRONT
        }

        val isBack = lensFacing == CameraSelector.LENS_FACING_BACK
        binding.tvCameraHintBanner.visibility = View.VISIBLE
        binding.tvCameraHintBanner.text = if (isBack) {
            "Основная камера: отдайте телефон другу, чтобы он сфотографировал вас!"
        } else {
            "Фронтальная камера: совместите лицо с овальной пунктирной рамкой"
        }

        bindCameraUseCases()
    }

    private fun toggleTorch() {
        val cam = camera ?: return
        if (cam.cameraInfo.hasFlashUnit()) {
            isTorchOn = !isTorchOn
            cam.cameraControl.enableTorch(isTorchOn)
            updateTorchButtonIcon()
        }
    }

    private fun updateTorchButtonIcon() {
        binding.btnToggleTorch.setImageResource(
            if (isTorchOn) com.example.kotlinroomdatabase.R.drawable.ic_flash_on
            else com.example.kotlinroomdatabase.R.drawable.ic_flash_off
        )
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        if (binding.layoutReviewOverlay.visibility == View.VISIBLE) return

        triggerHapticFeedback()
        binding.faceOverlayView.triggerFlashEffect()

        val tempFile = StudentFacePhotoManager.createTempCaptureFile(this, activeAngle)
        val outputOptions = ImageCapture.OutputFileOptions.Builder(tempFile).build()

        capture.takePicture(
            outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    processSavedPhoto(tempFile)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exception.message}", exception)
                    runOnUiThread {
                        Toast.makeText(
                            this@FaceCaptureActivity,
                            "Ошибка съемки: ${exception.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        )
    }

    private fun processSavedPhoto(rawFile: File) {
        lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                binding.progressProcessing.visibility = View.VISIBLE
                binding.layoutReviewOverlay.visibility = View.VISIBLE
            }

            val result = StudentFacePhotoManager.processAndValidatePhoto(
                this@FaceCaptureActivity,
                rawFile,
                activeAngle
            )

            result.onSuccess { validatedFile ->
                capturedFile = validatedFile
                val previewBitmap = BitmapFactory.decodeFile(validatedFile.absolutePath)
                withContext(Dispatchers.Main) {
                    binding.progressProcessing.visibility = View.GONE
                    binding.ivCapturedPreview.setImageBitmap(previewBitmap)
                }
            }.onFailure { err ->
                withContext(Dispatchers.Main) {
                    binding.progressProcessing.visibility = View.GONE
                    binding.layoutReviewOverlay.visibility = View.GONE
                    Toast.makeText(
                        this@FaceCaptureActivity,
                        "Ошибка: ${err.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun confirmCapturedPhoto() {
        val file = capturedFile
        if (file == null || !file.exists()) {
            Toast.makeText(this, "Снимок не найден", Toast.LENGTH_SHORT).show()
            return
        }

        val resultIntent = Intent().apply {
            putExtra(EXTRA_IMAGE_PATH, file.absolutePath)
            putExtra(EXTRA_ANGLE, activeAngle)
        }
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    private fun triggerHapticFeedback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                vibrator?.vibrate(50L)
            }
        } catch (_: Exception) {}
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (binding.layoutReviewOverlay.visibility != View.VISIBLE) {
                takePhoto()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun cleanupTempFiles() {
        capturedFile?.let {
            if (it.exists()) it.delete()
        }
        capturedFile = null
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "FaceCaptureActivity"
        const val EXTRA_ANGLE = "extra_angle"
        const val EXTRA_IMAGE_PATH = "extra_image_path"

        fun newIntent(context: Context, angle: String): Intent {
            return Intent(context, FaceCaptureActivity::class.java).apply {
                putExtra(EXTRA_ANGLE, angle)
            }
        }
    }
}
