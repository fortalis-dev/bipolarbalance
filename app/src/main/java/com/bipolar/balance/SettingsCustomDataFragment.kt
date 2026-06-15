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
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Settings sub-page for managing custom data points.
 * Users can add, edit, and delete custom metrics here.
 */
class SettingsCustomDataFragment : Fragment() {

    private var container: LinearLayout? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_settings_custom_data, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        container = view.findViewById(R.id.metrics_management_container)
        view.findViewById<View>(R.id.btn_add_data_point).setOnClickListener {
            openMetricBuilder(null)
        }
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

        if (metrics.isEmpty()) {
            container?.addView(TextView(ctx).apply {
                text      = "No data points yet.\nTap '+ Add data point' to create one."
                textSize  = 14f
                setTextColor(Color.parseColor("#A09090"))
                gravity   = android.view.Gravity.CENTER
                setPadding(0, (24 * dp).toInt(), 0, (24 * dp).toInt())
            })
            return
        }

        for (metric in metrics) {
            val card = com.google.android.material.card.MaterialCardView(ctx).apply {
                radius   = 12f * dp
                elevation = 1f * dp
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = (8 * dp).toInt() }
                setCardBackgroundColor(Color.parseColor("#FEFAF8"))
            }

            val inner = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
            }

            // Row 1: name + type badge
            val row1 = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity     = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = (4 * dp).toInt() }
            }
            row1.addView(TextView(ctx).apply {
                text     = metric.name
                textSize = 16f
                setTextColor(Color.parseColor("#3D3030"))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            val typeBadge = TextView(ctx).apply {
                text     = when (metric.type) {
                    MetricType.YES_NO  -> "Yes/No"
                    MetricType.RANGE   -> "Scale (${metric.rangeMin}–${metric.rangeMax})"
                    MetricType.COUNTER -> "Counter"
                }
                textSize = 11f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#A98BC4"))
                setPadding((8 * dp).toInt(), (2 * dp).toInt(), (8 * dp).toInt(), (2 * dp).toInt())
            }
            row1.addView(typeBadge)
            inner.addView(row1)

            // Row 2: flags
            val flags = buildList {
                if (metric.persistsToNextDay) add("Persists day-to-day")
                if (metric.widgetEnabled) add("Widget enabled")
            }.joinToString("  •  ")
            if (flags.isNotEmpty()) {
                inner.addView(TextView(ctx).apply {
                    text     = flags
                    textSize = 12f
                    setTextColor(Color.parseColor("#A09090"))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.bottomMargin = (8 * dp).toInt() }
                })
            }

            // Row 3: edit + delete buttons
            val row3 = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity     = Gravity.END
            }
            val btnEdit = MaterialButton(ctx, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text     = "Edit"
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    (36 * dp).toInt()
                ).also { it.marginEnd = (8 * dp).toInt() }
                setOnClickListener { openMetricBuilder(metric) }
            }
            val btnDelete = MaterialButton(ctx, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text     = "Delete"
                textSize = 12f
                setTextColor(Color.parseColor("#C07878"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    (36 * dp).toInt()
                )
                setOnClickListener {
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle("Delete \"${metric.name}\"?")
                        .setMessage("This removes the data point and all its recorded values.")
                        .setPositiveButton("Delete") { _, _ ->
                            DataRepository.deleteCustomMetric(ctx, metric.id)
                            refreshList()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            row3.addView(btnEdit)
            row3.addView(btnDelete)
            inner.addView(row3)

            card.addView(inner)
            container?.addView(card)
        }
    }

    private fun openMetricBuilder(existing: CustomMetric?) {
        val sheet = MetricBuilderBottomSheet()
        sheet.existingMetric = existing
        sheet.onMetricCreated = { metric ->
            DataRepository.saveCustomMetric(requireContext(), metric)
            refreshList()
        }
        sheet.show(parentFragmentManager, "metric_builder")
    }
}
