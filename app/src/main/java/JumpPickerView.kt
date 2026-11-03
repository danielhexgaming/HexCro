package com.example.hexcro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max

class JumpPickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var screenshot: Bitmap? = null
    private var jumpIcon: Bitmap? = null

    private val drawRect = RectF()

    private var iconCenterX = 0.85f
    private var iconCenterY = 0.78f
    private var iconSizeRatio = 0.14f
    private var iconOpacity = 200

    private var dragging = false
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f

    private var activeCorner = -1     // 0=TL, 1=TR, 2=BL, 3=BR
    private var anchorX = 0f
    private var anchorY = 0f

    private val screenshotPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val iconPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = iconOpacity }
    private val outlinePaint = Paint().apply {
        color = 0xFF6C5CE7.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }
    private val handleFillPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val handleStrokePaint = Paint().apply {
        color = 0xFF6C5CE7.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    private fun handleRadiusPx() = (12 * resources.displayMetrics.density).toInt()
    private fun hitRadiusPx() = (28 * resources.displayMetrics.density).toInt()

    fun setScreenshot(bmp: Bitmap?) {
        screenshot = bmp
        recalcDrawRect()
        invalidate()
    }

    fun setJumpIcon(bmp: Bitmap?) {
        jumpIcon = bmp
        invalidate()
    }

    fun setIconOpacity(opacity: Int) {
        iconOpacity = opacity.coerceIn(0, 255)
        iconPaint.alpha = iconOpacity
        invalidate()
    }

    fun setInitialPosition(xRatio: Float, yRatio: Float) {
        iconCenterX = xRatio
        iconCenterY = yRatio
        invalidate()
    }

    fun setInitialSize(sizeRatio: Float) {
        iconSizeRatio = sizeRatio
        invalidate()
    }

    fun getCenterRatio(): Pair<Float, Float> = iconCenterX to iconCenterY
    fun getSizeRatio(): Float = iconSizeRatio

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recalcDrawRect()
    }

    private fun recalcDrawRect() {
        val bmp = screenshot ?: run {
            drawRect.set(0f, 0f, width.toFloat(), height.toFloat())
            return
        }
        val bmpAspect = bmp.width.toFloat() / bmp.height.toFloat()
        val viewAspect = width.toFloat() / height.toFloat()

        if (bmpAspect > viewAspect) {
            val scaledH = width / bmpAspect
            val top = (height - scaledH) / 2f
            drawRect.set(0f, top, width.toFloat(), top + scaledH)
        } else {
            val scaledW = height * bmpAspect
            val left = (width - scaledW) / 2f
            drawRect.set(left, 0f, left + scaledW, height.toFloat())
        }
    }

    private fun iconRect(): RectF {
        val cx = drawRect.left + iconCenterX * drawRect.width()
        val cy = drawRect.top + iconCenterY * drawRect.height()
        val half = (iconSizeRatio * drawRect.width()) / 2f
        return RectF(cx - half, cy - half, cx + half, cy + half)
    }

    private fun cornerPoints(r: RectF): List<Pair<Float, Float>> = listOf(
        r.left  to r.top,
        r.right to r.top,
        r.left  to r.bottom,
        r.right to r.bottom
    )

    private fun oppositeCornerIndex(c: Int): Int = when (c) {
        0 -> 3; 1 -> 2; 2 -> 1; else -> 0
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        screenshot?.let { canvas.drawBitmap(it, null, drawRect, screenshotPaint) }
        jumpIcon?.let { icon ->
            val r = iconRect()
            canvas.drawBitmap(icon, null, r, iconPaint)
            canvas.drawRect(r, outlinePaint)
            cornerPoints(r).forEach { (x, y) ->
                canvas.drawCircle(x, y, handleRadiusPx().toFloat(), handleFillPaint)
                canvas.drawCircle(x, y, handleRadiusPx().toFloat(), handleStrokePaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val r = iconRect()
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // 1) corner handle?
                val hit = hitRadiusPx().toFloat()
                cornerPoints(r).forEachIndexed { i, (x, y) ->
                    if (activeCorner == -1 && abs(event.x - x) < hit && abs(event.y - y) < hit) {
                        activeCorner = i
                        val opp = oppositeCornerIndex(i)
                        val (ax, ay) = cornerPoints(r)[opp]
                        anchorX = ax
                        anchorY = ay
                    }
                }
                if (activeCorner != -1) return true

                // 2) icon body?
                if (r.contains(event.x, event.y)) {
                    dragging = true
                    dragOffsetX = event.x - r.centerX()
                    dragOffsetY = event.y - r.centerY()
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (drawRect.width() <= 0f) return false

                if (activeCorner != -1) {
                    // keep anchor fixed, resize square to follow finger
                    val dx = abs(event.x - anchorX)
                    val dy = abs(event.y - anchorY)
                    val newSizePx = max(dx, dy).coerceAtLeast(drawRect.width() * 0.03f)
                    val newSizeRatio = (newSizePx / drawRect.width()).coerceIn(0.03f, 0.6f)
                    iconSizeRatio = newSizeRatio

                    // New center is halfway between anchor and dragged corner.
                    // Compute the direction of the dragged corner relative to anchor.
                    val signX = if (event.x > anchorX) 1f else -1f
                    val signY = if (event.y > anchorY) 1f else -1f
                    val newCxPx = anchorX + signX * newSizePx / 2f
                    val newCyPx = anchorY + signY * newSizePx / 2f
                    iconCenterX = ((newCxPx - drawRect.left) / drawRect.width()).coerceIn(0f, 1f)
                    iconCenterY = ((newCyPx - drawRect.top) / drawRect.height()).coerceIn(0f, 1f)
                    invalidate()
                    return true
                }

                if (dragging) {
                    val newCx = ((event.x - dragOffsetX - drawRect.left) / drawRect.width()).coerceIn(0f, 1f)
                    val newCy = ((event.y - dragOffsetY - drawRect.top) / drawRect.height()).coerceIn(0f, 1f)
                    iconCenterX = newCx
                    iconCenterY = newCy
                    invalidate()
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                activeCorner = -1
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}