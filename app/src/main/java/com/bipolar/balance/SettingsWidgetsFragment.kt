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
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * Settings sub-page for managing which data points have widgets enabled
 * and configuring the persist-to-next-day behaviour.
 */
class SettingsWidgetsFragment : Fragment() {

    private var container: LinearLayout? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_settings_widgets, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        container = view.findViewById(R.id.widgets_container)
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        val ctx     = requireContext()
        val metrics = DataRepository.getCustomMetrics(ctx)
        val dp      = ctx.resources.displayMetrics.density
        container?.removeAllViews()

        fun sectionHeader(title: String) = TextView(ctx).apply {
            text     = title
            textSize = 11f
            setTextColor(Color.parseColor("#A98BC4"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.1f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = (16 * dp).toInt(); it.bottomMargin = (8 * dp).toInt() }
        }

        fun divider() = View(ctx).apply {
            setBackgroundColor(Color.parseColor("#1A000000"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt()
            )
        }

        // ── Built-in widgets ───────────────────────────────────────────────────
        container?.addView(sectionHeader("BUILT-IN WIDGETS"))

        // Drive: always enabled, no toggle
        container?.addView(builtInRow(ctx, dp, "Drive", "7-level thermometer bar · always available"))
        container?.addView(divider())

        // Suspend: user-toggleable
        container?.addView(suspendToggleRow(ctx, dp))
        container?.addView(divider())

        // ── Custom data point widgets ──────────────────────────────────────────
        container?.addView(sectionHeader("CUSTOM DATA POINTS"))

        if (metrics.isEmpty()) {
            container?.addView(TextView(ctx).apply {
                text     = "No custom data points yet.\nAdd them in Settings › Custom Data."
                textSize = 13f
                setTextColor(Color.parseColor("#A09090"))
                gravity  = Gravity.CENTER
                setPadding(8, (12 * dp).toInt(), 8, (12 * dp).toInt())
            })
        } else {
            for (metric in metrics) {
                container?.addView(customMetricRow(ctx, dp, metric))
                container?.addView(divider())
            }
        }

        // ── Info note ─────────────────────────────────────────────────────────
        container?.addView(TextView(ctx).apply {
            text     = "ℹ️  Tap a Drive or Suspend widget segment on your home screen to log values instantly.\n\n" +
                "For custom data points: long-press the home screen › Widgets › \"My Balance — Data Point\" " +
                "to add a widget, then choose which data point to display."
            textSize = 12f
            setTextColor(Color.parseColor("#A09090"))
            setPadding(0, (16 * dp).toInt(), 0, (16 * dp).toInt())
        })
    }

    private fun builtInRow(ctx: android.content.Context, dp: Float, name: String, desc: String): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, (10 * dp).toInt(), 0, (10 * dp).toInt())
        }
        val left = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        left.addView(TextView(ctx).apply { text = name; textSize = 15f; setTextColor(Color.parseColor("#3D3030")) })
        left.addView(TextView(ctx).apply { text = desc; textSize = 12f; setTextColor(Color.parseColor("#A09090")) })
        val badge = TextView(ctx).apply {
            text = "✓ Always on"
            textSize = 12f
            setTextColor(Color.parseColor("#78AA8A"))
        }
        row.addView(left)
        row.addView(badge)
        return row
    }

    private fun suspendToggleRow(ctx: android.content.Context, dp: Float): View =
        toggleRow(ctx, dp,
            "Sleep (Suspend)",
            "Purple 0–24 h bar · show or hide widget",
            DataRepository.getSuspendWidgetEnabled(ctx)
        ) { checked ->
            DataRepository.setSuspendWidgetEnabled(ctx, checked)
            // Refresh the widget to reflect the change immediately
            val mgr = android.appwidget.AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(android.content.ComponentName(ctx, SuspendWidget::class.java))
            ids.forEach { SuspendWidget.updateAppWidget(ctx, mgr, it) }
        }

    private fun customMetricRow(ctx: android.content.Context, dp: Float, metric: CustomMetric): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
        }

        val nameRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (6 * dp).toInt() }
        }
        nameRow.addView(TextView(ctx).apply {
            text     = metric.name
            textSize = 15f
            setTextColor(Color.parseColor("#3D3030"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val typeStr = when (metric.type) {
            MetricType.YES_NO  -> "Yes/No"
            MetricType.RANGE   -> "Scale (${metric.rangeMin}–${metric.rangeMax})"
            MetricType.COUNTER -> "Counter"
        }
        nameRow.addView(TextView(ctx).apply {
            text     = typeStr
            textSize = 11f
            setTextColor(Color.parseColor("#A09090"))
        })
        row.addView(nameRow)

        // Widget toggle
        row.addView(toggleRow(ctx, dp, "Widget enabled",
            "Show quick-access widget for this data point",
            metric.widgetEnabled
        ) { checked ->
            DataRepository.saveCustomMetric(ctx, metric.copy(widgetEnabled = checked))
        })

        // Persist toggle
        row.addView(toggleRow(ctx, dp, "Persist to next day",
            "Value carries forward to the next day automatically",
            metric.persistsToNextDay
        ) { checked ->
            DataRepository.saveCustomMetric(ctx, metric.copy(persistsToNextDay = checked))
        })

        return row
    }

    private fun toggleRow(
        ctx: android.content.Context, dp: Float,
        title: String, subtitle: String, checked: Boolean,
        onChanged: (Boolean) -> Unit,
    ): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (4 * dp).toInt() }
            setPadding((16 * dp).toInt(), 0, 0, 0)
        }
        val left = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        left.addView(TextView(ctx).apply { text = title; textSize = 14f; setTextColor(Color.parseColor("#3D3030")) })
        left.addView(TextView(ctx).apply { text = subtitle; textSize = 12f; setTextColor(Color.parseColor("#A09090")) })
        val sw = SwitchMaterial(ctx).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, c -> onChanged(c) }
        }
        row.addView(left)
        row.addView(sw)
        return row
    }
}
