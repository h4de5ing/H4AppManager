package com.github.appmanager.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import com.github.appmanager.R
import kotlin.math.roundToInt

/**
 * 圆角小标签（如「系统」）。以 compound drawable 形式挂在 TextView 末尾，
 * 文字被省略时标签依旧完整显示。
 */
class TagDrawable(context: Context, private val text: String) : Drawable() {
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.accentText)
        textSize = context.sp(10f)
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.accentSoft)
    }
    private val radius = context.dp(6f).toFloat()
    private val width = (textPaint.measureText(text) + context.dp(6f) * 2).roundToInt()
    private val height = context.dp(16f)
    private val rect = RectF()

    override fun getIntrinsicWidth(): Int = width

    override fun getIntrinsicHeight(): Int = height

    override fun draw(canvas: Canvas) {
        rect.set(bounds)
        canvas.drawRoundRect(rect, radius, radius, backgroundPaint)
        val metrics = textPaint.fontMetrics
        val baseline = rect.centerY() - (metrics.ascent + metrics.descent) / 2
        canvas.drawText(text, rect.centerX(), baseline, textPaint)
    }

    override fun setAlpha(alpha: Int) {
        textPaint.alpha = alpha
        backgroundPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        textPaint.colorFilter = colorFilter
        backgroundPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
