package com.bipolar.balance

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.bipolar.balance.databinding.FragmentSettingsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingsFragment : Fragment() {

    private var _b: FragmentSettingsBinding? = null
    private val b get() = _b!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _b = FragmentSettingsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupSettingsList()
    }

    private fun setupSettingsList() {
        val ctx = requireContext()

        // 1. Backup
        setupItem(b.itemBackup.root, "Google Drive Sync", "Sign in to backup or restore your data", R.drawable.ic_nav_backup) {
            navigateTo(BackupFragment())
        }

        // 2. Custom Data
        setupItem(b.itemCustomData.root, "Custom Data Points", "Manage your own tracking variables", R.drawable.ic_nav_insights) {
            navigateTo(SettingsCustomDataFragment())
        }

        // 3. Widgets
        setupItem(b.itemWidgets.root, "Configure Widgets", "Instructions for home screen tracking", R.drawable.ic_nav_dashboard) {
            navigateTo(SettingsWidgetsFragment())
        }

        // 4. Notifications
        setupItem(b.itemNotifications.root, "Daily Reminders", "Set a time to be reminded to log your day", R.drawable.ic_nav_daily) {
            navigateTo(NotificationsFragment())
        }

        // 5. Time Format
        updateTimeFormatItem()

        // 6. Toggle Suspend
        setupSwitchItem(b.itemToggleSuspend.root, "Track Suspend (Rest)", "Show rest duration in charts and entries", DataRepository.getSuspendTrackingEnabled(ctx)) { enabled ->
            DataRepository.setSuspendTrackingEnabled(ctx, enabled)
        }

        // 7. Health Sync
        setupItem(b.itemHealthSync.root, "Health Connect", "Linking with Android Health Sync is under development", R.drawable.ic_nav_daily) {
            toast("This feature is currently in development")
        }

        // 8. Help
        setupItem(b.itemHelp.root, "How To Use", "Guide to understanding Drive and Suspend", R.drawable.ic_nav_histogram) {
            navigateTo(HelpFragment())
        }
    }

    private fun updateTimeFormatItem() {
        val ctx = requireContext()
        val currentFmt = DataRepository.getTimeFormat(ctx)
        val fmtNames = arrayOf("System Default", "24-hour (13:00)", "12-hour (1:00 PM)")
        
        setupItem(b.itemTimeFormat.root, "Time Format", fmtNames[currentFmt], R.drawable.ic_chevron_right) {
            MaterialAlertDialogBuilder(ctx)
                .setTitle("Select Time Format")
                .setSingleChoiceItems(fmtNames, currentFmt) { dialog, which ->
                    DataRepository.setTimeFormat(ctx, which)
                    updateTimeFormatItem()
                    dialog.dismiss()
                }
                .show()
        }
    }

    private fun setupItem(root: View, title: String, desc: String, iconRes: Int, onClick: () -> Unit) {
        root.findViewById<TextView>(R.id.setting_title).text = title
        root.findViewById<TextView>(R.id.setting_desc).text = desc
        root.findViewById<ImageView>(R.id.setting_icon).setImageResource(iconRes)
        root.setOnClickListener { onClick() }
    }

    private fun setupSwitchItem(root: View, title: String, desc: String, initialValue: Boolean, onToggle: (Boolean) -> Unit) {
        root.findViewById<TextView>(R.id.setting_title).text = title
        root.findViewById<TextView>(R.id.setting_desc).text = desc
        val sw = root.findViewById<SwitchMaterial>(R.id.setting_switch)
        sw.isChecked = initialValue
        sw.setOnCheckedChangeListener { _, isChecked -> onToggle(isChecked) }
        root.setOnClickListener { sw.toggle() }
    }

    private fun navigateTo(fragment: Fragment) {
        parentFragmentManager.beginTransaction()
            .replace(R.id.settings_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
