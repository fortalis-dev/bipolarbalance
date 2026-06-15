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
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Dual-line trend chart for Drive level and Sleep hours over time.
 *
 * Drive  — coloured by aquarelle level palette, filled curve
 * Sleep  — soft lavender dashed line, secondary right-hand axis
 *
 * Both use Catmull-Rom smooth curves. Pinch-to-zoom and drag-to-pan are supported.
 */
class HistogramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** (dayKey, driveLevel 1-7) sorted by date */
    var driveSeries: List<Pair<String, Float>> = emptyList()
        set(v) { field = v; invalidate() }

    /** (dayKey, sleepHours 0-24) sorted by date */
    var sleepSeries: List<Pair<String, Float>> = emptyList()
        set(v) { field = v; invalidate() }

    // ── aquarelle colours ─────────────────────────────────────────────────
    private val levelColors = intArrayOf(
        Color.parseColor("#7FA8C0"), Color.parseColor("#6BADB8"),
        Color.parseColor("#7DB5A3"), Color.parseColor("#8CBD8E"),
        Color.parseColor("#D8BA77"), Color.parseColor("#D4916C"),
        Color.parseColor("#C07878"),
    )
    private val colSleep = Color.parseColor("#A98BC4")

    // ── paints ────────────────────────────────────────────────────────────
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EDE6E3"); strokeWidth = 1f; style = Paint.Style.STROKE
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C4B8B4"); strokeWidth = 1.5f; style = Paint.Style.STROKE
    }
    private val drivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 3f; style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val sleepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colSleep; strokeWidth = 2f; style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        alpha = 190
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
    private val yRightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colSleep; textAlign = Paint.Align.LEFT; alpha = 190
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C4B8B4"); textAlign = Paint.Align.CENTER
    }

    // ── zoom / pan  ───────────────────────────────────────────────────────
    private var scaleX = 1f; private var translateX = 0f

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                scaleX = (scaleX * d.scaleFactor).coerceIn(0.4f, 8f)
                clamp(); invalidate(); return true
            }
        })
    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                translateX -= dx; clamp(); invalidate(); return true
            }
        })

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e); gestureDetector.onTouchEvent(e); return true
    }

    private fun clamp() {
        val w = (width - PAD_L - PAD_R) * scaleX
        translateX = translateX.coerceIn(min(0f, width - PAD_L - PAD_R - w), 0f)
    }

    companion object {
        private const val PAD_L = 48f; private const val PAD_R = 48f
        private const val PAD_T = 20f; private const val PAD_B = 44f
    }

    private fun xOf(i: Int, n: Int, cw: Float) =
        PAD_L + translateX + (i.toFloat() / max(1, n - 1)) * cw

    private fun buildSpline(pts: List<PointF>): Path {
        val p = Path(); if (pts.size < 2) return p
        p.moveTo(pts[0].x, pts[0].y)
        for (i in 0 until pts.size - 1) {
            val p0 = if (i == 0) pts[0] else pts[i - 1]
            val p1 = pts[i]; val p2 = pts[i + 1]
            val p3 = if (i + 2 >= pts.size) pts.last() else pts[i + 2]
            p.cubicTo(
                p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f,
                p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f,
                p2.x, p2.y
            )
        }
        return p
    }

    private fun buildFill(pts: List<PointF>, yBase: Float): Path {
        val p = Path(); if (pts.size < 2) return p
        p.moveTo(pts[0].x, yBase); p.lineTo(pts[0].x, pts[0].y)
        for (i in 0 until pts.size - 1) {
            val p0 = if (i == 0) pts[0] else pts[i - 1]
            val p1 = pts[i]; val p2 = pts[i + 1]
            val p3 = if (i + 2 >= pts.size) pts.last() else pts[i + 2]
            p.cubicTo(
                p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f,
                p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f,
                p2.x, p2.y
            )
        }
        p.lineTo(pts.last().x, yBase); p.close(); return p
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (driveSeries.isEmpty()) {
            emptyPaint.textSize = (height * 0.07f).coerceIn(22f, 32f)
            canvas.drawText("Add entries to see trends", width / 2f, height / 2f, emptyPaint)
            return
        }

        val chartW = (width - PAD_L - PAD_R) * scaleX
        val chartH = height - PAD_T - PAD_B
        val n = driveSeries.size

        fun xD(i: Int) = xOf(i, n, chartW)
        fun yD(v: Float) = PAD_T + chartH - ((v - 1f) / 6f) * chartH

        // ── clip ──
        canvas.save()
        canvas.clipRect(PAD_L / 2f, 0f, width.toFloat(), height.toFloat())

        // ── grid + left labels ──
        labelPaint.textSize  = (height * 0.07f).coerceIn(18f, 26f)
        yLabelPaint.textSize = labelPaint.textSize
        yRightPaint.textSize = labelPaint.textSize

        for (lvl in listOf(1f, 3f, 5f, 7f)) {
            val y = yD(lvl)
            canvas.drawLine(PAD_L, y, PAD_L + chartW + translateX, y, gridPaint)
            canvas.drawText("${"%.0f".format(lvl)}", PAD_L - 6f, y + yLabelPaint.textSize / 3f, yLabelPaint)
        }

        // ── right axis labels for sleep ──
        canvas.drawText("24h", PAD_L + chartW + translateX + 4f, PAD_T + yRightPaint.textSize, yRightPaint)
        canvas.drawText("12h", PAD_L + chartW + translateX + 4f, PAD_T + chartH / 2f + yRightPaint.textSize / 2f, yRightPaint)
        canvas.drawText("0h",  PAD_L + chartW + translateX + 4f, PAD_T + chartH, yRightPaint)

        // ── sleep line ──
        val sleepMap = sleepSeries.associateBy { it.first }
        val sleepPts = (0 until n).mapNotNull { i ->
            val h = sleepMap[driveSeries[i].first]?.second ?: return@mapNotNull null
            PointF(xD(i), PAD_T + chartH - (h / 24f) * chartH)
        }
        if (sleepPts.size >= 2) {
            canvas.drawPath(buildSpline(sleepPts), sleepPaint)
        }

        // ── drive fill (gradient) ──
        val drivePts = (0 until n).map { PointF(xD(it), yD(driveSeries[it].second)) }
        if (drivePts.size >= 2) {
            val fillPath = buildFill(drivePts, PAD_T + chartH)
            fillPaint.shader = LinearGradient(
                0f, PAD_T, 0f, PAD_T + chartH,
                intArrayOf(Color.parseColor("#50F4956A"), Color.parseColor("#00F4956A")),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawPath(fillPath, fillPaint)
            fillPaint.shader = null

            // ── drive line per-segment ──
            for (i in 0 until n - 1) {
                val p0 = if (i == 0) drivePts[0] else drivePts[i - 1]
                val p1 = drivePts[i]; val p2 = drivePts[i + 1]
                val p3 = if (i + 2 >= n) drivePts[n - 1] else drivePts[i + 2]
                val seg = Path().apply {
                    moveTo(p1.x, p1.y)
                    cubicTo(
                        p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f,
                        p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f,
                        p2.x, p2.y
                    )
                }
                val cl = ((driveSeries[i].second + driveSeries[i+1].second) / 2).toInt().coerceIn(1, 7)
                drivePaint.color = levelColors[cl - 1]
                canvas.drawPath(seg, drivePaint)
            }
        }

        // ── drive dots ──
        val dr = (height * 0.04f).coerceIn(5f, 9f)
        for (i in 0 until n) {
            val lc = driveSeries[i].second.toInt().coerceIn(1, 7)
            dotPaint.color = levelColors[lc - 1]
            canvas.drawCircle(drivePts[i].x, drivePts[i].y, dr, dotPaint)
            canvas.drawCircle(drivePts[i].x, drivePts[i].y, dr, dotBorder)
        }

        // ── date labels ──
        val stride = max(1, n / 7)
        for (i in 0 until n step stride) {
            canvas.drawText(driveSeries[i].first.substring(5), xD(i), height - PAD_B * 0.1f, labelPaint)
        }

        // ── axes ──
        canvas.drawLine(PAD_L, PAD_T + chartH, PAD_L + chartW + translateX, PAD_T + chartH, axisPaint)
        canvas.drawLine(PAD_L, PAD_T, PAD_L, PAD_T + chartH, axisPaint)

        canvas.restore()
    }
}
