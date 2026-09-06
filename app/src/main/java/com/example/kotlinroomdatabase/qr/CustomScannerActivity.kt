package com.example.kotlinroomdatabase.qr

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.util.Size
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.kotlinroomdatabase.R
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Direct Camera2 Hardware Scanner Engine.
 *
 * Bypasses 3rd-party vendor HAL zoom locks by opening the REAL physical
 * periscope/telephoto sensor directly as its own independent camera device.
 * Automatically binds the highest optical zoom lens on startup for long-distance scanning.
 */
class CustomScannerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SCAN_RESULT = "SCAN_RESULT"
        const val EXTRA_PROMPT = "PROMPT_MESSAGE"
        private const val TAG = "QRScanner2"
    }

    data class DiscoveredLens(
        val cameraId: String,
        val focalLength: Float,
        val eqFocalLength: Float,
        val zoomMultiplier: Float, // relative to 1x main
        val label: String,
        val isTelephoto: Boolean,
        val maxDigitalZoom: Float,
        val zoomRatioRange: Pair<Float, Float>?
    )

    // --- Views ---
    private lateinit var cameraTextureView: TextureView
    private lateinit var viewGestureOverlay: View
    private lateinit var btnScannerBack: ImageButton
    private lateinit var btnScannerTorch: ImageButton
    private lateinit var tvScannerPrompt: TextView
    private lateinit var tvZoomBadge: TextView
    private lateinit var btnPreset1x: TextView
    private lateinit var btnPreset2x: TextView
    private lateinit var btnPreset3x: TextView
    private lateinit var btnPreset5x: TextView
    private lateinit var sbZoom: SeekBar
    private lateinit var ivZoomMin: ImageView
    private lateinit var ivZoomMax: ImageView

    // --- Camera2 State ---
    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var previewRequest: CaptureRequest? = null
    private var imageReader: ImageReader? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private val cameraOpenCloseLock = Semaphore(1)

    // --- ML Kit & Processing ---
    private lateinit var barcodeScanner: BarcodeScanner
    private val isProcessingFrame = AtomicBoolean(false)
    private var isScanned = false

    // --- Discovered Cameras ---
    private var discoveredLenses: List<DiscoveredLens> = emptyList()
    private var activeLensIndex = 0
    private var activeCharacteristics: CameraCharacteristics? = null

    // --- Zoom State ---
    private var currentZoomFactor = 1.0f
    private var minZoomFactor = 1.0f
    private var maxZoomFactor = 10.0f

    // --- Gestures ---
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector

    private var isTorchOn = false
    private var sensorOrientation = 90
    private var previewSize = Size(1920, 1080)

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            setupAndStart()
        } else {
            Toast.makeText(this, "Требуется разрешение на использование камеры", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_custom_scanner)

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        initViews()
        initMlKit()
        setupGestures()
        setupClickListeners()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            setupAndStart()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun initViews() {
        cameraTextureView = findViewById(R.id.cameraTextureView)
        viewGestureOverlay = findViewById(R.id.viewGestureOverlay)
        btnScannerBack = findViewById(R.id.btnScannerBack)
        btnScannerTorch = findViewById(R.id.btnScannerTorch)
        tvScannerPrompt = findViewById(R.id.tvScannerPrompt)
        tvZoomBadge = findViewById(R.id.tvZoomBadge)
        btnPreset1x = findViewById(R.id.btnPreset1x)
        btnPreset2x = findViewById(R.id.btnPreset2x)
        btnPreset3x = findViewById(R.id.btnPreset3x)
        btnPreset5x = findViewById(R.id.btnPreset5x)
        sbZoom = findViewById(R.id.sbZoom)
        ivZoomMin = findViewById(R.id.ivZoomMin)
        ivZoomMax = findViewById(R.id.ivZoomMax)

        val prompt = intent.getStringExtra(EXTRA_PROMPT) ?: intent.getStringExtra("PROMPT_MESSAGE")
        if (!prompt.isNullOrBlank()) {
            tvScannerPrompt.text = prompt
        }
    }

    private fun initMlKit() {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        barcodeScanner = BarcodeScanning.getClient(options)
    }

    private fun setupAndStart() {
        discoverCameras()
        setupPresetButtons()

        // AUTO-SELECT THE HIGHEST OPTICAL ZOOM (TELEPHOTO) BY DEFAULT
        val telephotoIdx = discoveredLenses.indexOfLast { it.isTelephoto }
        activeLensIndex = if (telephotoIdx >= 0) telephotoIdx else {
            // fallback to 1x main lens
            val mainIdx = discoveredLenses.indexOfFirst { abs(it.zoomMultiplier - 1.0f) < 0.3f }
            if (mainIdx >= 0) mainIdx else 0
        }

        Log.i(TAG, "Selected initial camera: ${discoveredLenses.getOrNull(activeLensIndex)?.label} " +
                "(ID: ${discoveredLenses.getOrNull(activeLensIndex)?.cameraId})")

        if (cameraTextureView.isAvailable) {
            openCamera(discoveredLenses[activeLensIndex].cameraId)
        } else {
            cameraTextureView.surfaceTextureListener = surfaceTextureListener
        }
    }

    // =========================================================================
    // CAMERA DISCOVERY ENGINE (Logical + Hidden Physical Sensors)
    // =========================================================================

    private fun discoverCameras() {
        val allCameraIds = mutableSetOf<String>()

        try {
            // 1. Gather standard Camera IDs
            val list = cameraManager.cameraIdList
            allCameraIds.addAll(list)

            // 2. Discover hidden physical cameras inside Logical Multi-Cameras
            for (id in list) {
                try {
                    val chars = cameraManager.getCameraCharacteristics(id)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        val physicalIds = chars.physicalCameraIds
                        if (physicalIds.isNotEmpty()) {
                            Log.i(TAG, "Logical camera $id exposes physical cameras: $physicalIds")
                            allCameraIds.addAll(physicalIds)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error inspecting camera $id for physical IDs: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error discovering cameras", e)
        }

        val backLenses = mutableListOf<DiscoveredLens>()

        for (id in allCameraIds) {
            try {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (facing != CameraCharacteristics.LENS_FACING_BACK) continue

                val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                if (focalLengths == null || focalLengths.isEmpty()) continue
                val focal = focalLengths[0]
                if (focal <= 0f) continue // ignore ToF / depth sensors

                val sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val sensorWidth = sensorSize?.width ?: 6.0f
                val eqFocal = (focal * 36.0f) / sensorWidth

                val maxDigZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1.0f

                val zoomRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let {
                        Pair(it.lower, it.upper)
                    }
                } else null

                // Skip SAT logical master if individual physical cameras are available
                val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                val isLogical = capabilities?.contains(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
                ) == true

                if (isLogical && allCameraIds.size > 2) {
                    Log.i(TAG, "Skipping logical multi-camera $id in favor of physical sensors")
                    continue
                }

                Log.i(TAG, "Found Back Camera ID $id: focal=${focal}mm, eqFocal=${eqFocal}mm, isLogical=$isLogical")
                backLenses.add(
                    DiscoveredLens(
                        cameraId = id,
                        focalLength = focal,
                        eqFocalLength = eqFocal,
                        zoomMultiplier = 1.0f, // will calculate relative to main
                        label = "",
                        isTelephoto = false,
                        maxDigitalZoom = maxDigZoom,
                        zoomRatioRange = zoomRange
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Cannot read characteristics for camera $id: ${e.message}")
            }
        }

        if (backLenses.isEmpty()) {
            // Absolute fallback
            backLenses.add(
                DiscoveredLens("0", 5.0f, 26.0f, 1.0f, "1x", false, 10.0f, null)
            )
        }

        // Sort by 35mm equivalent focal length
        backLenses.sortBy { it.eqFocalLength }

        // Find main 1x camera (typically 22-28mm eq focal length)
        val mainEqFocal = backLenses.firstOrNull { it.eqFocalLength in 20.0f..35.0f }?.eqFocalLength
            ?: backLenses[if (backLenses.size > 2) 1 else 0].eqFocalLength

        discoveredLenses = backLenses.map { lens ->
            val mult = lens.eqFocalLength / mainEqFocal
            val isTele = mult >= 2.2f || lens.focalLength >= 12.0f
            val label = when {
                mult < 0.8f -> String.format(Locale.US, "%.1fx", mult)
                abs(mult - 1.0f) < 0.25f -> "1x"
                abs(mult - 4.3f) < 0.4f -> "4.3x"
                abs(mult - 3.0f) < 0.3f -> "3x"
                abs(mult - 5.0f) < 0.4f -> "5x"
                else -> String.format(Locale.US, "%.1fx", mult)
            }
            lens.copy(zoomMultiplier = mult, label = label, isTelephoto = isTele)
        }

        Log.i(TAG, "=== FINAL DISCOVERED LENSES ===")
        discoveredLenses.forEachIndexed { i, l ->
            Log.i(TAG, "  [$i] ID=${l.cameraId} label=${l.label} mult=${l.zoomMultiplier}x " +
                    "eqFocal=${l.eqFocalLength}mm tele=${l.isTelephoto}")
        }
    }

    private fun setupPresetButtons() {
        val l = discoveredLenses
        when {
            l.size >= 4 -> {
                btnPreset1x.text = l[0].label
                btnPreset2x.text = l[1].label
                btnPreset3x.text = l[2].label
                btnPreset5x.text = l[3].label
            }
            l.size == 3 -> {
                btnPreset1x.text = l[0].label // 0.7x
                btnPreset2x.text = l[1].label // 1x
                btnPreset3x.text = l[2].label // 4.3x Telephoto
                btnPreset5x.text = "10x"      // 10x Hybrid Telephoto
            }
            l.size == 2 -> {
                btnPreset1x.text = l[0].label
                btnPreset2x.text = l[1].label
                btnPreset3x.text = "2x"
                btnPreset5x.text = "10x"
            }
            else -> {
                btnPreset1x.text = "1x"
                btnPreset2x.text = "2x"
                btnPreset3x.text = "4x"
                btnPreset5x.text = "10x"
            }
        }
    }

    // =========================================================================
    // CAMERA2 LIFECYCLE & OPEN / CLOSE
    // =========================================================================

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            if (discoveredLenses.isNotEmpty()) {
                openCamera(discoveredLenses[activeLensIndex].cameraId)
            }
        }
        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
            configureTransform(width, height)
        }
        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error stopping background thread", e)
        }
    }

    private fun openCamera(cameraId: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        startBackgroundThread()

        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }

            activeCharacteristics = cameraManager.getCameraCharacteristics(cameraId)
            sensorOrientation = activeCharacteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

            val map = activeCharacteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            if (map != null) {
                previewSize = chooseOptimalSize(
                    map.getOutputSizes(SurfaceTexture::class.java),
                    cameraTextureView.width.coerceAtLeast(1080),
                    cameraTextureView.height.coerceAtLeast(1920)
                )

                // Setup ImageReader for ML Kit analysis (1080p full HD YUV)
                val analysisSize = chooseOptimalSize(
                    map.getOutputSizes(ImageFormat.YUV_420_888),
                    1920,
                    1080
                )
                imageReader?.close()
                imageReader = ImageReader.newInstance(
                    analysisSize.width,
                    analysisSize.height,
                    ImageFormat.YUV_420_888,
                    2
                ).apply {
                    setOnImageAvailableListener(onImageAvailableListener, backgroundHandler)
                }

                runOnUiThread {
                    configureTransform(cameraTextureView.width, cameraTextureView.height)
                }
            }

            // Determine zoom limits for active camera
            val currentLens = discoveredLenses.getOrNull(activeLensIndex)
            minZoomFactor = currentLens?.zoomRatioRange?.first ?: 1.0f
            maxZoomFactor = currentLens?.zoomRatioRange?.second ?: currentLens?.maxDigitalZoom ?: 10.0f
            currentZoomFactor = minZoomFactor

            cameraManager.openCamera(cameraId, stateCallback, backgroundHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera $cameraId", e)
            cameraOpenCloseLock.release()
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraOpenCloseLock.release()
            cameraDevice = camera
            createCameraPreviewSession()

            runOnUiThread {
                updateUIState()
            }
        }

        override fun onDisconnected(camera: CameraDevice) {
            cameraOpenCloseLock.release()
            camera.close()
            cameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            cameraOpenCloseLock.release()
            camera.close()
            cameraDevice = null
            Log.e(TAG, "Camera device error: $error")
            runOnUiThread {
                Toast.makeText(this@CustomScannerActivity, "Ошибка камеры ($error)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera", e)
        } finally {
            cameraOpenCloseLock.release()
            stopBackgroundThread()
        }
    }

    private fun createCameraPreviewSession() {
        try {
            val texture = cameraTextureView.surfaceTexture ?: return
            texture.setDefaultBufferSize(previewSize.width, previewSize.height)

            val previewSurface = Surface(texture)
            val readerSurface = imageReader?.surface ?: return

            previewRequestBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)?.apply {
                addTarget(previewSurface)
                addTarget(readerSurface)

                // 3A Automatic Hardware Pipeline with OIS
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)

                // Enable Hardware Optical Image Stabilization for shake-free distant scanning
                val oisModes = activeCharacteristics?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                if (oisModes != null && oisModes.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)) {
                    set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
                }

                // Fast noise reduction & edge enhancement for barcode clarity
                set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_FAST)
                set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_FAST)
            }

            cameraDevice?.createCaptureSession(
                listOf(previewSurface, readerSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return
                        captureSession = session
                        applyZoomAndRepeating()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Capture session configuration failed")
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error creating capture session", e)
        }
    }

    // =========================================================================
    // FRAME ANALYSIS (ML Kit)
    // =========================================================================

    private val onImageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        val image = reader.acquireLatestImage() ?: return@OnImageAvailableListener

        if (isScanned || isProcessingFrame.get()) {
            image.close()
            return@OnImageAvailableListener
        }

        isProcessingFrame.set(true)

        try {
            val inputImage = InputImage.fromMediaImage(image, sensorOrientation)
            barcodeScanner.process(inputImage)
                .addOnSuccessListener { barcodes ->
                    if (barcodes.isNotEmpty() && !isScanned) {
                        val raw = barcodes.firstOrNull()?.rawValue
                        if (!raw.isNullOrBlank()) {
                            isScanned = true
                            runOnUiThread { onQrCodeDetected(raw) }
                        }
                    }
                }
                .addOnCompleteListener {
                    image.close()
                    isProcessingFrame.set(false)
                }
        } catch (e: Exception) {
            image.close()
            isProcessingFrame.set(false)
        }
    }

    private fun onQrCodeDetected(text: String) {
        vibratePhone()
        val resultIntent = Intent().apply {
            putExtra(EXTRA_SCAN_RESULT, text)
            putExtra("SCAN_RESULT", text)
            putExtra("SCAN_RESULT_FORMAT", "QR_CODE")
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    // =========================================================================
    // ZOOM & CAMERA SWITCHING
    // =========================================================================

    private fun switchCamera(index: Int) {
        if (index < 0 || index >= discoveredLenses.size || index == activeLensIndex) return

        activeLensIndex = index
        closeCamera()
        openCamera(discoveredLenses[activeLensIndex].cameraId)
    }

    private fun applyZoom(zoomFactor: Float) {
        currentZoomFactor = zoomFactor.coerceIn(minZoomFactor, maxZoomFactor)
        applyZoomAndRepeating()
        updateUIState()
    }

    private fun applyZoomAndRepeating() {
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                activeCharacteristics?.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) != null
            ) {
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, currentZoomFactor)
            } else {
                // Crop region fallback for older HALs
                val activeRect = activeCharacteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                if (activeRect != null) {
                    val cropW = (activeRect.width() / currentZoomFactor).toInt()
                    val cropH = (activeRect.height() / currentZoomFactor).toInt()
                    val cropX = (activeRect.width() - cropW) / 2
                    val cropY = (activeRect.height() - cropH) / 2
                    val cropRect = Rect(cropX, cropY, cropX + cropW, cropY + cropH)
                    builder.set(CaptureRequest.SCALER_CROP_REGION, cropRect)
                }
            }

            previewRequest = builder.build()
            session.setRepeatingRequest(previewRequest!!, null, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Error applying zoom", e)
        }
    }

    private fun triggerFocus(x: Float, y: Float) {
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return
        val rect = activeCharacteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return

        try {
            val focusSize = 150
            val viewW = cameraTextureView.width.coerceAtLeast(1)
            val viewH = cameraTextureView.height.coerceAtLeast(1)

            val sensorX = ((x / viewW) * rect.width()).toInt().coerceIn(0, rect.width())
            val sensorY = ((y / viewH) * rect.height()).toInt().coerceIn(0, rect.height())

            val focusRect = Rect(
                max(0, sensorX - focusSize),
                max(0, sensorY - focusSize),
                min(rect.width(), sensorX + focusSize),
                min(rect.height(), sensorY + focusSize)
            )

            val meteringRect = MeteringRectangle(focusRect, MeteringRectangle.METERING_WEIGHT_MAX)
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(meteringRect))
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(meteringRect))
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)

            session.capture(builder.build(), null, backgroundHandler)

            // Reset trigger
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Error triggering focus", e)
        }
    }

    private fun toggleTorch() {
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return

        try {
            isTorchOn = !isTorchOn
            val flashMode = if (isTorchOn) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF
            builder.set(CaptureRequest.FLASH_MODE, flashMode)
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
            updateTorchUI()
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling torch", e)
        }
    }

    // =========================================================================
    // UI UPDATES
    // =========================================================================

    private fun updateUIState() {
        val currentLens = discoveredLenses.getOrNull(activeLensIndex) ?: return
        val effectiveZoom = currentLens.zoomMultiplier * currentZoomFactor

        tvZoomBadge.text = String.format(Locale.US, "%.1fx", effectiveZoom)

        val linearProgress = ((currentZoomFactor - minZoomFactor) / (maxZoomFactor - minZoomFactor).coerceAtLeast(0.01f)) * 1000
        sbZoom.progress = linearProgress.toInt().coerceIn(0, 1000)

        highlightPresetButtons()
    }

    private fun highlightPresetButtons() {
        val l = discoveredLenses
        when {
            l.size >= 4 -> {
                setChipSelected(btnPreset1x, activeLensIndex == 0)
                setChipSelected(btnPreset2x, activeLensIndex == 1)
                setChipSelected(btnPreset3x, activeLensIndex == 2)
                setChipSelected(btnPreset5x, activeLensIndex == 3)
            }
            l.size == 3 -> {
                setChipSelected(btnPreset1x, activeLensIndex == 0)
                setChipSelected(btnPreset2x, activeLensIndex == 1)
                setChipSelected(btnPreset3x, activeLensIndex == 2 && currentZoomFactor < 1.8f)
                setChipSelected(btnPreset5x, activeLensIndex == 2 && currentZoomFactor >= 1.8f)
            }
            l.size == 2 -> {
                setChipSelected(btnPreset1x, activeLensIndex == 0)
                setChipSelected(btnPreset2x, activeLensIndex == 1 && currentZoomFactor < 1.8f)
                setChipSelected(btnPreset3x, activeLensIndex == 1 && currentZoomFactor in 1.8f..3.5f)
                setChipSelected(btnPreset5x, activeLensIndex == 1 && currentZoomFactor > 3.5f)
            }
            else -> {
                setChipSelected(btnPreset1x, currentZoomFactor < 1.5f)
                setChipSelected(btnPreset2x, currentZoomFactor in 1.5f..2.5f)
                setChipSelected(btnPreset3x, currentZoomFactor in 2.5f..6.0f)
                setChipSelected(btnPreset5x, currentZoomFactor > 6.0f)
            }
        }
    }

    private fun setChipSelected(tv: TextView, on: Boolean) {
        tv.setBackgroundResource(if (on) R.drawable.bg_zoom_chip_selected else R.drawable.bg_zoom_chip_unselected)
        tv.setTextColor(if (on) Color.WHITE else ContextCompat.getColor(this, R.color.photo_grey_text))
    }

    private fun updateTorchUI() {
        btnScannerTorch.setImageResource(if (isTorchOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
        btnScannerTorch.setColorFilter(if (isTorchOn) Color.parseColor("#FFD700") else Color.WHITE)
    }

    // =========================================================================
    // GESTURES & LISTENERS
    // =========================================================================

    private fun setupGestures() {
        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val newZoom = currentZoomFactor * detector.scaleFactor
                applyZoom(newZoom)
                return true
            }
        })

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                triggerFocus(e.x, e.y)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                // Cycle through available lenses
                val nextIndex = (activeLensIndex + 1) % discoveredLenses.size
                switchCamera(nextIndex)
                return true
            }
        })

        viewGestureOverlay.setOnTouchListener { v, event ->
            scaleGestureDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP) v.performClick()
            true
        }
    }

    private fun setupClickListeners() {
        btnScannerBack.setOnClickListener { finish() }
        btnScannerTorch.setOnClickListener { toggleTorch() }

        btnPreset1x.setOnClickListener { onPresetClicked(0) }
        btnPreset2x.setOnClickListener { onPresetClicked(1) }
        btnPreset3x.setOnClickListener { onPresetClicked(2) }
        btnPreset5x.setOnClickListener { onPresetClicked(3) }

        ivZoomMin.setOnClickListener { applyZoom(currentZoomFactor - 0.75f) }
        ivZoomMax.setOnClickListener { applyZoom(currentZoomFactor + 0.75f) }

        sbZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val factor = minZoomFactor + (progress / 1000f) * (maxZoomFactor - minZoomFactor)
                    applyZoom(factor)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun onPresetClicked(buttonIdx: Int) {
        val l = discoveredLenses
        when {
            l.size >= 4 -> {
                if (buttonIdx < l.size) switchCamera(buttonIdx)
            }
            l.size == 3 -> when (buttonIdx) {
                0 -> switchCamera(0) // Ultrawide
                1 -> switchCamera(1) // Main 1x
                2 -> {
                    // Telephoto 4.3x (optical)
                    if (activeLensIndex != 2) switchCamera(2)
                    applyZoom(1.0f)
                }
                3 -> {
                    // 10x Hybrid Telephoto (4.3x optical * 2.3x digital)
                    if (activeLensIndex != 2) switchCamera(2)
                    applyZoom(2.32f)
                }
            }
            l.size == 2 -> when (buttonIdx) {
                0 -> switchCamera(0)
                1 -> {
                    if (activeLensIndex != 1) switchCamera(1)
                    applyZoom(1.0f)
                }
                2 -> {
                    if (activeLensIndex != 1) switchCamera(1)
                    applyZoom(2.0f)
                }
                3 -> {
                    if (activeLensIndex != 1) switchCamera(1)
                    applyZoom(5.0f)
                }
            }
            else -> when (buttonIdx) {
                0 -> applyZoom(1.0f)
                1 -> applyZoom(2.0f)
                2 -> applyZoom(4.0f)
                3 -> applyZoom(10.0f)
            }
        }
    }

    // =========================================================================
    // MATRIX TRANSFORM & SIZING
    // =========================================================================

    private fun chooseOptimalSize(choices: Array<Size>, textureViewWidth: Int, textureViewHeight: Int): Size {
        val targetRatio = 16.0 / 9.0
        val matched = choices.filter {
            val ratio = it.width.toDouble() / it.height.toDouble()
            abs(ratio - targetRatio) < 0.1 || abs((1.0 / ratio) - targetRatio) < 0.1
        }

        return matched.firstOrNull { it.width >= 1920 || it.height >= 1920 }
            ?: matched.firstOrNull { it.width >= 1280 || it.height >= 1280 }
            ?: choices.firstOrNull { it.width >= 1920 }
            ?: choices[0]
    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
        matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
        val scale = max(viewHeight.toFloat() / previewSize.height, viewWidth.toFloat() / previewSize.width)
        matrix.postScale(scale, scale, centerX, centerY)
        cameraTextureView.setTransform(matrix)
    }

    private fun vibratePhone() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                    ?.defaultVibrator?.vibrate(VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    v?.vibrate(VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE))
                else @Suppress("DEPRECATION") v?.vibrate(70)
            }
        } catch (_: Exception) {}
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> { applyZoom(currentZoomFactor + 0.5f); true }
        KeyEvent.KEYCODE_VOLUME_DOWN -> { applyZoom(currentZoomFactor - 0.5f); true }
        else -> super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        if (cameraTextureView.isAvailable && discoveredLenses.isNotEmpty()) {
            openCamera(discoveredLenses[activeLensIndex].cameraId)
        } else {
            cameraTextureView.surfaceTextureListener = surfaceTextureListener
        }
    }

    override fun onPause() {
        closeCamera()
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        barcodeScanner.close()
    }
}
