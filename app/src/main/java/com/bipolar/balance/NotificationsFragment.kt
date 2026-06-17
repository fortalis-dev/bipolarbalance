package com.bipolar.balance

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.bipolar.balance.databinding.FragmentNotificationsBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class NotificationsFragment : Fragment() {

    private var _b: FragmentNotificationsBinding? = null
    private val b get() = _b!!

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isCheckedPending != null) {
            val ctx = requireContext()
            if (isGranted) {
                DataRepository.setNotificationsEnabled(ctx, true)
                scheduleNotification(ctx)
                b.swNotifs.isChecked = true
            } else {
                b.swNotifs.isChecked = false
                toast("Permission denied. Reminders cannot be enabled.")
            }
            isCheckedPending = null
        }
    }

    private var isCheckedPending: Boolean? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _b = FragmentNotificationsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val ctx = requireContext()
        val (h, m) = DataRepository.getNotificationTime(ctx)
        
        updateTimeLabel(h, m)
        b.swNotifs.isChecked = DataRepository.getNotificationsEnabled(ctx)

        b.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }

        b.swNotifs.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked == DataRepository.getNotificationsEnabled(ctx)) return@setOnCheckedChangeListener
            
            if (isChecked && Build.VERSION.SDK_INT >= 33 && 
                androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                isCheckedPending = true
                requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                // Temporarily revert switch until permission result
                b.swNotifs.isChecked = false
            } else {
                DataRepository.setNotificationsEnabled(ctx, isChecked)
                if (isChecked) scheduleNotification(ctx) else cancelNotification(ctx)
            }
        }

        b.layoutTimePicker.setOnClickListener {
            val (currentH, currentM) = DataRepository.getNotificationTime(ctx)
            TimePickerDialog(ctx, { _, hour, min ->
                DataRepository.setNotificationTime(ctx, hour, min)
                updateTimeLabel(hour, min)
                if (DataRepository.getNotificationsEnabled(ctx)) scheduleNotification(ctx)
            }, currentH, currentM, is24Hour(ctx)).show()
        }
    }

    private fun is24Hour(ctx: Context): Boolean {
        return when (DataRepository.getTimeFormat(ctx)) {
            1 -> true
            2 -> false
            else -> DateFormat.is24HourFormat(ctx)
        }
    }

    private fun updateTimeLabel(h: Int, m: Int) {
        val ctx = requireContext()
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, m)
        }
        
        b.tvNotifTime.text = when (DataRepository.getTimeFormat(ctx)) {
            1 -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(cal.time)
            2 -> SimpleDateFormat("h:mm a", Locale.getDefault()).format(cal.time)
            else -> DateFormat.getTimeFormat(ctx).format(cal.time)
        }
    }

    private fun scheduleNotification(ctx: Context) {
        val alarmManager = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(ctx, NotificationReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            ctx, 1001, intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val (h, m) = DataRepository.getNotificationTime(ctx)
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, m)
            set(Calendar.SECOND, 0)
            if (before(Calendar.getInstance())) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        alarmManager.setRepeating(
            AlarmManager.RTC_WAKEUP,
            calendar.timeInMillis,
            AlarmManager.INTERVAL_DAY,
            pendingIntent
        )
    }

    private fun cancelNotification(ctx: Context) {
        val alarmManager = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(ctx, NotificationReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            ctx, 1001, intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}
