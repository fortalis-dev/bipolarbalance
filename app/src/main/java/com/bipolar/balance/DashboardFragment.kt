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
import com.bipolar.balance.databinding.FragmentDashboardBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class DashboardFragment : Fragment() {

    private var _b: FragmentDashboardBinding? = null
    private val b get() = _b!!

    /** 0 = today, -1 = yesterday, etc. */
    private var dayOffset = 0

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _b = FragmentDashboardBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        b.btnPrevDay.setOnClickListener {
            dayOffset -= 1
            refresh()
        }
        b.btnNextDay.setOnClickListener {
            if (dayOffset < 0) { dayOffset += 1; refresh() }
        }

        // Tapping a dot on the quality graph shows details in the card below
        b.qualityGraph.onDayTapped = { dayKey ->
            showDayDetailCard(dayKey)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun currentDayKey(): String {
        val ms = System.currentTimeMillis() + dayOffset * 86_400_000L
        return DataRepository.getLogicalDayKey(ms, requireContext())
    }

    private fun refresh() {
        val ctx    = requireContext()
        val dayKey = currentDayKey()
        val entry  = DataRepository.getEntryForDayKey(ctx, dayKey)
        val trackingEnabled = DataRepository.getSuspendTrackingEnabled(ctx)

        // ── Navigation header ──
        val cal = Calendar.getInstance().also { it.add(Calendar.DAY_OF_YEAR, dayOffset) }
        val fmt = if (dayOffset == 0) "EEEE, d MMMM yyyy" else "EEEE, d MMMM yyyy"
        val prefix = when (dayOffset) {
            0    -> ""
            -1   -> "Yesterday — "
            else -> ""
        }
        b.tvDate.text = prefix + SimpleDateFormat(fmt, Locale.getDefault()).format(cal.time)
        b.btnNextDay.isEnabled = dayOffset < 0
        b.btnNextDay.alpha = if (dayOffset < 0) 1f else 0.35f

        // ── Quality card ──
        if (entry != null) {
            val q = DataRepository.getQualityForEntry(entry)
            b.tvQualityValue.text  = "%.1f".format(q)
            b.tvQualityLabel.text  = balanceLabel(q)
            b.cardQuality.setCardBackgroundColor(qualityCardColor(q))
            b.tvQualityValue.setTextColor(Color.WHITE)
            b.tvQualityLabel.setTextColor(Color.parseColor("#E8DDD8"))
        } else {
            b.tvQualityValue.text  = "—"
            b.tvQualityLabel.text  = if (dayOffset == 0) "No entry yet today" else "No entry for this day"
            b.cardQuality.setCardBackgroundColor(Color.parseColor("#B0A8A4"))
            b.tvQualityValue.setTextColor(Color.WHITE)
            b.tvQualityLabel.setTextColor(Color.parseColor("#E8DDD8"))
        }

        // ── Intraday graph ──
        val intraDayEntries = DataRepository.getIntraDayDriveEntries(ctx, dayKey)
        b.intradayGraph.dayKey  = dayKey
        b.intradayGraph.entries = intraDayEntries
        val dateLabel = SimpleDateFormat("d MMM", Locale.getDefault()).format(cal.time)
        b.tvIntradayTitle.text = "Daily Drive Inputs — $dateLabel"

        // ── Quality over time graph ──
        b.qualityGraph.series        = DataRepository.getQualitySeries(ctx)
        b.qualityGraph.suspendSeries = if (trackingEnabled) DataRepository.getSuspendSeries(ctx) else emptyList()
        b.qualityGraph.showSuspend   = trackingEnabled
        b.qualityGraph.selectedDayKey = dayKey
        b.qualityGraph.notes         = DataRepository.getNoteSeries(ctx)

        // ── Extras card (custom metric values for this day) ──
        refreshExtras(ctx, dayKey)

        // ── Hide day-detail card until user taps a dot ──
        b.cardDayDetail.visibility = View.GONE
    }

    private fun refreshExtras(ctx: android.content.Context, dayKey: String) {
        val metrics  = DataRepository.getCustomMetrics(ctx)
        val values   = DataRepository.getCustomMetricValuesForDay(ctx, dayKey)
        val dayNotes = DataRepository.getNotesForDay(ctx, dayKey)

        if (metrics.isEmpty() && dayNotes.isEmpty()) {
            b.cardExtras.visibility = View.GONE
            return
        }
        b.cardExtras.visibility = View.VISIBLE
        b.extrasContainer.removeAllViews()

        val dp      = ctx.resources.displayMetrics.density
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

        for (metric in metrics) {
            val v = values[metric.id]
            val valueStr = when {
                v == null || v < 0f                    -> "—"
                metric.type == MetricType.YES_NO       -> if (v == 1f) "Yes ✓" else "No"
                metric.type == MetricType.RANGE        -> v.toInt().toString()
                metric.type == MetricType.COUNTER      -> v.toInt().toString()
                else                                   -> v.toInt().toString()
            }
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity     = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = (4 * dp).toInt() }
            }
            row.addView(TextView(ctx).apply {
                text     = metric.name
                textSize = 14f
                setTextColor(Color.parseColor("#3D3030"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(ctx).apply {
                text     = valueStr
                textSize = 14f
                setTextColor(Color.parseColor("#5E8FAD"))
            })
            b.extrasContainer.addView(row)
        }

        if (dayNotes.isNotEmpty()) {
            if (metrics.isNotEmpty()) {
                // Divider
                b.extrasContainer.addView(android.view.View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt()
                    ).also { it.topMargin = (6 * dp).toInt(); it.bottomMargin = (6 * dp).toInt() }
                    setBackgroundColor(Color.parseColor("#1A000000"))
                })
            }
            for (note in dayNotes.sortedBy { it.timestampMs }) {
                val noteRow = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity     = Gravity.TOP
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.bottomMargin = (4 * dp).toInt() }
                }
                noteRow.addView(TextView(ctx).apply {
                    text     = timeFmt.format(Date(note.timestampMs))
                    textSize = 11f
                    setTextColor(Color.parseColor("#A98BC4"))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.marginEnd = (8 * dp).toInt(); it.topMargin = (2 * dp).toInt() }
                })
                noteRow.addView(TextView(ctx).apply {
                    text     = "📌 ${note.text.take(80)}${if (note.text.length > 80) "…" else ""}"
                    textSize = 13f
                    setTextColor(Color.parseColor("#7A6A66"))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                b.extrasContainer.addView(noteRow)
            }
        }
    }

    private fun showDayDetailCard(dayKey: String) {
        val ctx = requireContext()
        val entry = DataRepository.getEntryForDayKey(ctx, dayKey) ?: run {
            b.cardDayDetail.visibility = View.GONE; return
        }
        val q    = DataRepository.getQualityForEntry(entry)
        val date = SimpleDateFormat("EEEE d MMMM", Locale.getDefault())
            .format(Date(DataRepository.dayKeyToMs(dayKey)))
        
        val trackingEnabled = DataRepository.getSuspendTrackingEnabled(ctx)
        val sleepStr = if (trackingEnabled) {
            val sleepH = entry.suspendMinutes / 60
            val sleepM = entry.suspendMinutes % 60
            val s = if (sleepM == 0) "${sleepH}h" else "${sleepH}h ${sleepM}m"
            "  •  Sleep: $s"
        } else ""
        
        val driveStr = if (entry.driveOverridden)
            "Auto: ${entry.autoDriveLevel}  Daily: ${entry.driveLevel} (manual)"
        else
            "${entry.driveLevel}"
        
        b.tvDayDetailTitle.text = date
        b.tvDayDetailInfo.text  =
            "Drive: $driveStr$sleepStr  •  Balance: ${"%.2f".format(q)}" +
            "\n${balanceLabel(q)}"
        b.cardDayDetail.visibility = View.VISIBLE
    }

    // ── Balance Score helpers ───────────────────────────────────────────

    private fun balanceLabel(q: Float) = when {
        // q = drive − 4 → range −3 … +3, each step = 1 drive level
        q >  1.5f  -> "↑↑ Very high energy"        // drive 6–7
        q >  0.5f  -> "↑ Elevated — above balanced" // drive 5–6
        q >= -0.5f -> "◉ Balanced"                  // drive 4 (±0.5)
        q >= -1.5f -> "↓ Below balanced"             // drive 3–4
        else       -> "↓↓ Low energy / rest needed"  // drive 1–2
    }

    private fun qualityCardColor(q: Float) = when {
        q >  1f    -> Color.parseColor("#5E8FAD")   // dusty blue
        q >  0.2f  -> Color.parseColor("#7AAFBF")   // muted teal-blue
        q >= -0.2f -> Color.parseColor("#78AA8A")   // sage green
        q >= -1f   -> Color.parseColor("#D4916C")   // terracotta
        else       -> Color.parseColor("#C07878")   // dusty rose
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
