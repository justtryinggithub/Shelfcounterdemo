package com.demo.shelfcounter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/** Draws a frame around every barcode in view: green = counted, amber = being confirmed. */
class BarcodeOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Box(val rect: Rect, val label: String, val confirmed: Boolean)

    private var boxes: List<Box> = emptyList()
    private val density = resources.displayMetrics.density

    private val confirmedColor = 0xFF4ADE80.toInt()
    private val pendingColor = 0xFFFFC107.toInt()

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC000000.toInt() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f * density
        typeface = Typeface.MONOSPACE
    }
    private val rectF = RectF()

    fun setBoxes(newBoxes: List<Box>) {
        boxes = newBoxes
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val corner = 8f * density
        val grow = 4f * density
        val pad = 5f * density

        for (box in boxes) {
            val color = if (box.confirmed) confirmedColor else pendingColor
            rectF.set(box.rect)
            rectF.inset(-grow, -grow)

            fillPaint.color = color
            fillPaint.alpha = 45
            canvas.drawRoundRect(rectF, corner, corner, fillPaint)
            strokePaint.color = color
            canvas.drawRoundRect(rectF, corner, corner, strokePaint)

            val textWidth = textPaint.measureText(box.label)
            val textHeight = textPaint.textSize
            val labelHeight = textHeight + 2 * pad
            var labelTop = rectF.top - labelHeight - grow
            if (labelTop < 0f) labelTop = rectF.bottom + grow
            val labelLeft = rectF.left
            canvas.drawRoundRect(
                labelLeft, labelTop, labelLeft + textWidth + 2 * pad, labelTop + labelHeight,
                corner, corner, labelBgPaint
            )
            canvas.drawText(box.label, labelLeft + pad, labelTop + pad + textHeight * 0.82f, textPaint)
        }
    }
}
