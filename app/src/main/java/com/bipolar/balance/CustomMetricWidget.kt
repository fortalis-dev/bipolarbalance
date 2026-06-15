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
 * Home-screen widget for a single custom data point.
 *
 * - YES_NO  : tap the symbol to toggle between ✓ and ✗
 * - COUNTER : tap − / + buttons to decrement / increment
 * - RANGE   : tap a segment on the vertical bar to set the value
 */
class CustomMetricWidget : AppWidgetProvider() {

    companion object {

        const val ACTION_YESNO_TOGGLE = "com.bipolar.balance.ACTION_YESNO_TOGGLE"
        const val ACTION_COUNTER_INC  = "com.bipolar.balance.ACTION_COUNTER_INC"
        const val ACTION_COUNTER_DEC  = "com.bipolar.balance.ACTION_COUNTER_DEC"
        const val ACTION_RANGE_SET    = "com.bipolar.balance.ACTION_RANGE_SET"

        const val EXTRA_WIDGET_ID  = "widget_id"
        const val EXTRA_METRIC_ID  = "metric_id"
        const val EXTRA_RANGE_STEP = "range_step"  // 0 = min, 14 = max

        private const val NUM_RANGE_SEGS = 15

        // In widget_custom_metric_range.xml: rs14 (top = max) … rs0 (bottom = min)
        private val RANGE_SEG_IDS = intArrayOf(
            R.id.rs14, R.id.rs13, R.id.rs12, R.id.rs11, R.id.rs10,
            R.id.rs9,  R.id.rs8,  R.id.rs7,  R.id.rs6,  R.id.rs5,
            R.id.rs4,  R.id.rs3,  R.id.rs2,  R.id.rs1,  R.id.rs0
        )

        private val COLOR_RANGE_ACTIVE   = Color.parseColor("#7B52A9")
        private val COLOR_RANGE_INACTIVE = Color.parseColor("#BDBDBD")

        // ── Shared helpers ─────────────────────────────────────────────────────

        fun getMetricId(ctx: Context, appWidgetId: Int): String? =
            ctx.getSharedPreferences("widget_config", Context.MODE_PRIVATE)
                .getString("metric_$appWidgetId", null)

        fun updateWidget(ctx: Context, appWidgetId: Int) {
            val mgr      = AppWidgetManager.getInstance(ctx)
            val metricId = getMetricId(ctx, appWidgetId)
            val metric   = if (metricId != null)
                DataRepository.getCustomMetrics(ctx).find { it.id == metricId }
            else null

            if (metric == null) {
                // Fallback: show placeholder with all layouts' common IDs
                val views = RemoteViews(ctx.packageName, R.layout.widget_custom_metric)
                views.setTextViewText(R.id.tv_widget_metric_name,  if (metricId == null) "Tap to configure" else "Deleted metric")
                views.setTextViewText(R.id.tv_widget_metric_value, "—")
                views.setTextViewText(R.id.tv_widget_metric_unit,  "")
                mgr.updateAppWidget(appWidgetId, views)
                return
            }

            val dayKey   = DataRepository.getCurrentDayKey()
            val valueMap = DataRepository.getCustomMetricValuesForDay(ctx, dayKey)
            val rawValue = valueMap[metricId]

            when (metric.type) {
                MetricType.YES_NO  -> updateYesNoWidget (ctx, mgr, appWidgetId, metric, rawValue)
                MetricType.COUNTER -> updateCounterWidget(ctx, mgr, appWidgetId, metric, rawValue)
                MetricType.RANGE   -> updateRangeWidget  (ctx, mgr, appWidgetId, metric, rawValue)
            }
        }

        // ── YES/NO ─────────────────────────────────────────────────────────────

        private fun updateYesNoWidget(
            ctx: Context, mgr: AppWidgetManager, id: Int,
            metric: CustomMetric, rawValue: Float?
        ) {
            val views = RemoteViews(ctx.packageName, R.layout.widget_custom_metric_yesno)
            val isYes = rawValue == 1f
            views.setTextViewText(R.id.tv_widget_metric_name,  metric.name)
            views.setTextViewText(R.id.tv_widget_metric_value, when {
                rawValue == null -> "—"
                isYes            -> "✓"
                else             -> "✗"
            })
            views.setTextViewText(R.id.tv_widget_metric_unit, "tap to toggle")

            // Tapping the symbol or name toggles the value
            val toggleIntent = Intent(ctx, CustomMetricWidget::class.java)
                .setAction(ACTION_YESNO_TOGGLE)
                .putExtra(EXTRA_WIDGET_ID, id)
                .putExtra(EXTRA_METRIC_ID, metric.id)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val togglePi = PendingIntent.getBroadcast(ctx, id, toggleIntent, flags)
            views.setOnClickPendingIntent(R.id.tv_widget_metric_value, togglePi)
            views.setOnClickPendingIntent(R.id.tv_widget_metric_name,  togglePi)
            views.setOnClickPendingIntent(R.id.tv_widget_metric_unit,  togglePi)

            mgr.updateAppWidget(id, views)
        }

        // ── COUNTER ────────────────────────────────────────────────────────────

        private fun updateCounterWidget(
            ctx: Context, mgr: AppWidgetManager, id: Int,
            metric: CustomMetric, rawValue: Float?
        ) {
            val count = rawValue?.toInt() ?: 0
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

            val incIntent = Intent(ctx, CustomMetricWidget::class.java)
                .setAction(ACTION_COUNTER_INC)
                .putExtra(EXTRA_WIDGET_ID, id)
                .putExtra(EXTRA_METRIC_ID, metric.id)
            val incPi = PendingIntent.getBroadcast(ctx, id * 10 + 1, incIntent, flags)
            val openApp = openAppIntent(ctx, id)

            if (metric.counterWidgetIncOnly) {
                // + only layout
                val views = RemoteViews(ctx.packageName, R.layout.widget_custom_metric_inc)
                views.setTextViewText(R.id.tv_widget_metric_name,  metric.name)
                views.setTextViewText(R.id.tv_widget_metric_value, count.toString())
                views.setOnClickPendingIntent(R.id.tv_widget_btn_inc,       incPi)
                views.setOnClickPendingIntent(R.id.tv_widget_metric_value,  openApp)
                views.setOnClickPendingIntent(R.id.tv_widget_metric_name,   openApp)
                mgr.updateAppWidget(id, views)
            } else {
                // +/- layout
                val views = RemoteViews(ctx.packageName, R.layout.widget_custom_metric)
                views.setTextViewText(R.id.tv_widget_metric_name,  metric.name)
                views.setTextViewText(R.id.tv_widget_metric_value, count.toString())
                views.setTextViewText(R.id.tv_widget_metric_unit,  "today")
                views.setOnClickPendingIntent(R.id.tv_widget_btn_inc, incPi)
                val decIntent = Intent(ctx, CustomMetricWidget::class.java)
                    .setAction(ACTION_COUNTER_DEC)
                    .putExtra(EXTRA_WIDGET_ID, id)
                    .putExtra(EXTRA_METRIC_ID, metric.id)
                views.setOnClickPendingIntent(
                    R.id.tv_widget_btn_dec,
                    PendingIntent.getBroadcast(ctx, id * 10 + 2, decIntent, flags)
                )
                views.setOnClickPendingIntent(R.id.tv_widget_metric_value, openApp)
                mgr.updateAppWidget(id, views)
            }
        }

        // ── RANGE ──────────────────────────────────────────────────────────────

        private fun updateRangeWidget(
            ctx: Context, mgr: AppWidgetManager, id: Int,
            metric: CustomMetric, rawValue: Float?
        ) {
            val rMin  = metric.rangeMin
            val rMax  = metric.rangeMax
            val range = (rMax - rMin).coerceAtLeast(1)
            val views = RemoteViews(ctx.packageName, R.layout.widget_custom_metric_range)
            views.setTextViewText(R.id.tv_widget_metric_name, metric.name)
            views.setTextViewText(R.id.tv_widget_metric_value, if (rawValue != null) rawValue.toInt().toString() else "—")

            val flags         = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            // Map rawValue → the filled step index (0 = bottom = min, 14 = top = max)
            val filledUpTo: Int = if (rawValue != null) {
                Math.round((rawValue - rMin).toFloat() / range * (NUM_RANGE_SEGS - 1))
                    .coerceIn(0, NUM_RANGE_SEGS - 1)
            } else -1

            for (step in 0 until NUM_RANGE_SEGS) {
                // RANGE_SEG_IDS[0] = rs14 (top = max) … RANGE_SEG_IDS[14] = rs0 (bottom = min)
                // visualIndex 0 = top segment = step (NUM_RANGE_SEGS-1), bottom = step 0
                val visualIndex = NUM_RANGE_SEGS - 1 - step   // 14 down to 0
                val segId = RANGE_SEG_IDS[visualIndex]

                val color = if (step <= filledUpTo) COLOR_RANGE_ACTIVE else COLOR_RANGE_INACTIVE
                views.setInt(segId, "setBackgroundColor", color)

                val setIntent = Intent(ctx, CustomMetricWidget::class.java)
                    .setAction(ACTION_RANGE_SET)
                    .putExtra(EXTRA_WIDGET_ID, id)
                    .putExtra(EXTRA_METRIC_ID, metric.id)
                    .putExtra(EXTRA_RANGE_STEP, step)
                val pi = PendingIntent.getBroadcast(ctx, id * 100 + step, setIntent, flags)
                views.setOnClickPendingIntent(segId, pi)
            }

            mgr.updateAppWidget(id, views)
        }

        private fun openAppIntent(ctx: Context, requestCode: Int): PendingIntent =
            PendingIntent.getActivity(
                ctx, requestCode,
                Intent(ctx, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        /** Refresh every placed instance of this widget. */
        fun refreshAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, CustomMetricWidget::class.java))
            ids.forEach { updateWidget(ctx, it) }
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { updateWidget(ctx, it) }
    }

    override fun onDeleted(ctx: Context, appWidgetIds: IntArray) {
        val prefs = ctx.getSharedPreferences("widget_config", Context.MODE_PRIVATE).edit()
        appWidgetIds.forEach { prefs.remove("metric_$it") }
        prefs.apply()
    }

    // ── Broadcast handling for tap interactions ────────────────────────────────

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        val widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, -1)
        val metricId = intent.getStringExtra(EXTRA_METRIC_ID) ?: return
        if (widgetId == -1) return

        val dayKey = DataRepository.getCurrentDayKey()
        val existing = DataRepository.getCustomMetricValuesForDay(ctx, dayKey)[metricId]

        when (intent.action) {
            ACTION_YESNO_TOGGLE -> {
                val newVal = if (existing == 1f) 0f else 1f
                DataRepository.saveCustomMetricValue(ctx, dayKey, metricId, newVal)
                updateWidget(ctx, widgetId)
            }
            ACTION_COUNTER_INC -> {
                val newVal = (existing ?: 0f) + 1f
                DataRepository.saveCustomMetricValue(ctx, dayKey, metricId, newVal)
                updateWidget(ctx, widgetId)
            }
            ACTION_COUNTER_DEC -> {
                val newVal = ((existing ?: 0f) - 1f).coerceAtLeast(0f)
                DataRepository.saveCustomMetricValue(ctx, dayKey, metricId, newVal)
                updateWidget(ctx, widgetId)
            }
            ACTION_RANGE_SET -> {
                val step   = intent.getIntExtra(EXTRA_RANGE_STEP, 0)
                val metric = DataRepository.getCustomMetrics(ctx).find { it.id == metricId } ?: return
                val range  = (metric.rangeMax - metric.rangeMin).coerceAtLeast(1)
                val value  = metric.rangeMin + Math.round(step.toFloat() / (NUM_RANGE_SEGS - 1) * range)
                DataRepository.saveCustomMetricValue(ctx, dayKey, metricId, value.toFloat())
                updateWidget(ctx, widgetId)
            }
        }
    }
}
