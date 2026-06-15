package com.bipolar.balance

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/**
 * Shows raw drive entries logged during a single day as a smooth Catmull-Rom curve.
 * X axis = time of day (00:00–24:00), Y axis = drive level (1–7).
 * Uses aquarelle level colours with a gentle gradient fill below the curve.
 */
class IntraDayGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Raw drive entries for the selected day, sorted by timestamp. */
    var entries: List<DriveEntry> = emptyList()
        set(v) { field = v; invalidate() }

    /** Day key string (yyyy-MM-dd) — used to compute midnight offset. */
    var dayKey: String = ""
        set(v) { field = v; invalidate() }

    // colours matching the aquarelle level palette
    private val levelColors = intArrayOf(
        Color.parseColor("#7FA8C0"),
        Color.parseColor("#6BADB8"),
        Color.parseColor("#7DB5A3"),
        Color.parseColor("#8CBD8E"),
        Color.parseColor("#D8BA77"),
        Color.parseColor("#D4916C"),
        Color.parseColor("#C07878"),
    )

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EDE6E3"); strokeWidth = 1f; style = Paint.Style.STROKE
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C4B8B4"); strokeWidth = 1.5f; style = Paint.Style.STROKE
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 3f; style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dotPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dotBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFCFA"); strokeWidth = 2f; style = Paint.Style.STROKE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8A7A76"); textAlign = Paint.Align.CENTER
    }
    private val yLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A09090"); textAlign = Paint.Align.RIGHT
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C4B8B4"); textAlign = Paint.Align.CENTER
    }

    companion object {
        private const val PAD_L = 48f; private const val PAD_R = 16f
        private const val PAD_T = 16f; private const val PAD_B = 40f
        private const val DAY_MS = 86_400_000L
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val chartW = width - PAD_L - PAD_R
        val chartH = height - PAD_T - PAD_B

        labelPaint.textSize  = (height * 0.10f).coerceIn(18f, 28f)
        yLabelPaint.textSize = labelPaint.textSize
        emptyPaint.textSize  = (height * 0.12f).coerceIn(20f, 32f)

        if (entries.isEmpty()) {
            canvas.drawText("No inputs this day", width / 2f, height / 2f + emptyPaint.textSize / 3f, emptyPaint)
            return
        }

        val midnightMs = DataRepository.dayKeyToMs(dayKey)
        fun xOf(tsMs: Long) = PAD_L + ((tsMs - midnightMs).toFloat() / DAY_MS) * chartW
        fun yOf(level: Int) = PAD_T + chartH - ((level - 1).toFloat() / 6f) * chartH

        // ── hour grid lines ──
        for (h in listOf(6, 12, 18)) {
            val x = PAD_L + (h / 24f) * chartW
            canvas.drawLine(x, PAD_T, x, PAD_T + chartH, gridPaint)
            canvas.drawText("${h}h", x, height - PAD_B * 0.05f, labelPaint)
        }
        // Y grid levels
        for (lvl in 1..7) {
            val y = yOf(lvl)
            canvas.drawLine(PAD_L, y, PAD_L + chartW, y, gridPaint)
            canvas.drawText("$lvl", PAD_L - 6f, y + yLabelPaint.textSize / 3f, yLabelPaint)
        }

        // ── axes ──
        canvas.drawLine(PAD_L, PAD_T + chartH, PAD_L + chartW, PAD_T + chartH, axisPaint)
        canvas.drawLine(PAD_L, PAD_T, PAD_L, PAD_T + chartH, axisPaint)

        val pts = entries.map { PointF(xOf(it.timestampMs), yOf(it.level)) }

        // average colour for fill gradient
        val avgLevel = entries.map { it.level }.average().toInt().coerceIn(1, 7)
        val avgColor = levelColors[avgLevel - 1]
        val r = Color.red(avgColor); val g = Color.green(avgColor); val b = Color.blue(avgColor)

        if (pts.size >= 2) {
            // ── smooth fill ──
            val fillPath = buildSmoothFill(pts, PAD_T + chartH)
            fillPaint.shader = LinearGradient(
                0f, PAD_T, 0f, PAD_T + chartH,
                intArrayOf(Color.argb(80, r, g, b), Color.argb(0, r, g, b)),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawPath(fillPath, fillPaint)
            fillPaint.shader = null

            // ── smooth line (per-segment coloured) ──
            for (i in 0 until pts.size - 1) {
                val p0 = if (i == 0) pts[0] else pts[i - 1]
                val p1 = pts[i]; val p2 = pts[i + 1]
                val p3 = if (i + 2 >= pts.size) pts[pts.size - 1] else pts[i + 2]
                val cp1x = p1.x + (p2.x - p0.x) / 6f; val cp1y = p1.y + (p2.y - p0.y) / 6f
                val cp2x = p2.x - (p3.x - p1.x) / 6f; val cp2y = p2.y - (p3.y - p1.y) / 6f
                val seg = Path().apply { moveTo(p1.x, p1.y); cubicTo(cp1x, cp1y, cp2x, cp2y, p2.x, p2.y) }
                val midLevel = ((entries[i].level + entries[i + 1].level) / 2).coerceIn(1, 7)
                linePaint.color = levelColors[midLevel - 1]
                canvas.drawPath(seg, linePaint)
            }
        }

        // ── dots ──
        val dotR = (height * 0.06f).coerceIn(6f, 11f)
        for ((idx, pt) in pts.withIndex()) {
            dotPaint.color = levelColors[(entries[idx].level - 1).coerceIn(0, 6)]
            canvas.drawCircle(pt.x, pt.y, dotR, dotPaint)
            canvas.drawCircle(pt.x, pt.y, dotR, dotBorder)
        }
    }

    private fun buildSmoothFill(pts: List<PointF>, yBase: Float): Path {
        val path = Path()
        if (pts.size < 2) return path
        path.moveTo(pts[0].x, yBase); path.lineTo(pts[0].x, pts[0].y)
        for (i in 0 until pts.size - 1) {
            val p0 = if (i == 0) pts[0] else pts[i - 1]
            val p1 = pts[i]; val p2 = pts[i + 1]
            val p3 = if (i + 2 >= pts.size) pts[pts.size - 1] else pts[i + 2]
            val cp1x = p1.x + (p2.x - p0.x) / 6f; val cp1y = p1.y + (p2.y - p0.y) / 6f
            val cp2x = p2.x - (p3.x - p1.x) / 6f; val cp2y = p2.y - (p3.y - p1.y) / 6f
            path.cubicTo(cp1x, cp1y, cp2x, cp2y, p2.x, p2.y)
        }
        path.lineTo(pts.last().x, yBase); path.close()
        return path
    }
}
