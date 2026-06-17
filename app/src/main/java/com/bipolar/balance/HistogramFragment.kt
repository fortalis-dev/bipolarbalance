package com.bipolar.balance

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.bipolar.balance.databinding.FragmentInsightsBinding

/**
 * Insights tab – replaces the old Histogram.
 *
 * Shows:
 *  • Sleep vs Drive correlation summary cards
 *  • Lag-effect analysis (what happens to Drive N days after low/high sleep)
 *  • Smooth dual-line Drive + Sleep trend chart (InsightsTrendView)
 */
class InsightsFragment : Fragment() {

    private var _b: FragmentInsightsBinding? = null
    private val b get() = _b!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _b = FragmentInsightsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        b.chipGroupRange.setOnCheckedStateChangeListener { _, _ -> refreshData() }
    }

    override fun onResume() {
        super.onResume()
        refreshData()
    }

    private fun refreshData() {
        val ctx = requireContext()

        val sinceMs: Long = when (b.chipGroupRange.checkedChipId) {
            R.id.chip_7days  -> System.currentTimeMillis() - 7L * 86_400_000L
            R.id.chip_30days -> System.currentTimeMillis() - 30L * 86_400_000L
            else -> 0L
        }

        val entries = DataRepository.getDailyEntries(ctx)
            .filter { it.lastUpdatedMs >= sinceMs }
            .sortedBy { it.dayKey }
        
        val trackingEnabled = DataRepository.getSuspendTrackingEnabled(ctx)

        // ── Trend chart ──
        b.trendView.driveSeries    = entries.map { it.dayKey to it.driveLevel.toFloat() }
        b.trendView.autoDriveSeries = entries.map { it.dayKey to it.autoDriveLevel.toFloat() }
        b.trendView.sleepSeries    = if (trackingEnabled) entries.map { it.dayKey to (it.suspendMinutes / 60f) } else emptyList()

        // ── Insight cards ──
        val insights = DataRepository.getSleepDriveInsights(ctx, sinceMs)

        b.cardInsightSummary.visibility = if (trackingEnabled) View.VISIBLE else View.GONE
        b.tvDriveHighSleep.text = insights.avgDriveHighSleep?.let { "%.1f".format(it) } ?: "—"
        b.tvDriveLowSleep.text  = insights.avgDriveLowSleep?.let  { "%.1f".format(it) } ?: "—"
        b.tvInsightText.text    = buildInsightText(insights)

        // ── Lag-effect analysis ──
        val lagData = DataRepository.getLagInsights(ctx)
        if (trackingEnabled && lagData.isNotEmpty() && insights.dayCount >= 5) {
            b.cardLag.visibility = View.VISIBLE
            b.tvLagText.text = buildLagText(lagData)
        } else {
            b.cardLag.visibility = View.GONE
        }

        // ── Correlation cards ──
        buildCorrelations(ctx, entries, trackingEnabled)
    }

    private fun buildCorrelations(ctx: android.content.Context, entries: List<DailyEntry>, trackingEnabled: Boolean) {
        if (entries.size < 3) { b.cardCorrelations.visibility = View.GONE; return }

        val driveMap: Map<String, Float> = entries.associate { it.dayKey to it.driveLevel.toFloat() }
        b.correlationsContainer.removeAllViews()

        var rowCount = 0

        // ── Sleep ──
        if (trackingEnabled) {
            val sleepMap: Map<String, Float> = entries.associate { it.dayKey to it.suspendMinutes / 60f }
            val sleepR   = computeCorrelation(driveMap, sleepMap)
            if (sleepR != null) {
                b.correlationsContainer.addView(correlationRow(ctx, "Sleep", sleepR))
                rowCount++
            }
        }

        // ── Custom metrics ──
        for (metric in DataRepository.getCustomMetrics(ctx)) {
            val metricMap = DataRepository.getAllValuesForMetric(ctx, metric.id)
                .filter { it.second >= 0f }
                .associate { it.first to it.second }
            if (metricMap.size < 3) continue
            val r = computeCorrelation(driveMap, metricMap) ?: continue
            b.correlationsContainer.addView(correlationRow(ctx, metric.name, r))
            rowCount++
        }

        b.cardCorrelations.visibility = if (rowCount > 0) View.VISIBLE else View.GONE
    }

    private fun computeCorrelation(xs: Map<String, Float>, ys: Map<String, Float>): Float? {
        val keys = xs.keys.intersect(ys.keys).toList()
        if (keys.size < 3) return null
        return DataRepository.computePearsonR(keys.map { xs[it]!! }, keys.map { ys[it]!! })
    }

    private fun correlationRow(ctx: android.content.Context, name: String, r: Float): View {
        val dp   = ctx.resources.displayMetrics.density
        val row  = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (8 * dp).toInt() }
        }

        val nameView = TextView(ctx).apply {
            text     = name
            textSize = 14f
            setTextColor(Color.parseColor("#3D3030"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val rColor = when {
            r >  0.4f -> Color.parseColor("#5E8FAD")   // blue – positive
            r < -0.4f -> Color.parseColor("#C07878")   // rose – negative
            else      -> Color.parseColor("#78AA8A")   // sage – neutral
        }
        val arrow = when {
            r >  0.4f -> "↑"
            r < -0.4f -> "↓"
            else      -> "≈"
        }
        val label = when {
            kotlin.math.abs(r) >= 0.7f -> "Strong"
            kotlin.math.abs(r) >= 0.4f -> "Moderate"
            else                       -> "Weak"
        }

        val valueView = TextView(ctx).apply {
            text     = "$arrow $label  r = ${"%.2f".format(r)}"
            textSize = 13f
            setTextColor(rColor)
        }

        row.addView(nameView); row.addView(valueView)
        return row
    }

    private fun buildInsightText(i: DataRepository.SleepDriveInsights): String {
        if (i.dayCount < 3) return "Add a few more days of entries to see insights.\n\nThe Balance Score is based on Auto Drive only (drive 4 = balanced, range −3 … +3). Sleep is tracked separately and used here for trend analysis — it does not affect the score."
        val hi = i.avgDriveHighSleep
        val lo = i.avgDriveLowSleep
        val avg = "%.1f".format(i.avgSleepAllTime)
        val note = "\n\n💡 Note: the Balance Score uses Auto Drive (your day's average drive inputs) — not the manual daily override, and not sleep. Drive 4 = balanced (0), drive 7 = +3, drive 1 = −3."

        return when {
            hi == null && lo == null -> "No comparison possible yet (need both high and low sleep days).$note"
            hi == null -> "All recorded days had sleep below 7 h (avg $avg h). " +
                "Try logging some well-rested days to compare.$note"
            lo == null -> "All recorded days had sleep 7 h or more (avg $avg h). " +
                "Great pattern — add some shorter-sleep days for comparison.$note"
            else -> {
                val diff = hi - lo
                val diffStr = "%.1f".format(kotlin.math.abs(diff))
                val base = when {
                    diff > 0.6f ->
                        "When you sleep well (≥7 h) your Auto Drive averages ${"%.1f".format(hi)}, " +
                        "vs ${"%.1f".format(lo)} after shorter sleep — $diffStr points higher with better rest. " +
                        "Your Auto Drive responds positively to adequate sleep."
                    diff < -0.6f ->
                        "When you sleep less (<7 h) your Auto Drive averages ${"%.1f".format(lo)}, " +
                        "vs ${"%.1f".format(hi)} with more sleep — $diffStr points higher on shorter sleep days. " +
                        "Consider whether this reflects productive intensity or early-phase escalation."
                    else ->
                        "Your Auto Drive level appears fairly stable regardless of sleep duration " +
                        "(avg $avg h overall). Both conditions average within 0.5 of each other."
                }
                base + note
            }
        }
    }

    private fun buildLagText(lags: List<Triple<Int, Float?, Float?>>): String {
        val sb = StringBuilder()
        for ((lagDays, hiDrive, loDrive) in lags) {
            if (hiDrive == null || loDrive == null) continue
            val diff = loDrive - hiDrive   // positive = higher drive after low sleep
            val sign = if (diff > 0) "↑" else if (diff < 0) "↓" else "≈"
            val day = if (lagDays == 1) "next day" else "$lagDays days later"
            sb.appendLine(
                "• $sign ${"%.1f".format(kotlin.math.abs(diff))} pts Drive $day: " +
                "after poor sleep (${"%.1f".format(loDrive)}) vs good sleep (${"%.1f".format(hiDrive)})"
            )
        }
        if (sb.isEmpty()) return "Not enough data for lag analysis yet."
        return "How your Drive changes after high vs low sleep:\n\n$sb".trim()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
