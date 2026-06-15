package com.bipolar.balance

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews

/**
 * "Suspend" home-screen widget.
 *
 * Displays a vertical bar divided into 25 tap zones (0 h … 24 h, one per hour).
 * The user taps the desired hour to record a suspend-duration entry.
 * The current value is shown as a filled bar from the bottom.
 */
class SuspendWidget : AppWidgetProvider() {

    companion object {
        const val ACTION_SET_SUSPEND = "com.bipolar.balance.ACTION_SET_SUSPEND"
        const val EXTRA_MINUTES      = "minutes"

        /** IDs of the 25 row-segments in the layout (index 0 = 0h, index 24 = 24h). */
        private val SEGMENT_IDS = intArrayOf(
            R.id.s0,  R.id.s1,  R.id.s2,  R.id.s3,  R.id.s4,
            R.id.s5,  R.id.s6,  R.id.s7,  R.id.s8,  R.id.s9,
            R.id.s10, R.id.s11, R.id.s12, R.id.s13, R.id.s14,
            R.id.s15, R.id.s16, R.id.s17, R.id.s18, R.id.s19,
            R.id.s20, R.id.s21, R.id.s22, R.id.s23, R.id.s24
        )

        private val COLOR_ACTIVE   = Color.parseColor("#7B1FA2")   // purple
        private val COLOR_INACTIVE = Color.parseColor("#BDBDBD")

        fun updateAppWidget(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val enabled = DataRepository.getSuspendWidgetEnabled(ctx)
            val views = RemoteViews(ctx.packageName, R.layout.widget_suspend)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

            if (!enabled) {
                // Show all segments as inactive and clear the value label
                for (segId in SEGMENT_IDS) {
                    views.setInt(segId, "setBackgroundColor", COLOR_INACTIVE)
                }
                views.setTextViewText(R.id.tv_suspend_value, "off")
                mgr.updateAppWidget(id, views)
                return
            }

            val minutes = DataRepository.getCurrentSuspendMinutes(ctx)
            val currentHour = minutes / 60

            // Layout has segment 24 at top, segment 0 at bottom.
            for (hour in 0..24) {
                val segIndex = 24 - hour   // visual row index (0 = top = 24h)
                val segId = SEGMENT_IDS[segIndex]
                val color = if (hour <= currentHour) COLOR_ACTIVE else COLOR_INACTIVE
                views.setInt(segId, "setBackgroundColor", color)

                val intent = Intent(ctx, SuspendWidget::class.java)
                    .setAction(ACTION_SET_SUSPEND)
                    .putExtra(EXTRA_MINUTES, hour * 60)
                val pi = PendingIntent.getBroadcast(ctx, 100 + hour, intent, flags)
                views.setOnClickPendingIntent(segId, pi)
            }

            val h = minutes / 60
            val m = minutes % 60
            views.setTextViewText(
                R.id.tv_suspend_value,
                if (m == 0) "${h}h" else "${h}h${m}m"
            )
            mgr.updateAppWidget(id, views)
        }

        fun refreshAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, SuspendWidget::class.java))
            ids.forEach { updateAppWidget(ctx, mgr, it) }
        }
    }

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { updateAppWidget(ctx, mgr, it) }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == ACTION_SET_SUSPEND) {
            val minutes = intent.getIntExtra(EXTRA_MINUTES, -1)
            if (minutes in 0..1440) {
                DataRepository.saveSuspend(ctx, minutes)
                refreshAll(ctx)
            }
        }
    }
}
