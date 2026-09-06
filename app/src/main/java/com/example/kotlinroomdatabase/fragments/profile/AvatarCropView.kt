package com.example.kotlinroomdatabase.fragments.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Region
import android.graphics.Shader
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class AvatarCropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var sourceBitmap: Bitmap? = null
    private var smartBg: SmartBackground? = null

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
        this.smartBg = analyzeBackground(bitmap)
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
        minScale = scale * 0.5f
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

        // 1. Draw transformed source bitmap with smart background extension inside the circle
        canvas.save()
        canvas.clipPath(circlePath)

        val cropBounds = RectF(
            circleCenterX - circleRadius,
            circleCenterY - circleRadius,
            circleCenterX + circleRadius,
            circleCenterY + circleRadius
        )

        sourceBitmap?.let { bitmap ->
            val bmBounds = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
            drawMatrix.mapRect(bmBounds)

            smartBg?.draw(canvas, cropBounds, bmBounds)
            canvas.drawBitmap(bitmap, drawMatrix, bitmapPaint)
        } ?: run {
            smartBg?.draw(canvas, cropBounds)
        }
        canvas.restore()

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
     * Fills any unrendered areas with seamless background continuation to eliminate black borders.
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

        val outBounds = RectF(0f, 0f, outputSize.toFloat(), outputSize.toFloat())
        val bmBounds = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        cropMatrix.mapRect(bmBounds)

        smartBg?.draw(outCanvas, outBounds, bmBounds)
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

        val outBounds = RectF(0f, 0f, previewSize.toFloat(), previewSize.toFloat())
        val bmBounds = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        cropMatrix.mapRect(bmBounds)

        smartBg?.draw(outCanvas, outBounds, bmBounds)
        outCanvas.drawBitmap(bitmap, cropMatrix, bitmapPaint)
        return outBitmap
    }

    /**
     * Data class and renderer for intelligent content-aware background continuation.
     */
    class SmartBackground(
        val isUniform: Boolean,
        val uniformColor: Int,
        val isVerticalGradient: Boolean,
        val topColor: Int,
        val bottomColor: Int,
        val ambientBitmap: Bitmap?
    ) {
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val ambientPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            isFilterBitmap = true
            isDither = true
        }

        fun draw(
            canvas: Canvas,
            fillBounds: RectF,
            bmBounds: RectF? = null
        ) {
            if (isUniform) {
                bgPaint.shader = null
                bgPaint.color = uniformColor
                canvas.drawRect(fillBounds, bgPaint)
                return
            }

            if (isVerticalGradient) {
                val y0 = bmBounds?.top ?: fillBounds.top
                val y1 = bmBounds?.bottom ?: fillBounds.bottom
                bgPaint.shader = LinearGradient(
                    fillBounds.centerX(), y0,
                    fillBounds.centerX(), y1,
                    topColor, bottomColor,
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(fillBounds, bgPaint)
                return
            }

            // Complex / non-uniform background: ambient continuation
            bgPaint.shader = null
            bgPaint.color = uniformColor
            canvas.drawRect(fillBounds, bgPaint)

            if (ambientBitmap != null && !ambientBitmap.isRecycled) {
                canvas.drawBitmap(ambientBitmap, null, fillBounds, ambientPaint)
            }
        }
    }

    private fun analyzeBackground(bitmap: Bitmap): SmartBackground {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) {
            return SmartBackground(
                isUniform = true,
                uniformColor = Color.WHITE,
                isVerticalGradient = false,
                topColor = Color.WHITE,
                bottomColor = Color.WHITE,
                ambientBitmap = null
            )
        }

        val insetX = (w * 0.02f).toInt().coerceIn(1, 4)
        val insetY = (h * 0.02f).toInt().coerceIn(1, 4)

        val sampleCount = 36
        val topColors = ArrayList<Int>(sampleCount)
        val bottomColors = ArrayList<Int>(sampleCount)
        val leftColors = ArrayList<Int>(sampleCount)
        val rightColors = ArrayList<Int>(sampleCount)
        val upperColors = ArrayList<Int>(sampleCount * 2)

        for (i in 0 until sampleCount) {
            val x = insetX + ((w - 1 - 2 * insetX) * i) / (sampleCount - 1)
            val cTop = bitmap.getPixel(x, insetY)
            val cBottom = bitmap.getPixel(x, h - 1 - insetY)
            topColors.add(cTop)
            bottomColors.add(cBottom)
            upperColors.add(cTop)
        }

        for (i in 0 until sampleCount) {
            val y = insetY + ((h - 1 - 2 * insetY) * i) / (sampleCount - 1)
            val cLeft = bitmap.getPixel(insetX, y)
            val cRight = bitmap.getPixel(w - 1 - insetX, y)
            leftColors.add(cLeft)
            rightColors.add(cRight)
            if (i < sampleCount * 0.45f) {
                upperColors.add(cLeft)
                upperColors.add(cRight)
            }
        }

        val allColors = ArrayList<Int>(sampleCount * 4).apply {
            addAll(topColors)
            addAll(bottomColors)
            addAll(leftColors)
            addAll(rightColors)
        }

        val (allColor, allStdDev) = calculateColorStats(allColors)
        val (upperColor, upperStdDev) = calculateColorStats(upperColors)
        val (topColor, topStdDev) = calculateColorStats(topColors)
        val (bottomColor, bottomStdDev) = calculateColorStats(bottomColors)

        // 1. All 4 edges are uniform (e.g. logo, avatar on solid backdrop, studio shot)
        if (allStdDev <= 28.0f) {
            return SmartBackground(
                isUniform = true,
                uniformColor = allColor,
                isVerticalGradient = false,
                topColor = allColor,
                bottomColor = allColor,
                ambientBitmap = null
            )
        }

        // 2. Upper backdrop is solid (portrait with clothing/shoulders at bottom)
        if (upperStdDev <= 24.0f) {
            return SmartBackground(
                isUniform = true,
                uniformColor = upperColor,
                isVerticalGradient = false,
                topColor = upperColor,
                bottomColor = upperColor,
                ambientBitmap = null
            )
        }

        // 3. Studio lighting vertical gradient
        if (topStdDev <= 26.0f && bottomStdDev <= 26.0f) {
            return SmartBackground(
                isUniform = false,
                uniformColor = topColor,
                isVerticalGradient = true,
                topColor = topColor,
                bottomColor = bottomColor,
                ambientBitmap = null
            )
        }

        // 4. Non-uniform background (complex scene): create smooth ambient blur
        val ambient = createAmbientBlur(bitmap)
        return SmartBackground(
            isUniform = false,
            uniformColor = upperColor,
            isVerticalGradient = false,
            topColor = topColor,
            bottomColor = bottomColor,
            ambientBitmap = ambient
        )
    }

    private fun calculateColorStats(colors: List<Int>): Pair<Int, Float> {
        if (colors.isEmpty()) return Pair(Color.WHITE, 0f)
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        val n = colors.size.toDouble()

        for (c in colors) {
            sumR += Color.red(c)
            sumG += Color.green(c)
            sumB += Color.blue(c)
        }

        val avgR = (sumR / n).toFloat()
        val avgG = (sumG / n).toFloat()
        val avgB = (sumB / n).toFloat()

        var variance = 0.0
        for (c in colors) {
            val dr = Color.red(c) - avgR
            val dg = Color.green(c) - avgG
            val db = Color.blue(c) - avgB
            variance += (dr * dr + dg * dg + db * db)
        }

        val stdDev = sqrt(variance / n).toFloat()
        val avgColor = Color.rgb(
            avgR.toInt().coerceIn(0, 255),
            avgG.toInt().coerceIn(0, 255),
            avgB.toInt().coerceIn(0, 255)
        )
        return Pair(avgColor, stdDev)
    }

    private fun createAmbientBlur(source: Bitmap): Bitmap? {
        return try {
            val size = 48
            val small = Bitmap.createScaledBitmap(source, size, size, true)
            fastBoxBlur(small, radius = 5, iterations = 2)
            small
        } catch (e: Exception) {
            Log.e("AvatarCropView", "Error creating ambient blur", e)
            null
        }
    }

    private fun fastBoxBlur(bitmap: Bitmap, radius: Int, iterations: Int) {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val temp = IntArray(w * h)

        for (it in 0 until iterations) {
            // Horizontal blur
            for (y in 0 until h) {
                var rSum = 0
                var gSum = 0
                var bSum = 0
                val rowOffset = y * w

                for (i in -radius..radius) {
                    val px = pixels[rowOffset + i.coerceIn(0, w - 1)]
                    rSum += Color.red(px)
                    gSum += Color.green(px)
                    bSum += Color.blue(px)
                }

                val windowSize = radius * 2 + 1
                for (x in 0 until w) {
                    temp[rowOffset + x] = Color.rgb(rSum / windowSize, gSum / windowSize, bSum / windowSize)

                    val removeX = (x - radius).coerceIn(0, w - 1)
                    val addX = (x + radius + 1).coerceIn(0, w - 1)
                    val removePx = pixels[rowOffset + removeX]
                    val addPx = pixels[rowOffset + addX]

                    rSum += Color.red(addPx) - Color.red(removePx)
                    gSum += Color.green(addPx) - Color.green(removePx)
                    bSum += Color.blue(addPx) - Color.blue(removePx)
                }
            }

            // Vertical blur
            for (x in 0 until w) {
                var rSum = 0
                var gSum = 0
                var bSum = 0

                for (i in -radius..radius) {
                    val px = temp[i.coerceIn(0, h - 1) * w + x]
                    rSum += Color.red(px)
                    gSum += Color.green(px)
                    bSum += Color.blue(px)
                }

                val windowSize = radius * 2 + 1
                for (y in 0 until h) {
                    pixels[y * w + x] = Color.rgb(rSum / windowSize, gSum / windowSize, bSum / windowSize)

                    val removeY = (y - radius).coerceIn(0, h - 1)
                    val addY = (y + radius + 1).coerceIn(0, h - 1)
                    val removePx = temp[removeY * w + x]
                    val addPx = temp[addY * w + x]

                    rSum += Color.red(addPx) - Color.red(removePx)
                    gSum += Color.green(addPx) - Color.green(removePx)
                    bSum += Color.blue(addPx) - Color.blue(removePx)
                }
            }
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }
}
