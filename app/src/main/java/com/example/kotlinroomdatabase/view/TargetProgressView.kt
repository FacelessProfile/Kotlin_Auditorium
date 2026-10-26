package com.example.kotlinroomdatabase.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.example.kotlinroomdatabase.R
import com.example.kotlinroomdatabase.getColorFromAttr

/** A percentage track with the user's comparison target, independent of pass marks. */
class TargetProgressView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    var value: Double? = null
        set(value) { field = value; invalidate() }
    var target: Int = 80
        set(value) { field = value; invalidate() }
    var barColor: Int = context.getColor(R.color.sib_blue_primary)
        set(value) { field = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = resources.displayMetrics.density * 2
        val left = paddingLeft.toFloat() + inset
        val right = width - paddingRight.toFloat() - inset
        val center = height / 2f
        val half = resources.displayMetrics.density * 3
        paint.color = context.getColorFromAttr(R.attr.sib_border_subtle)
        canvas.drawRoundRect(left, center - half, right, center + half, half, half, paint)
        value?.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0)?.let {
            paint.color = barColor
            canvas.drawRoundRect(left, center - half, left + (right - left) * it.toFloat() / 100f, center + half, half, half, paint)
        }
        paint.color = context.getColorFromAttr(R.attr.sib_text_secondary)
        paint.strokeWidth = resources.displayMetrics.density * 1.5f
        val x = left + (right - left) * target.coerceIn(0, 100) / 100f
        canvas.drawLine(x, center - half * 1.8f, x, center + half * 1.8f, paint)
    }
}
