package com.demo.shelfcounter

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Drawn on top of the camera image.
 *  - Live mode: a frame around every barcode in view (green = saved, amber = reading).
 *  - Count mode: the first item (reference) and every identical item found, numbered.
 *  - Drawing: when [drawingEnabled], the user can drag a box around the first item.
 * All coordinates are view coordinates.
 */
class BarcodeOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Box(val rect: Rect, val label: String, val confirmed: Boolean)

    private var barcodeBoxes: List<Box> = emptyList()
    private var referenceBox: RectF? = null
    private var itemBoxes: List<RectF> = emptyList()
    private var dragRect: RectF? = null
    private var dragStartX = 0f
    private var dragStartY = 0f

    var drawingEnabled = false
        set(value) {
            field = value
            if (!value) dragRect = null
            invalidate()
        }

    /** Called with the box the user drew (view coordinates). */
    var onBoxDrawn: ((RectF) -> Unit)? = null

    private val density = resources.displayMetrics.density

    private val confirmedColor = 0xFF4ADE80.toInt()
    private val pendingColor = 0xFFFFC107.toInt()
    private val referenceColor = 0xFF4ADE80.toInt()
    private val itemColor = 0xFF38BDF8.toInt()

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = Color.WHITE
        pathEffect = DashPathEffect(floatArrayOf(10f * density, 6f * density), 0f)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC000000.toInt() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f * density
        typeface = Typeface.MONOSPACE
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 16f * density
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val rectF = RectF()

    fun setBoxes(newBoxes: List<Box>) {
        barcodeBoxes = newBoxes
        invalidate()
    }

    fun setItems(reference: RectF?, others: List<RectF>) {
        referenceBox = reference
        itemBoxes = others
        invalidate()
    }

    fun clearAll() {
        barcodeBoxes = emptyList()
        referenceBox = null
        itemBoxes = emptyList()
        dragRect = null
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!drawingEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartX = event.x
                dragStartY = event.y
                dragRect = RectF(event.x, event.y, event.x, event.y)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                dragRect = RectF(
                    min(dragStartX, event.x), min(dragStartY, event.y),
                    max(dragStartX, event.x), max(dragStartY, event.y)
                )
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val r = dragRect
                dragRect = null
                invalidate()
                val minSize = 40f * density
                if (r != null && abs(r.width()) >= minSize && abs(r.height()) >= minSize) {
                    onBoxDrawn?.invoke(RectF(r))
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                dragRect = null
                invalidate()
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val corner = 8f * density

        drawBarcodes(canvas, corner)

        itemBoxes.forEachIndexed { i, box -> drawItem(canvas, box, i + 2, itemColor, corner) }
        referenceBox?.let { drawItem(canvas, it, 1, referenceColor, corner) }

        dragRect?.let { canvas.drawRoundRect(it, corner, corner, dashPaint) }
    }

    private fun drawItem(canvas: Canvas, box: RectF, number: Int, color: Int, corner: Float) {
        fillPaint.color = color
        fillPaint.alpha = 40
        canvas.drawRoundRect(box, corner, corner, fillPaint)
        strokePaint.color = color
        canvas.drawRoundRect(box, corner, corner, strokePaint)

        val radius = 15f * density
        val cx = max(radius, min(box.left + radius + 4f * density, width - radius))
        val cy = max(radius, min(box.top + radius + 4f * density, height - radius))
        badgePaint.color = color
        canvas.drawCircle(cx, cy, radius, badgePaint)
        canvas.drawText(
            number.toString(), cx,
            cy - (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2f, badgeTextPaint
        )
    }

    private fun drawBarcodes(canvas: Canvas, corner: Float) {
        val grow = 4f * density
        val pad = 5f * density
        for (box in barcodeBoxes) {
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
