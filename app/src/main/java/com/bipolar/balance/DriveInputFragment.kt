package com.bipolar.balance

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.bipolar.balance.databinding.FragmentDriveInputBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Drive input screen: user taps the 7-segment thermometer bar to log their
 * current Drive level.  Every tap is stored as a timestamped [DriveEntry] and
 * also updates today's [DailyEntry] (keeping existing suspend value).
 */
class DriveInputFragment : Fragment() {

    private var _b: FragmentDriveInputBinding? = null
    private val b get() = _b!!

    private val driveColors = intArrayOf(
        Color.parseColor("#7FA8C0"),
        Color.parseColor("#6BADB8"),
        Color.parseColor("#7DB5A3"),
        Color.parseColor("#8CBD8E"),
        Color.parseColor("#D8BA77"),
        Color.parseColor("#D4916C"),
        Color.parseColor("#C07878"),
    )
    private val driveLabels = Array(7) { i -> "${i + 1}" }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _b = FragmentDriveInputBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        b.barDriveInput.segmentCount  = 7
        b.barDriveInput.segmentColors = driveColors
        b.barDriveInput.labels        = driveLabels
        b.barDriveInput.onSelected    = { idx ->
            val level = idx + 1
            val ctx   = requireContext()
            // Save raw timestamped entry
            DataRepository.saveDriveLevel(ctx, level)
            // Auto-update today's daily entry with the new average (unless user overrode it there)
            DataRepository.autoUpdateDailyDrive(ctx)
            updateCurrentDisplay(level)
            refreshHistory()
        }
    }

    override fun onResume() {
        super.onResume()
        val level = DataRepository.getCurrentLevel(requireContext())
        b.barDriveInput.selectedIndex = level - 1
        updateCurrentDisplay(level)
        refreshHistory()
    }

    private fun updateCurrentDisplay(level: Int) {
        b.tvCurrentLevel.text = "Level $level"
        b.tvCurrentLevel.setTextColor(Color.WHITE)
        b.cardLevelHeader.setCardBackgroundColor(driveColors[level - 1])
    }

    private fun refreshHistory() {
        val entries = DataRepository.getDriveEntries(requireContext())
            .sortedByDescending { it.timestampMs }
            .take(8)

        val fmt = SimpleDateFormat("d MMM  HH:mm", Locale.getDefault())
        val sb  = StringBuilder()
        entries.forEach { e ->
            sb.append("Level ${e.level}   ${fmt.format(Date(e.timestampMs))}\n")
        }
        b.tvHistory.text = if (sb.isEmpty()) "No entries yet" else sb.trimEnd().toString()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
