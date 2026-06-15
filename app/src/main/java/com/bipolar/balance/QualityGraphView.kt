package com.bipolar.balance

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Beautiful quality-over-time line chart with:
 * - Catmull-Rom smooth curves (no jagged straight lines)
 * - Optional Sleep overlay (thin dashed lavender line, secondary y-axis)
 * - Tap a dot to select a day (fires [onDayTapped])
 * - Highlighted dot for selected day
 * - Aquarelle/watercolour colour palette
 */
class QualityGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** (dayKey, qualityScore) sorted ascending. */
    var series: List<Pair<String, Float>> = emptyList()
        set(v) { field = v; invalidate() }

    /** (dayKey, suspendHours) sorted ascending – overlay on secondary axis. */
    var suspendSeries: List<Pair<String, Float>> = emptyList()
        set(v) { field = v; invalidate() }

    var showSuspend: Boolean = true
        set(v) { field = v; invalidate() }

    /** Day key of the currently highlighted / selected dot. */
    var selectedDayKey: String? = null
        set(v) { field = v; invalidate() }

    /** Invoked when the user taps a data dot. */
    var onDayTapped: ((dayKey: String) -> Unit)? = null

    /** dayKey → note text; days with non-blank notes get a small peach pin at the x-axis. */
    var notes: Map<String, String> = emptyMap()
        set(v) { field = v; invalidate() }

    // ── aquarelle colours ─────────────────────────────────────────────────
    private val colPositive = Color.parseColor("#6B9FBF")   // dusty blue
    private val colNeutral  = Color.parseColor("#78AA8A")   // sage green
    private val colNegative = Color.parseColor("#C28A80")   // warm rose
    private val colSuspend  = Color.parseColor("#A98BC4")   // soft lavender

    // ── paints ───────────────────────────────────────────────────────────
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = Color.parseColor("#EDE6E3")
        strokeWidth = 1f
        style       = Paint.Style.STROKE
    }
    private val originPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = Color.parseColor("#78AA8A")
        strokeWidth = 2.5f
        style       = Paint.Style.STROKE
        pathEffect  = DashPathEffect(floatArrayOf(10f, 7f), 0f)
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = Color.parseColor("#C4B8B4")
        strokeWidth = 1.5f
        style       = Paint.Style.STROKE
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 3.5f
        style       = Paint.Style.STROKE
        strokeJoin  = Paint.Join.ROUND
        strokeCap   = Paint.Cap.ROUND
    }
    private val suspendLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = colSuspend
        strokeWidth = 2f
        style       = Paint.Style.STROKE
        strokeJoin  = Paint.Join.ROUND
        strokeCap   = Paint.Cap.ROUND
        pathEffect  = DashPathEffect(floatArrayOf(8f, 5f), 0f)
        alpha       = 180
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val dotBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = Color.parseColor("#FFFCFA")
        strokeWidth = 2.5f
        style       = Paint.Style.STROKE
    }
    private val selectedRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = Color.parseColor("#F4956A")
        strokeWidth = 3f
        style       = Paint.Style.STROKE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = Color.parseColor("#8A7A76")
        textAlign = Paint.Align.CENTER
    }
    private val yLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = Color.parseColor("#A09090")
        textAlign = Paint.Align.RIGHT
    }
    private val suspendLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = colSuspend
        textAlign = Paint.Align.LEFT
        alpha     = 180
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = Color.parseColor("#C4B8B4")
        textAlign = Paint.Align.CENTER
    }
    private val zoneLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        alpha     = 140
    }
    private val balancedLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = Color.parseColor("#78AA8A")
        textAlign = Paint.Align.LEFT
        alpha     = 200
    }
    private val tooltipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color  = Color.parseColor("#FFF8F2")
        style  = Paint.Style.FILL
        setShadowLayer(4f, 0f, 2f, Color.parseColor("#20000000"))
    }
    private val tooltipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = Color.parseColor("#3D3030")
        textAlign = Paint.Align.CENTER
    }
    private val notePinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F4956A"); style = Paint.Style.FILL
    }
    private val notePinStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C97040"); strokeWidth = 1.5f; style = Paint.Style.STROKE
    }

    // ── zoom / pan ────────────────────────────────────────────────────────
    private var scaleX     = 1f
    private var translateX = 0f

    private var selectedIndex: Int = -1

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                scaleX = (scaleX * d.scaleFactor).coerceIn(0.4f, 8f)
                clampTranslate(); invalidate(); return true
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                translateX -= dx; clampTranslate(); invalidate(); return true
            }
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val idx = findNearestDot(e.x, e.y)
                if (idx >= 0) {
                    selectedIndex = if (selectedIndex == idx) -1 else idx
                    selectedDayKey = if (selectedIndex >= 0) series[selectedIndex].first else null
                    onDayTapped?.invoke(series[idx].first)
                    invalidate()
                }
                return true
            }
        })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e); gestureDetector.onTouchEvent(e); return true
    }

    private fun clampTranslate() {
        val usable = (width - PAD_LEFT - PAD_RIGHT) * scaleX
        translateX = translateX.coerceIn(min(0f, width - PAD_LEFT - PAD_RIGHT - usable), 0f)
    }

    private fun findNearestDot(tapX: Float, tapY: Float): Int {
        var best = -1; var bestDist = 60f
        series.indices.forEach { i ->
            val d = abs(tapX - xOf(i, series.size, (width - PAD_LEFT - PAD_RIGHT) * scaleX))
            if (d < bestDist) { bestDist = d; best = i }
        }
        return best
    }

    companion object {
        private const val PAD_LEFT   = 72f
        private const val PAD_RIGHT  = 48f
        private const val PAD_TOP    = 32f
        private const val PAD_BOTTOM = 56f
    }

    private fun xOf(i: Int, n: Int, chartW: Float) =
        PAD_LEFT + translateX + (i.toFloat() / max(1, n - 1)) * chartW

    // ── Catmull-Rom → cubic Bézier ────────────────────────────────────────
    private fun buildSmoothPath(pts: List<PointF>): Path {
        val path = Path()
        if (pts.isEmpty()) return path
        if (pts.size == 1) { path.moveTo(pts[0].x, pts[0].y); return path }
        path.moveTo(pts[0].x, pts[0].y)
        for (i in 0 until pts.size - 1) {
            val p0 = if (i == 0) pts[0] else pts[i - 1]
            val p1 = pts[i]
            val p2 = pts[i + 1]
            val p3 = if (i + 2 >= pts.size) pts[pts.size - 1] else pts[i + 2]
            val cp1x = p1.x + (p2.x - p0.x) / 6f
            val cp1y = p1.y + (p2.y - p0.y) / 6f
            val cp2x = p2.x - (p3.x - p1.x) / 6f
            val cp2y = p2.y - (p3.y - p1.y) / 6f
            path.cubicTo(cp1x, cp1y, cp2x, cp2y, p2.x, p2.y)
        }
        return path
    }

    /** Build a smooth closed fill area from a series of points, closing to [yBaseline]. */
    private fun buildSmoothFill(pts: List<PointF>, yBaseline: Float): Path {
        val path = Path()
        if (pts.size < 2) return path
        path.moveTo(pts[0].x, yBaseline)
        path.lineTo(pts[0].x, pts[0].y)
        for (i in 0 until pts.size - 1) {
            val p0 = if (i == 0) pts[0] else pts[i - 1]
            val p1 = pts[i]; val p2 = pts[i + 1]
            val p3 = if (i + 2 >= pts.size) pts[pts.size - 1] else pts[i + 2]
            val cp1x = p1.x + (p2.x - p0.x) / 6f; val cp1y = p1.y + (p2.y - p0.y) / 6f
            val cp2x = p2.x - (p3.x - p1.x) / 6f; val cp2y = p2.y - (p3.y - p1.y) / 6f
            path.cubicTo(cp1x, cp1y, cp2x, cp2y, p2.x, p2.y)
        }
        path.lineTo(pts.last().x, yBaseline)
        path.close()
        return path
    }

    // ── drawing ───────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        setLayerType(LAYER_TYPE_SOFTWARE, null)   // needed for shadow on tooltip

        if (series.isEmpty()) {
            emptyPaint.textSize = (height * 0.055f).coerceIn(28f, 38f)
            canvas.drawText("Add daily entries to see Balance Score", width / 2f, height / 2f, emptyPaint)
            return
        }

        val chartW = (width - PAD_LEFT - PAD_RIGHT) * scaleX
        val chartH = height - PAD_TOP - PAD_BOTTOM
        val n      = series.size

        val minQ  = series.minOf { it.second }.coerceAtMost(-1f)
        val maxQ  = series.maxOf { it.second }.coerceAtLeast(1f)
        val range = maxQ - minQ

        fun xQ(i: Int) = xOf(i, n, chartW)
        fun yQ(q: Float) = PAD_TOP + chartH - ((q - minQ) / range) * chartH
        val yOrigin = yQ(0f).coerceIn(PAD_TOP, PAD_TOP + chartH)

        // clip to chart area
        canvas.save()
        canvas.clipRect(PAD_LEFT / 2f, 0f, width.toFloat(), height.toFloat())

        // ── zone background fills (very subtle) ──
        fillPaint.shader = null
        fillPaint.color  = Color.parseColor("#08506B9F")   // very faint blue above origin
        canvas.drawRect(PAD_LEFT, PAD_TOP, PAD_LEFT + chartW + translateX, yOrigin, fillPaint)
        fillPaint.color  = Color.parseColor("#08C28A80")    // very faint rose below origin
        canvas.drawRect(PAD_LEFT, yOrigin, PAD_LEFT + chartW + translateX, PAD_TOP + chartH, fillPaint)

        // ── grid lines ──
        val ySteps = listOf(minQ, minQ + range * 0.25f, minQ + range * 0.5f, minQ + range * 0.75f, maxQ)
        yLabelPaint.textSize = (height * 0.032f).coerceIn(18f, 28f)
        for (yVal in ySteps) {
            val y = yQ(yVal)
            canvas.drawLine(PAD_LEFT, y, PAD_LEFT + chartW + translateX, y, gridPaint)
            canvas.drawText("%.1f".format(yVal), PAD_LEFT - 8f, y + yLabelPaint.textSize / 3f, yLabelPaint)
        }

        // ── origin line ──
        canvas.drawLine(PAD_LEFT, yOrigin, PAD_LEFT + chartW + translateX, yOrigin, originPaint)
        // Label on the origin line
        balancedLabelPaint.textSize = (height * 0.028f).coerceIn(14f, 20f)
        canvas.drawText(" balanced →", PAD_LEFT + 4f, yOrigin - 4f, balancedLabelPaint)

        // ── zone labels (drawn at edges when chart is large enough) ──
        zoneLabelPaint.textSize = (height * 0.026f).coerceIn(12f, 18f)
        zoneLabelPaint.color = Color.parseColor("#6B9FBF")  // blue for high
        canvas.drawText("↑ HIGH / MANIC", PAD_LEFT + 8f, PAD_TOP + zoneLabelPaint.textSize + 4f, zoneLabelPaint)
        zoneLabelPaint.color = Color.parseColor("#C28A80")  // rose for low
        canvas.drawText("↓ LOW / DEPRESSED", PAD_LEFT + 8f, PAD_TOP + chartH - 6f, zoneLabelPaint)

        // ── filled area (smooth) ──
        if (n >= 2) {
            val pts = (0 until n).map { PointF(xQ(it), yQ(series[it].second)) }
            val fillPath = buildSmoothFill(pts, yOrigin)

            fillPaint.shader = LinearGradient(
                0f, PAD_TOP, 0f, PAD_TOP + chartH,
                intArrayOf(
                    Color.parseColor("#506B9FBF"),
                    Color.parseColor("#00FFFFFF"),
                    Color.parseColor("#00FFFFFF"),
                    Color.parseColor("#40C28A80")
                ),
                floatArrayOf(0f, (yOrigin - PAD_TOP) / chartH, (yOrigin - PAD_TOP) / chartH, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawPath(fillPath, fillPaint)
            fillPaint.shader = null
        }

        // ── sleep overlay (secondary axis, right side) ──
        if (showSuspend && suspendSeries.isNotEmpty() && n >= 2) {
            // Map suspend hours (0-24) to the chart's pixel range
            val susMap = suspendSeries.associateBy { it.first }
            val susPts = (0 until n).mapNotNull { i ->
                val h = susMap[series[i].first]?.second ?: return@mapNotNull null
                PointF(xQ(i), PAD_TOP + chartH - (h / 24f) * chartH)
            }
            if (susPts.size >= 2) {
                val susPath = buildSmoothPath(susPts)
                canvas.drawPath(susPath, suspendLinePaint)
                // Right-side axis label
                suspendLabelPaint.textSize = (height * 0.028f).coerceIn(16f, 24f)
                canvas.drawText("24h", width - PAD_RIGHT + 4f, PAD_TOP + suspendLabelPaint.textSize, suspendLabelPaint)
                canvas.drawText("0h",  width - PAD_RIGHT + 4f, PAD_TOP + chartH, suspendLabelPaint)
                canvas.drawText("Zzz", width - PAD_RIGHT + 4f, PAD_TOP + chartH / 2f, suspendLabelPaint)
            }
        }

        // ── quality line (per-segment colour, smooth) ──
        if (n >= 2) {
            val pts = (0 until n).map { PointF(xQ(it), yQ(series[it].second)) }
            for (i in 0 until n - 1) {
                val segPts = buildListOf(
                    if (i == 0) pts[0] else pts[i - 1],
                    pts[i], pts[i + 1],
                    if (i + 2 >= n) pts[n - 1] else pts[i + 2]
                )
                val cp1x = segPts[1].x + (segPts[2].x - segPts[0].x) / 6f
                val cp1y = segPts[1].y + (segPts[2].y - segPts[0].y) / 6f
                val cp2x = segPts[2].x - (segPts[3].x - segPts[1].x) / 6f
                val cp2y = segPts[2].y - (segPts[3].y - segPts[1].y) / 6f
                val seg = Path().apply {
                    moveTo(segPts[1].x, segPts[1].y)
                    cubicTo(cp1x, cp1y, cp2x, cp2y, segPts[2].x, segPts[2].y)
                }
                linePaint.color = qualityColor((series[i].second + series[i + 1].second) / 2f)
                canvas.drawPath(seg, linePaint)
            }
        }

        // ── dots ──
        val dotRadius = (height * 0.022f).coerceIn(7f, 12f)
        for (i in 0 until n) {
            val x = xQ(i); val y = yQ(series[i].second)
            dotPaint.color = qualityColor(series[i].second)
            canvas.drawCircle(x, y, dotRadius, dotPaint)
            canvas.drawCircle(x, y, dotRadius, dotBorderPaint)
            if (series[i].first == selectedDayKey || i == selectedIndex) {
                canvas.drawCircle(x, y, dotRadius + 5f, selectedRingPaint)
            }
        }

        // ── x-axis date labels ──
        val stride = max(1, n / 8)
        labelPaint.textSize = (height * 0.032f).coerceIn(18f, 28f)
        for (i in 0 until n step stride) {
            canvas.drawText(series[i].first.substring(5), xQ(i), height - PAD_BOTTOM * 0.15f, labelPaint)
        }

        // ── note pins (small peach triangles just above x-axis) ──
        for (i in 0 until n) {
            if (!notes[series[i].first].isNullOrBlank()) {
                val px = xQ(i)
                val py = PAD_TOP + chartH + 4f
                val tri = Path().apply {
                    moveTo(px, py); lineTo(px - 6f, py + 11f); lineTo(px + 6f, py + 11f); close()
                }
                canvas.drawPath(tri, notePinPaint)
                canvas.drawPath(tri, notePinStroke)
            }
        }

        // ── x-axis line ──
        canvas.drawLine(PAD_LEFT, PAD_TOP + chartH, PAD_LEFT + chartW + translateX, PAD_TOP + chartH, axisPaint)

        // ── tooltip for selected point ──
        val selIdx = if (selectedIndex in series.indices) selectedIndex
                     else series.indexOfFirst { it.first == selectedDayKey }
        if (selIdx >= 0) drawTooltip(canvas, selIdx, xQ(selIdx), yQ(series[selIdx].second), chartH)

        canvas.restore()
    }

    private fun drawTooltip(canvas: Canvas, i: Int, cx: Float, cy: Float, chartH: Float) {
        val q    = series[i].second
        val date = series[i].first
        val sus  = suspendSeries.find { it.first == date }?.second
        val note = notes[date]?.takeIf { it.isNotBlank() }
        val line1 = date.substring(5)
        val line2 = "Balance Score: ${"%.2f".format(q)}"
        val line3 = if (sus != null) "Sleep: ${"%.1f".format(sus)}h" else null
        val line4 = if (note != null) "📌 ${note.take(38)}${if (note.length > 38) "…" else ""}" else null

        tooltipTextPaint.textSize = (height * 0.034f).coerceIn(20f, 30f)
        val tw = tooltipTextPaint.measureText(line2) + 24f
        val lineH = tooltipTextPaint.textSize * 1.35f
        val lines = listOfNotNull(line1, line2, line3, line4)
        val th = lines.size * lineH + 16f

        var tx = cx - tw / 2f
        var ty = cy - th - 16f
        if (tx < PAD_LEFT) tx = PAD_LEFT
        if (tx + tw > width - PAD_RIGHT) tx = width - PAD_RIGHT - tw
        if (ty < PAD_TOP) ty = cy + 20f

        val rect = RectF(tx, ty, tx + tw, ty + th)
        canvas.drawRoundRect(rect, 10f, 10f, tooltipBgPaint)

        var textY = ty + tooltipTextPaint.textSize + 8f
        for (line in lines) {
            canvas.drawText(line, tx + tw / 2f, textY, tooltipTextPaint)
            textY += lineH
        }
    }

    private fun qualityColor(q: Float) = when {
        // Drive 4 = 0 (balanced). Each drive step = ±1. Total range −3 … +3.
        q >= 2f    -> colPositive                               // drive 6–7
        q >= 0f    -> lerpColor(colNeutral, colPositive, q / 2f) // drive 4–6
        q >= -2f   -> lerpColor(colNeutral, colNegative, -q / 2f) // drive 2–4
        else       -> colNegative                               // drive 1
    }

    private fun lerpColor(from: Int, to: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(from)   + (Color.red(to)   - Color.red(from))   * f).toInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * f).toInt(),
            (Color.blue(from)  + (Color.blue(to)  - Color.blue(from))  * f).toInt()
        )
    }

    private fun buildListOf(vararg pts: PointF) = listOf(*pts)
}
