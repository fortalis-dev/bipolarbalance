package com.bipolar.balance

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.util.Log
import android.widget.RemoteViews

/**
 * "Drive" home-screen widget.
 *
 * The widget shows 7 coloured segments arranged like a thermometer (level 7
 * on top, level 1 at the bottom).  The user **taps a segment** to select that
 * level directly — no arrows needed.  The tapped level is recorded via
 * [DataRepository.saveDriveLevel] so the histogram inside the app stays current.
 */
class BarWidget : AppWidgetProvider() {

    companion object {
        /** Intent action that carries the chosen level as an extra. */
        const val ACTION_SET_LEVEL = "com.bipolar.balance.ACTION_SET_LEVEL"
        const val EXTRA_LEVEL      = "level"

        val LEVEL_COLORS = intArrayOf(
            Color.parseColor("#1565C0"),
            Color.parseColor("#0288D1"),
            Color.parseColor("#00897B"),
            Color.parseColor("#43A047"),
            Color.parseColor("#F9A825"),
            Color.parseColor("#EF6C00"),
            Color.parseColor("#B71C1C"),
        )
        private val COLOR_INACTIVE = Color.parseColor("#BDBDBD")

        // Widget layout lists segments from top (level 7) to bottom (level 1).
        private val SEGMENT_IDS = intArrayOf(
            R.id.level7, R.id.level6, R.id.level5,
            R.id.level4, R.id.level3, R.id.level2, R.id.level1
        )

        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val level = DataRepository.getCurrentLevel(context)
            Log.d("BarWidget", "updateAppWidget: level=$level")
            val views = RemoteViews(context.packageName, R.layout.widget_bar)

            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

            for (segIndex in SEGMENT_IDS.indices) {
                // segIndex 0 = level7 (top), segIndex 6 = level1 (bottom)
                val segLevel = 7 - segIndex
                val color = if (segLevel <= level) LEVEL_COLORS[segLevel - 1] else COLOR_INACTIVE
                views.setInt(SEGMENT_IDS[segIndex], "setBackgroundColor", color)

                // Wire a tap on each segment to broadcast ACTION_SET_LEVEL with that level.
                // IMPORTANT: we include the level in the action string to make the Intent truly unique
                val intent = Intent(context, BarWidget::class.java)
                    .setAction("${ACTION_SET_LEVEL}_$segLevel")
                    .putExtra(EXTRA_LEVEL, segLevel)
                
                // Use a unique request code for each segment
                val requestCode = 200 + segLevel
                val pi = PendingIntent.getBroadcast(context, requestCode, intent, flags)
                
                views.setOnClickPendingIntent(SEGMENT_IDS[segIndex], pi)
            }

            views.setTextViewText(R.id.tv_level, level.toString())
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { updateAppWidget(context, appWidgetManager, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d("BarWidget", "onReceive: action=$action")
        super.onReceive(context, intent)
        
        if (action.startsWith(ACTION_SET_LEVEL)) {
            val level = intent.getIntExtra(EXTRA_LEVEL, -1)
            Log.d("BarWidget", "ACTION_SET_LEVEL matched: level=$level")
            if (level in 1..7) {
                DataRepository.saveDriveLevel(context, level)
                DataRepository.autoUpdateDailyDrive(context)
                refreshAll(context)
            }
        }
    }

    private fun refreshAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, BarWidget::class.java))
        ids.forEach { updateAppWidget(context, manager, it) }
    }
}
