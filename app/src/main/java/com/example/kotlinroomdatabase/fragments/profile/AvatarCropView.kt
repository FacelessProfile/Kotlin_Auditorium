package com.example.kotlinroomdatabase.fragments.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Region
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

class AvatarCropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var sourceBitmap: Bitmap? = null
    private val drawMatrix = Matrix()
    private val savedMatrix = Matrix()

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
    }

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xD9080E1C.toInt() // Deep translucent SibSUTIS background
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0F62FE.toInt() // SibSUTIS primary blue
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
    }

    private val innerBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x80FFFFFF.toInt() // Soft white inner accent line
        style = Paint.Style.STROKE
        strokeWidth = 1f * resources.displayMetrics.density
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33FFFFFF.toInt() // Faint rule-of-thirds framing grid
        style = Paint.Style.STROKE
        strokeWidth = 1f * resources.displayMetrics.density
    }

    private val maskPath = Path()
    private val circlePath = Path()

    var circleCenterX = 0f
        private set
    var circleCenterY = 0f
        private set
    var circleRadius = 0f
        private set

    private var minScale = 0.5f
    private var maxScale = 5.0f

    // Touch gesture handling
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var isDragging = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scaleFactor = detector.scaleFactor
                applyScale(scaleFactor, detector.focusX, detector.focusY)
                return true
            }
        }
    )

    var onCropTransformChanged: (() -> Unit)? = null

    fun setSourceBitmap(bitmap: Bitmap) {
        this.sourceBitmap = bitmap
        if (width > 0 && height > 0) {
            setupInitialTransform()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        circleCenterX = w / 2f
        circleCenterY = h / 2f
        circleRadius = min(w, h) * 0.42f

        circlePath.reset()
        circlePath.addCircle(circleCenterX, circleCenterY, circleRadius, Path.Direction.CW)

        maskPath.reset()
        maskPath.addRect(0f, 0f, w.toFloat(), h.toFloat(), Path.Direction.CW)
        maskPath.addCircle(circleCenterX, circleCenterY, circleRadius, Path.Direction.CCW)

        if (sourceBitmap != null) {
            setupInitialTransform()
        }
    }

    private fun setupInitialTransform() {
        val bitmap = sourceBitmap ?: return
        if (circleRadius <= 0f) return

        val diameter = circleRadius * 2f
        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()

        val scale = max(diameter / bw, diameter / bh)
        minScale = scale * 0.6f
        maxScale = scale * 6.0f

        drawMatrix.reset()
        val scaledW = bw * scale
        val scaledH = bh * scale

        val dx = circleCenterX - (scaledW / 2f)
        val dy = circleCenterY - (scaledH / 2f)

        drawMatrix.postScale(scale, scale)
        drawMatrix.postTranslate(dx, dy)

        invalidate()
        onCropTransformChanged?.invoke()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                lastTouchX = event.getX(0)
                lastTouchY = event.getY(0)
                isDragging = true
            }
            MotionEvent.ACTION_MOVE -> {
                val pointerIndex = event.findPointerIndex(activePointerId)
                if (pointerIndex != -1 && !scaleDetector.isInProgress) {
                    val x = event.getX(pointerIndex)
                    val y = event.getY(pointerIndex)
                    val dx = x - lastTouchX
                    val dy = y - lastTouchY

                    drawMatrix.postTranslate(dx, dy)
                    lastTouchX = x
                    lastTouchY = y
                    invalidate()
                    onCropTransformChanged?.invoke()
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                if (pointerId == activePointerId) {
                    val newIndex = if (pointerIndex == 0) 1 else 0
                    if (newIndex < event.pointerCount) {
                        lastTouchX = event.getX(newIndex)
                        lastTouchY = event.getY(newIndex)
                        activePointerId = event.getPointerId(newIndex)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointerId = MotionEvent.INVALID_POINTER_ID
                isDragging = false
            }
        }
        return true
    }

    private fun applyScale(scaleFactor: Float, focusX: Float, focusY: Float) {
        val values = FloatArray(9)
        drawMatrix.getValues(values)
        val currentScale = values[Matrix.MSCALE_X]

        val targetScale = currentScale * scaleFactor
        val clampedFactor = if (targetScale < minScale) {
            minScale / currentScale
        } else if (targetScale > maxScale) {
            maxScale / currentScale
        } else {
            scaleFactor
        }

        drawMatrix.postScale(clampedFactor, clampedFactor, focusX, focusY)
        invalidate()
        onCropTransformChanged?.invoke()
    }

    fun zoomIn(step: Float = 1.25f) {
        applyScale(step, circleCenterX, circleCenterY)
    }

    fun zoomOut(step: Float = 0.8f) {
        applyScale(step, circleCenterX, circleCenterY)
    }

    fun rotate90Clockwise() {
        drawMatrix.postRotate(90f, circleCenterX, circleCenterY)
        invalidate()
        onCropTransformChanged?.invoke()
    }

    fun resetTransform() {
        setupInitialTransform()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. Draw transformed source bitmap
        sourceBitmap?.let { bitmap ->
            canvas.drawBitmap(bitmap, drawMatrix, bitmapPaint)
        }

        // 2. Draw dark crop mask outside the circular viewport
        canvas.drawPath(maskPath, maskPaint)

        // 3. Draw grid lines inside circle (rule-of-thirds framing)
        canvas.save()
        canvas.clipPath(circlePath)
        val r = circleRadius
        val cx = circleCenterX
        val cy = circleCenterY
        val third = (r * 2f) / 3f

        val line1X = cx - r + third
        val line2X = cx - r + 2 * third
        val line1Y = cy - r + third
        val line2Y = cy - r + 2 * third

        canvas.drawLine(line1X, cy - r, line1X, cy + r, gridPaint)
        canvas.drawLine(line2X, cy - r, line2X, cy + r, gridPaint)
        canvas.drawLine(cx - r, line1Y, cx + r, line1Y, gridPaint)
        canvas.drawLine(cx - r, line2Y, cx + r, line2Y, gridPaint)
        canvas.restore()

        // 4. Draw outer and inner borders
        canvas.drawCircle(circleCenterX, circleCenterY, circleRadius, borderPaint)
        canvas.drawCircle(circleCenterX, circleCenterY, circleRadius - (1.5f * resources.displayMetrics.density), innerBorderPaint)
    }

    /**
     * Extracts the visible circular crop region into a high-res square bitmap.
     */
    fun getCroppedBitmap(outputSize: Int = 512): Bitmap? {
        val bitmap = sourceBitmap ?: return null
        if (circleRadius <= 0f) return null

        val outBitmap = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.ARGB_8888)
        val outCanvas = Canvas(outBitmap)

        val cropMatrix = Matrix(drawMatrix)
        cropMatrix.postTranslate(-(circleCenterX - circleRadius), -(circleCenterY - circleRadius))

        val scaleRatio = outputSize.toFloat() / (circleRadius * 2f)
        cropMatrix.postScale(scaleRatio, scaleRatio)

        outCanvas.drawBitmap(bitmap, cropMatrix, bitmapPaint)
        return outBitmap
    }

    /**
     * Fast circular thumbnail preview generation for the live preview widget.
     */
    fun getCroppedPreviewBitmap(previewSize: Int = 160): Bitmap? {
        val bitmap = sourceBitmap ?: return null
        if (circleRadius <= 0f) return null

        val outBitmap = Bitmap.createBitmap(previewSize, previewSize, Bitmap.Config.ARGB_8888)
        val outCanvas = Canvas(outBitmap)

        // Create circular clip for preview
        val clipPath = Path().apply {
            addCircle(previewSize / 2f, previewSize / 2f, previewSize / 2f, Path.Direction.CW)
        }
        outCanvas.clipPath(clipPath)

        val cropMatrix = Matrix(drawMatrix)
        cropMatrix.postTranslate(-(circleCenterX - circleRadius), -(circleCenterY - circleRadius))

        val scaleRatio = previewSize.toFloat() / (circleRadius * 2f)
        cropMatrix.postScale(scaleRatio, scaleRatio)

        outCanvas.drawBitmap(bitmap, cropMatrix, bitmapPaint)
        return outBitmap
    }
}
