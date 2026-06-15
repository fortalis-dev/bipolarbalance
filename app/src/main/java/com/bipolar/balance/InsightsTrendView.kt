package com.bipolar.balance

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/**
 * Dual-line trend chart for the Insights tab.
 * - [driveSeries]: (dayKey, driveLevel 1–7) — final daily drive (manual override or auto avg)
 * - [autoDriveSeries]: (dayKey, autoDriveLevel 1–7) — always the auto-computed average
 * - [sleepSeries]: (dayKey, sleepHours 0–15) shown as a dashed lavender overlay
 * All axes are labelled on left (Drive 1–7) and right (Sleep 0–15).
 */
class InsightsTrendView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var driveSeries: List<Pair<String, Float>> = emptyList()
        set(v) { field = v; invalidate() }

    /** Auto-computed average drive per day — never includes manual overrides. */
    var autoDriveSeries: List<Pair<String, Float>> = emptyList()
        set(v) { field = v; invalidate() }

    var sleepSeries: List<Pair<String, Float>> = emptyList()
        set(v) { field = v; invalidate() }

    // ── paints ───────────────────────────────────────────────────────────
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EDE6E3"); strokeWidth = 1f; style = Paint.Style.STROKE
    }
    private val drivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6B9FBF"); strokeWidth = 3f
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val autoDrivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D4916C"); strokeWidth = 2f
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(12f, 4f), 0f); alpha = 200
    }
    private val dotAutoDrivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D4916C"); style = Paint.Style.FILL; alpha = 200
    }
    private val sleepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A98BC4"); strokeWidth = 2f
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(8f, 5f), 0f); alpha = 200
    }
    private val dotDrivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6B9FBF"); style = Paint.Style.FILL
    }
    private val dotSleepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A98BC4"); style = Paint.Style.FILL; alpha = 200
    }
    private val leftLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6B9FBF"); textAlign = Paint.Align.RIGHT
    }
    private val rightLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A98BC4"); textAlign = Paint.Align.LEFT; alpha = 200
    }
    private val xLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8A7A76"); textAlign = Paint.Align.CENTER
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C4B8B4"); textAlign = Paint.Align.CENTER
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C4B8B4"); strokeWidth = 1.5f; style = Paint.Style.STROKE
    }

    companion object {
        private const val PAD_LEFT   = 56f
        private const val PAD_RIGHT  = 56f
        private const val PAD_TOP    = 24f
        private const val PAD_BOTTOM = 52f
    }

    private fun buildSmoothPath(pts: List<PointF>): Path {
        val path = Path()
        if (pts.size < 2) return path
        path.moveTo(pts[0].x, pts[0].y)
        for (i in 0 until pts.size - 1) {
            val p0 = if (i == 0) pts[0] else pts[i - 1]
            val p1 = pts[i]; val p2 = pts[i + 1]
            val p3 = if (i + 2 >= pts.size) pts[pts.size - 1] else pts[i + 2]
            val cp1x = p1.x + (p2.x - p0.x) / 6f; val cp1y = p1.y + (p2.y - p0.y) / 6f
            val cp2x = p2.x - (p3.x - p1.x) / 6f; val cp2y = p2.y - (p3.y - p1.y) / 6f
            path.cubicTo(cp1x, cp1y, cp2x, cp2y, p2.x, p2.y)
        }
        return path
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = driveSeries.size
        if (n < 2) {
            emptyPaint.textSize = 34f
            canvas.drawText("Not enough data yet", width / 2f, height / 2f, emptyPaint)
            return
        }

        val chartW = width  - PAD_LEFT - PAD_RIGHT
        val chartH = height - PAD_TOP  - PAD_BOTTOM

        fun xOf(i: Int) = PAD_LEFT + (i.toFloat() / max(1, n - 1)) * chartW

        // Drive: 1–7 range
        fun yDrive(v: Float) = PAD_TOP + chartH - ((v - 1f) / 6f) * chartH
        // Sleep: 0–15 range
        fun ySleep(h: Float) = PAD_TOP + chartH - (h / 15f) * chartH

        // ── grid (Drive y-axis at 7 levels) ──
        leftLabelPaint.textSize  = (height * 0.03f).coerceIn(18f, 26f)
        rightLabelPaint.textSize = leftLabelPaint.textSize
        xLabelPaint.textSize     = leftLabelPaint.textSize

        for (level in 1..7) {
            val y = yDrive(level.toFloat())
            canvas.drawLine(PAD_LEFT, y, PAD_LEFT + chartW, y, gridPaint)
            canvas.drawText("$level", PAD_LEFT - 6f, y + leftLabelPaint.textSize / 3f, leftLabelPaint)
        }
        // Sleep axis ticks on right (0, 5, 10, 15)
        for (h in listOf(0, 5, 10, 15)) {
            val y = ySleep(h.toFloat())
            canvas.drawText("${h}h", PAD_LEFT + chartW + 6f, y + rightLabelPaint.textSize / 3f, rightLabelPaint)
        }

        // ── sleep line ──
        val sleepMap = sleepSeries.associateBy { it.first }
        val sleepPts = (0 until n).mapNotNull { i ->
            val h = sleepMap[driveSeries[i].first]?.second ?: return@mapNotNull null
            PointF(xOf(i), ySleep(h))
        }
        if (sleepPts.size >= 2) {
            canvas.drawPath(buildSmoothPath(sleepPts), sleepPaint)
            sleepPts.forEach { canvas.drawCircle(it.x, it.y, 4f, dotSleepPaint) }
        }

        // ── auto-drive line (dashed, terracotta) — only if overrides exist ──
        val autoMap = autoDriveSeries.associateBy { it.first }
        val hasOverrides = (0 until n).any { i ->
            val manual = driveSeries[i].second
            val auto   = autoMap[driveSeries[i].first]?.second
            auto != null && auto != manual
        }
        if (hasOverrides && autoDriveSeries.size >= 2) {
            val autoPts = (0 until n).mapNotNull { i ->
                val v = autoMap[driveSeries[i].first]?.second ?: return@mapNotNull null
                PointF(xOf(i), yDrive(v))
            }
            if (autoPts.size >= 2) {
                canvas.drawPath(buildSmoothPath(autoPts), autoDrivePaint)
                autoPts.forEach { canvas.drawCircle(it.x, it.y, 3f, dotAutoDrivePaint) }
            }
        }

        // ── drive line ──
        val drivePts = (0 until n).map { i -> PointF(xOf(i), yDrive(driveSeries[i].second)) }
        canvas.drawPath(buildSmoothPath(drivePts), drivePaint)
        drivePts.forEach { canvas.drawCircle(it.x, it.y, 5f, dotDrivePaint) }

        // ── x-axis labels ──
        val stride = max(1, n / 7)
        for (i in 0 until n step stride) {
            canvas.drawText(
                driveSeries[i].first.substring(5),
                xOf(i), height - PAD_BOTTOM * 0.1f, xLabelPaint
            )
        }

        // ── axes ──
        canvas.drawLine(PAD_LEFT, PAD_TOP, PAD_LEFT, PAD_TOP + chartH, axisPaint)
        canvas.drawLine(PAD_LEFT, PAD_TOP + chartH, PAD_LEFT + chartW, PAD_TOP + chartH, axisPaint)
    }
}
