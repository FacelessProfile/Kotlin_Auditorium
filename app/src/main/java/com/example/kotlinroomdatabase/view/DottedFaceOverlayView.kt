package com.example.kotlinroomdatabase.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import com.example.kotlinroomdatabase.model.StudentFaceSamplesStatus

class DottedFaceOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var activeAngle: String = StudentFaceSamplesStatus.ANGLE_FRONTAL
        set(value) {
            field = value
            invalidate()
        }

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#99000000") // 60% black background mask
        style = Paint.Style.FILL
    }

    private val dottedBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(3.5f)
        pathEffect = DashPathEffect(floatArrayOf(dpToPx(12f), dpToPx(8f)), 0f)
    }

    private val guideLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80FFFFFF") // 50% white
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(1.5f)
        pathEffect = DashPathEffect(floatArrayOf(dpToPx(6f), dpToPx(6f)), 0f)
    }

    private val accentArrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38BDF8") // vibrant light blue
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(3f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val flashPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private var flashAlpha: Int = 0
    private val ovalRect = RectF()
    private val clipPath = Path()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeOvalRect(w.toFloat(), h.toFloat())
    }

    private fun computeOvalRect(w: Float, h: Float) {
        // Face oval proportions: width is ~65% of screen width, height is ~1.35x width
        val ovalWidth = w * 0.68f
        val ovalHeight = ovalWidth * 1.35f

        val left = (w - ovalWidth) / 2f
        // Position slightly higher than dead center for comfortable camera angle
        val top = (h - ovalHeight) * 0.38f
        val right = left + ovalWidth
        val bottom = top + ovalHeight

        ovalRect.set(left, top, right, bottom)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (ovalRect.isEmpty) return

        val w = width.toFloat()
        val h = height.toFloat()

        // 1. Draw darkened overlay outside the oval hole
        canvas.save()
        clipPath.reset()
        clipPath.addOval(ovalRect, Path.Direction.CW)
        canvas.clipOutPath(clipPath)
        canvas.drawRect(0f, 0f, w, h, maskPaint)
        canvas.restore()

        // 2. Draw dotted oval frame
        canvas.drawOval(ovalRect, dottedBorderPaint)

        // 3. Draw angle-specific guidance inside the oval
        when (activeAngle) {
            StudentFaceSamplesStatus.ANGLE_FRONTAL -> drawFrontalGuides(canvas)
            StudentFaceSamplesStatus.ANGLE_THREE_QUARTER -> drawThreeQuarterGuides(canvas)
            StudentFaceSamplesStatus.ANGLE_PROFILE -> drawProfileGuides(canvas)
        }

        // 4. Shutter flash effect
        if (flashAlpha > 0) {
            flashPaint.alpha = flashAlpha
            canvas.drawRect(0f, 0f, w, h, flashPaint)
        }
    }

    private fun drawFrontalGuides(canvas: Canvas) {
        val cx = ovalRect.centerX()
        val cy = ovalRect.centerY()
        val eyeLevelY = ovalRect.top + ovalRect.height() * 0.38f

        // Horizontal eye-level line
        canvas.drawLine(
            ovalRect.left + dpToPx(24f),
            eyeLevelY,
            ovalRect.right - dpToPx(24f),
            eyeLevelY,
            guideLinePaint
        )

        // Vertical symmetry center line
        canvas.drawLine(
            cx,
            ovalRect.top + dpToPx(20f),
            cx,
            ovalRect.bottom - dpToPx(24f),
            guideLinePaint
        )
    }

    private fun drawThreeQuarterGuides(canvas: Canvas) {
        val cy = ovalRect.centerY()
        val eyeLevelY = ovalRect.top + ovalRect.height() * 0.38f

        // Slightly shifted center axis indicating 45° angle
        val shiftedX = ovalRect.centerX() + ovalRect.width() * 0.15f
        canvas.drawLine(
            shiftedX,
            ovalRect.top + dpToPx(24f),
            shiftedX,
            ovalRect.bottom - dpToPx(24f),
            guideLinePaint
        )

        // Horizontal eye guide
        canvas.drawLine(
            ovalRect.left + dpToPx(24f),
            eyeLevelY,
            ovalRect.right - dpToPx(24f),
            eyeLevelY,
            guideLinePaint
        )

        // Directional turn indicator arrow
        val arrowY = ovalRect.bottom + dpToPx(36f)
        val startX = ovalRect.centerX() - dpToPx(30f)
        val endX = ovalRect.centerX() + dpToPx(30f)

        canvas.drawLine(startX, arrowY, endX, arrowY, accentArrowPaint)
        // Arrow head pointing right
        canvas.drawLine(endX, arrowY, endX - dpToPx(10f), arrowY - dpToPx(8f), accentArrowPaint)
        canvas.drawLine(endX, arrowY, endX - dpToPx(10f), arrowY + dpToPx(8f), accentArrowPaint)
    }

    private fun drawProfileGuides(canvas: Canvas) {
        val cy = ovalRect.centerY()
        val eyeLevelY = ovalRect.top + ovalRect.height() * 0.38f

        // Profile vertical guide placed towards side
        val sideX = ovalRect.right - ovalRect.width() * 0.28f
        canvas.drawLine(
            sideX,
            ovalRect.top + dpToPx(24f),
            sideX,
            ovalRect.bottom - dpToPx(24f),
            guideLinePaint
        )

        // Eye-level crosshair mark
        canvas.drawLine(
            sideX - dpToPx(20f),
            eyeLevelY,
            sideX + dpToPx(20f),
            eyeLevelY,
            guideLinePaint
        )

        // Profile turn arrow
        val arrowY = ovalRect.bottom + dpToPx(36f)
        val startX = ovalRect.centerX() - dpToPx(40f)
        val endX = ovalRect.centerX() + dpToPx(40f)

        canvas.drawLine(startX, arrowY, endX, arrowY, accentArrowPaint)
        canvas.drawLine(endX, arrowY, endX - dpToPx(12f), arrowY - dpToPx(10f), accentArrowPaint)
        canvas.drawLine(endX, arrowY, endX - dpToPx(12f), arrowY + dpToPx(10f), accentArrowPaint)
    }

    fun triggerFlashEffect(onFinished: (() -> Unit)? = null) {
        ValueAnimator.ofInt(220, 0).apply {
            duration = 240
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                flashAlpha = anim.animatedValue as Int
                invalidate()
            }
            doOnEnd { onFinished?.invoke() }
            start()
        }
    }

    private inline fun ValueAnimator.doOnEnd(crossinline action: () -> Unit) {
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) = action()
        })
    }

    private fun dpToPx(dp: Float): Float =
        dp * context.resources.displayMetrics.density
}
