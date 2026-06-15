package com.bipolar.balance

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Tappable vertical thermometer bar used inside the app.
 *
 * Segments are numbered 0 (bottom/lowest) to [segmentCount]-1 (top/highest).
 * Tapping a segment selects it and fills everything at and below that point.
 *
 * For the Drive bar: supply 7 colours in [segmentColors] (index 0 = level 1, blue at bottom).
 * For the Suspend bar: leave [segmentColors] null and set [singleActiveColor] to purple.
 */
class ThermoBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var segmentCount: Int = 7
        set(v) { field = v; invalidate() }

    /** 0-based from bottom. -1 = nothing selected (all segments grey). */
    var selectedIndex: Int = -1
        set(v) { field = v.coerceIn(-1, segmentCount - 1); invalidate() }

    /** Colours per segment, index 0 = bottom. If null, [singleActiveColor] is used. */
    var segmentColors: IntArray? = null
        set(v) { field = v; invalidate() }

    var singleActiveColor: Int = Color.BLUE
        set(v) { field = v; invalidate() }

    var inactiveColor: Int = Color.parseColor("#BDBDBD")
        set(v) { field = v; invalidate() }

    /**
     * Optional text labels per segment, index 0 = bottom.
     * Dense bars (>10) show only every 4th label + extremes.
     */
    var labels: Array<String>? = null
        set(v) { field = v; invalidate() }

    var onSelected: ((index: Int) -> Unit)? = null

    /**
     * Optional relative height weights per segment (index 0 = bottom).
     * When set, segments are drawn proportionally to their weight instead of equal height.
     * Touch detection also uses the same proportions.
     */
    var segmentWeights: FloatArray? = null
        set(v) { field = v; invalidate() }

    private val barPaint   = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface  = Typeface.DEFAULT_BOLD
    }

    /** Tracks the last index we fired [onSelected] for so we don't spam on MOVE. */
    private var lastFiredIndex = -1

    init {
        isClickable = true
        isFocusable = true
    }

    // ── Layout helpers ────────────────────────────────────────────────────────

    /**
     * Returns a FloatArray of size [segmentCount]+1 with cumulative Y breakpoints
     * from top (index 0 = y=0) to bottom (index segmentCount = y=height).
     * Segment [seg] occupies rows[seg]…rows[seg+1] going from bottom to top.
     */
    private fun buildRows(totalH: Float): FloatArray {
        val weights = segmentWeights
        val rows    = FloatArray(segmentCount + 1)
        if (weights == null || weights.size < segmentCount) {
            // equal heights
            val segH = totalH / segmentCount
            for (i in 0..segmentCount) rows[i] = i * segH
        } else {
            val total = weights.take(segmentCount).sum()
            var cum   = 0f
            rows[0]   = 0f
            for (i in 0 until segmentCount) {
                cum      += weights[segmentCount - 1 - i]   // top segment first
                rows[i + 1] = (cum / total) * totalH
            }
        }
        return rows
    }

    /** Maps a touch y-coordinate to a segment index (0 = bottom). */
    private fun segmentForY(touchY: Float, totalH: Float): Int {
        val rows = buildRows(totalH)
        for (row in 0 until segmentCount) {
            if (touchY <= rows[row + 1]) {
                return segmentCount - 1 - row  // convert visual row → logical index from bottom
            }
        }
        return 0
    }

    // ── Touch ─────────────────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val fromBot = segmentForY(event.y, height.toFloat())
                selectedIndex = fromBot
                if (fromBot != lastFiredIndex) {
                    lastFiredIndex = fromBot
                    onSelected?.invoke(fromBot)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                lastFiredIndex = -1
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    // ── Draw ──────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (segmentCount == 0) return

        val w    = width.toFloat()
        val h    = height.toFloat()
        val gap  = 3f
        val rows = buildRows(h)

        for (seg in 0 until segmentCount) {
            val row    = segmentCount - 1 - seg   // visual row index (0 = top)
            val top    = rows[row] + gap / 2f
            val bottom = rows[row + 1] - gap / 2f
            val segH   = bottom - top
            val r      = (segH * 0.18f).coerceIn(3f, 14f)

            val isActive = selectedIndex >= 0 && seg <= selectedIndex
            barPaint.color = if (isActive) {
                segmentColors?.getOrNull(seg) ?: singleActiveColor
            } else {
                inactiveColor
            }
            canvas.drawRoundRect(RectF(gap, top, w - gap, bottom), r, r, barPaint)

            val lbl = labels?.getOrNull(seg)
            if (lbl != null) {
                labelPaint.textSize = (segH * 0.40f).coerceIn(9f, 22f)
                val showLabel = segmentCount <= 10 ||
                        seg == 0 || seg == segmentCount - 1 || seg % 4 == 0
                if (showLabel) {
                    labelPaint.color =
                        if (isActive) Color.WHITE else Color.parseColor("#9E9E9E")
                    canvas.drawText(
                        lbl,
                        w / 2f,
                        top + segH / 2f + labelPaint.textSize / 3f,
                        labelPaint
                    )
                }
            }
        }
    }
}