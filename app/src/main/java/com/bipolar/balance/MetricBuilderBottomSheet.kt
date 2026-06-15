package com.bipolar.balance

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.bipolar.balance.databinding.FragmentMetricBuilderBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class MetricBuilderBottomSheet : BottomSheetDialogFragment() {

    var onMetricCreated: ((CustomMetric) -> Unit)? = null
    /** Pre-populate when editing an existing metric. */
    var existingMetric: CustomMetric? = null

    private var _b: FragmentMetricBuilderBinding? = null
    private val b get() = _b!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _b = FragmentMetricBuilderBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Pre-populate if editing
        existingMetric?.let { m ->
            b.tvBuilderTitle.text = "Edit metric"
            b.etMetricName.setText(m.name)
            when (m.type) {
                MetricType.YES_NO  -> b.chipYesNo.isChecked  = true
                MetricType.RANGE   -> b.chipHours.isChecked  = true
                MetricType.COUNTER -> b.chipCounter.isChecked = true
            }
            b.etRangeMin.setText(m.rangeMin.toString())
            b.etRangeMax.setText(m.rangeMax.toString())
            b.switchCounterIncOnly.isChecked = m.counterWidgetIncOnly
            b.switchPersist.isChecked = m.persistsToNextDay
            b.switchWidget.isChecked  = m.widgetEnabled
        }

        b.chipGroupType.setOnCheckedStateChangeListener { _, _ ->
            updateHint()
            updateRangeVisibility()
            updateCounterOptions()
        }
        updateHint()
        updateRangeVisibility()
        updateCounterOptions()

        b.btnCreateMetric.text = if (existingMetric != null) "Save changes" else "Create metric"
        b.btnCreateMetric.setOnClickListener {
            val name = b.etMetricName.text?.toString()?.trim() ?: ""
            if (name.isBlank()) {
                b.etMetricName.error = "Please enter a name"
                return@setOnClickListener
            }
            val type = when {
                b.chipCounter.isChecked -> MetricType.COUNTER
                b.chipHours.isChecked   -> MetricType.RANGE
                else                   -> MetricType.YES_NO
            }
            val rangeMin = b.etRangeMin.text?.toString()?.toIntOrNull() ?: 0
            val rangeMax = b.etRangeMax.text?.toString()?.toIntOrNull() ?: 24
            if (type == MetricType.RANGE && rangeMin >= rangeMax) {
                b.etRangeMax.error = "Must be greater than From"
                return@setOnClickListener
            }
            val metric = CustomMetric(
                id                    = existingMetric?.id ?: java.util.UUID.randomUUID().toString(),
                name                  = name,
                type                  = type,
                widgetEnabled         = b.switchWidget.isChecked,
                persistsToNextDay     = b.switchPersist.isChecked,
                rangeMin              = if (type == MetricType.RANGE) rangeMin else 0,
                rangeMax              = if (type == MetricType.RANGE) rangeMax else 24,
                counterWidgetIncOnly  = b.switchCounterIncOnly.isChecked,
            )
            onMetricCreated?.invoke(metric)
            dismiss()
        }
    }

    private fun updateRangeVisibility() {
        b.layoutRangeInputs.visibility = if (b.chipHours.isChecked) View.VISIBLE else View.GONE
    }

    private fun updateCounterOptions() {
        b.layoutCounterWidgetMode.visibility = if (b.chipCounter.isChecked) View.VISIBLE else View.GONE
    }

    private fun updateHint() {
        b.tvTypeHint.text = when {
            b.chipCounter.isChecked -> "Each day you tap + for every time this happens (e.g. glasses of water, cigarettes)"
            b.chipHours.isChecked   -> "A numeric scale — set the From and To values. Widget shows a tappable slider."
            else                   -> "Each day you'll tap Yes or No"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
