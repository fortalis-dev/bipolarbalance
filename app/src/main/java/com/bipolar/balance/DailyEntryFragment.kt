package com.bipolar.balance

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.bipolar.balance.databinding.FragmentDailyEntryBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class DailyEntryFragment : Fragment() {

    private var _b: FragmentDailyEntryBinding? = null
    private val b get() = _b!!

    private var driveSelected   = 0
    private var suspendSelected = 0
    private var driveOverridden = false

    private val driveColors = intArrayOf(
        Color.parseColor("#7FA8C0"), Color.parseColor("#6BADB8"),
        Color.parseColor("#7DB5A3"), Color.parseColor("#8CBD8E"),
        Color.parseColor("#D8BA77"), Color.parseColor("#D4916C"),
        Color.parseColor("#C07878"),
    )

    private val driveLabels   = Array(7) { i -> "${i + 1}" }
    private val suspendLabels = Array(16) { i -> "${i}h" }
    private val suspendWeights = FloatArray(16) { i ->
        when {
            i in 6..9 -> 1.6f
            i in 0..5 -> 0.7f
            else      -> 0.75f
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _b = FragmentDailyEntryBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        b.barDrive.segmentCount  = 7
        b.barDrive.segmentColors = driveColors
        b.barDrive.inactiveColor = Color.parseColor("#D8CECC")
        b.barDrive.labels        = driveLabels
        b.barDrive.onSelected    = { idx ->
            driveSelected   = idx
            driveOverridden = true
            updateDriveLabel(); saveEntry()
        }

        b.barSuspend.segmentCount      = 16
        b.barSuspend.segmentWeights    = suspendWeights
        b.barSuspend.singleActiveColor = Color.parseColor("#A98BC4")
        b.barSuspend.inactiveColor     = Color.parseColor("#D8CECC")
        b.barSuspend.labels            = suspendLabels
        b.barSuspend.onSelected        = { idx ->
            suspendSelected = idx; updateSuspendLabel(); saveEntry()
        }

        b.btnResetDrive.setOnClickListener {
            val ctx = requireContext()
            driveOverridden = false
            val avg = DataRepository.getDailyDriveAverage(ctx, DataRepository.getCurrentDayKey())
            if (avg != null) {
                driveSelected = (avg.toInt().coerceIn(1, 7)) - 1
                b.barDrive.selectedIndex = driveSelected
                updateDriveLabel()
            }
            updateDriveAutoLabel(avg); saveEntry()
        }

        b.tvManageMetrics.setOnClickListener {
            (activity as? MainActivity)?.navigateToSettings()
        }

        b.btnSaveNote.setOnClickListener {
            val text = b.etNote.text?.toString()?.trim().orEmpty()
            if (text.isNotBlank()) {
                DataRepository.saveNoteEntry(requireContext(), text)
                b.etNote.setText("")
                refreshNotes()
                BackupHelper.tryAutoBackup(requireContext(), lifecycleScope)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadExistingEntry()
        updateDate()
        refreshMetrics()
        refreshNotes()
    }

    private fun loadExistingEntry() {
        val ctx    = requireContext()
        val entry  = DataRepository.getTodaysDailyEntry(ctx)
        val dayKey = DataRepository.getCurrentDayKey()
        val avg    = DataRepository.getDailyDriveAverage(ctx, dayKey)

        driveOverridden = entry?.driveOverridden ?: false

        val effectiveDrive = when {
            driveOverridden && entry != null -> entry.driveLevel
            avg != null                      -> avg.toInt().coerceIn(1, 7)
            entry != null                    -> entry.driveLevel
            else                             -> DataRepository.getCurrentLevel(ctx)
        }

        driveSelected   = (effectiveDrive - 1).coerceIn(0, 6)
        suspendSelected = ((entry?.suspendMinutes ?: DataRepository.getCurrentSuspendMinutes(ctx)) / 60)
            .coerceIn(0, 15)

        b.barDrive.selectedIndex   = driveSelected
        b.barSuspend.selectedIndex = suspendSelected
        updateDriveLabel(); updateDriveAutoLabel(avg); updateSuspendLabel()

        b.tvSavedAt.text = if (entry != null)
            getString(R.string.saved_at, SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(entry.lastUpdatedMs)))
        else getString(R.string.not_saved_yet)
    }

    private fun saveEntry() {
        val ctx            = requireContext()
        val driveLevel     = driveSelected + 1
        val suspendMinutes = suspendSelected * 60
        DataRepository.saveDailyEntry(ctx, driveLevel, suspendMinutes, driveOverridden)
        BackupHelper.tryAutoBackup(ctx, lifecycleScope)
        SuspendWidget.refreshAll(ctx)
        b.tvSavedAt.text = getString(R.string.saved_at,
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()))
    }

    private fun refreshNotes() {
        val ctx    = requireContext()
        val dayKey = DataRepository.getCurrentDayKey()
        val notes  = DataRepository.getNotesForDay(ctx, dayKey)
        val dp     = ctx.resources.displayMetrics.density

        b.notesListContainer.removeAllViews()

        if (notes.isEmpty()) {
            b.notesListContainer.visibility = View.GONE
        } else {
            b.notesListContainer.visibility = View.VISIBLE
            val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
            for (note in notes.sortedByDescending { it.timestampMs }) {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity     = Gravity.TOP
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.bottomMargin = (6 * dp).toInt() }
                    setPadding((8 * dp).toInt(), (6 * dp).toInt(), (4 * dp).toInt(), (6 * dp).toInt())
                    setBackgroundColor(Color.parseColor("#0F000000"))
                    setOnLongClickListener {
                        MaterialAlertDialogBuilder(ctx)
                            .setTitle("Delete note?")
                            .setMessage("\"${note.text.take(60)}\"")
                            .setPositiveButton("Delete") { _, _ ->
                                DataRepository.deleteNoteEntry(ctx, note.id)
                                refreshNotes()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                        true
                    }
                }

                val tvTime = TextView(ctx).apply {
                    text     = timeFmt.format(Date(note.timestampMs))
                    textSize = 11f
                    setTextColor(Color.parseColor("#A98BC4"))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.marginEnd = (8 * dp).toInt(); it.topMargin = (2 * dp).toInt() }
                }
                val tvText = TextView(ctx).apply {
                    text     = note.text
                    textSize = 13f
                    setTextColor(Color.parseColor("#3D3030"))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                row.addView(tvTime)
                row.addView(tvText)
                b.notesListContainer.addView(row)
            }
        }
    }

    private fun refreshMetrics() {
        val ctx     = requireContext()
        val metrics = DataRepository.getCustomMetrics(ctx)
        val dayKey  = DataRepository.getCurrentDayKey()
        val values  = DataRepository.getCustomMetricValuesForDay(ctx, dayKey).toMutableMap()

        val cal = Calendar.getInstance().also { it.add(Calendar.DAY_OF_YEAR, -1) }
        val yesterdayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
        val yesterdayValues = DataRepository.getCustomMetricValuesForDay(ctx, yesterdayKey)
        for (metric in metrics) {
            if (metric.persistsToNextDay && values[metric.id] == null) {
                val yv = yesterdayValues[metric.id]
                if (yv != null && yv >= 0f) {
                    values[metric.id] = yv
                    DataRepository.saveCustomMetricValue(ctx, dayKey, metric.id, yv)
                }
            }
        }

        b.metricsContainer.removeAllViews()

        for (metric in metrics) {
            val currentValue = values[metric.id] ?: -1f
            b.metricsContainer.addView(buildMetricRow(metric, currentValue))
        }

        b.metricsContainer.visibility = if (metrics.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun buildMetricRow(metric: CustomMetric, currentValue: Float): View {
        val ctx = requireContext()
        val dp  = ctx.resources.displayMetrics.density

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (8 * dp).toInt() }
            setPadding((4*dp).toInt(), (8*dp).toInt(), (4*dp).toInt(), (8*dp).toInt())
        }

        val nameView = TextView(ctx).apply {
            text     = metric.name
            textSize = 15f
            setTextColor(Color.parseColor("#3D3030"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(nameView)

        when (metric.type) {
            MetricType.YES_NO -> {
                val btnYes = makeToggleButton(ctx, "Yes",  currentValue == 1f, dp)
                val btnNo  = makeToggleButton(ctx, "No",   currentValue == 0f, dp)
                btnYes.setOnClickListener {
                    DataRepository.saveCustomMetricValue(ctx, DataRepository.getCurrentDayKey(), metric.id, 1f)
                    CustomMetricWidget.refreshAll(ctx)
                    updateToggle(btnYes, true); updateToggle(btnNo, false)
                }
                btnNo.setOnClickListener {
                    DataRepository.saveCustomMetricValue(ctx, DataRepository.getCurrentDayKey(), metric.id, 0f)
                    CustomMetricWidget.refreshAll(ctx)
                    updateToggle(btnYes, false); updateToggle(btnNo, true)
                }
                row.addView(btnYes); row.addView(btnNo)
            }
            MetricType.RANGE -> {
                var value = if (currentValue >= 0f) currentValue.toInt() else metric.rangeMin
                value = value.coerceIn(metric.rangeMin, metric.rangeMax)

                val btnM = makeStepper(ctx, "−", dp)
                val tvV  = TextView(ctx).apply {
                    text     = "$value"
                    textSize = 17f
                    gravity  = Gravity.CENTER
                    setTextColor(Color.parseColor("#3D3030"))
                    minWidth = (56 * dp).toInt()
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.marginStart = (4*dp).toInt(); it.marginEnd = (4*dp).toInt() }
                }
                val btnP = makeStepper(ctx, "+", dp)

                fun persist() {
                    DataRepository.saveCustomMetricValue(ctx, DataRepository.getCurrentDayKey(), metric.id, value.toFloat())
                    CustomMetricWidget.refreshAll(ctx)
                }
                btnM.setOnClickListener { value = (value - 1).coerceAtLeast(metric.rangeMin); tvV.text = "$value"; persist() }
                btnP.setOnClickListener { value = (value + 1).coerceAtMost(metric.rangeMax); tvV.text = "$value"; persist() }

                row.addView(btnM); row.addView(tvV); row.addView(btnP)
            }
            MetricType.COUNTER -> {
                var count = if (currentValue >= 0f) currentValue.toInt() else 0

                val btnM = makeStepper(ctx, "−", dp)
                val tvV  = TextView(ctx).apply {
                    text     = "$count"
                    textSize = 17f
                    gravity  = Gravity.CENTER
                    setTextColor(Color.parseColor("#3D3030"))
                    minWidth = (56 * dp).toInt()
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.marginStart = (4*dp).toInt(); it.marginEnd = (4*dp).toInt() }
                }
                val btnP = makeStepper(ctx, "+", dp)

                fun persist() {
                    DataRepository.saveCustomMetricValue(ctx, DataRepository.getCurrentDayKey(), metric.id, count.toFloat())
                    CustomMetricWidget.refreshAll(ctx)
                }
                btnM.setOnClickListener { count = (count - 1).coerceAtLeast(0); tvV.text = "$count"; persist() }
                btnP.setOnClickListener { count += 1; tvV.text = "$count"; persist() }

                row.addView(btnM); row.addView(tvV); row.addView(btnP)
            }
        }

        return row
    }

    private fun makeToggleButton(ctx: android.content.Context, label: String, active: Boolean, dp: Float): MaterialButton {
        return MaterialButton(ctx, null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text     = label
            textSize = 13f
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                (36 * dp).toInt()
            ).also { it.marginEnd = (4*dp).toInt() }
            layoutParams = lp
            updateToggle(this, active)
        }
    }

    private fun updateToggle(btn: MaterialButton, active: Boolean) {
        if (active) {
            btn.setBackgroundColor(Color.parseColor("#F4956A"))
            btn.setTextColor(Color.WHITE)
        } else {
            btn.setBackgroundColor(Color.parseColor("#F5EDE8"))
            btn.setTextColor(Color.parseColor("#8A7A76"))
        }
    }

    private fun makeStepper(ctx: android.content.Context, label: String, dp: Float): MaterialButton {
        return MaterialButton(ctx, null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text     = label
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(
                (40 * dp).toInt(), (36 * dp).toInt()
            )
        }
    }

    private fun updateDate() {
        b.tvDate.text = getString(R.string.daily_entry_date, DataRepository.getCurrentDayKey())
    }

    private fun updateDriveLabel() {
        b.tvDriveValue.text = getString(R.string.drive_level_value, driveSelected + 1)
    }

    private fun updateDriveAutoLabel(avg: Float?) {
        if (driveOverridden) {
            b.tvDriveAuto.text = getString(R.string.drive_manual_override)
            b.btnResetDrive.visibility = View.VISIBLE
        } else {
            b.tvDriveAuto.text = if (avg != null)
                getString(R.string.drive_auto_avg, avg)
            else getString(R.string.drive_auto_avg, (driveSelected + 1).toFloat())
            b.btnResetDrive.visibility = View.GONE
        }
    }

    private fun updateSuspendLabel() {
        b.tvSuspendValue.text = getString(R.string.suspend_value, suspendSelected)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
