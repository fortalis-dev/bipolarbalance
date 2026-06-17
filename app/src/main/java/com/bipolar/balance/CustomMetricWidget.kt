package com.bipolar.balance

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews

class CustomMetricWidget : AppWidgetProvider() {

    companion object {
        const val ACTION_YESNO_TOGGLE = "com.bipolar.balance.ACTION_YESNO_TOGGLE"
        const val ACTION_COUNTER_INC  = "com.bipolar.balance.ACTION_COUNTER_INC"
        const val ACTION_COUNTER_DEC  = "com.bipolar.balance.ACTION_COUNTER_DEC"
        const val ACTION_RANGE_SET    = "com.bipolar.balance.ACTION_RANGE_SET"

        const val EXTRA_WIDGET_ID  = "widget_id"
        const val EXTRA_METRIC_ID  = "metric_id"
        const val EXTRA_RANGE_STEP = "range_step"

        const val NUM_RANGE_SEGS   = 15

        private val RANGE_SEG_IDS = intArrayOf(
            R.id.rs0,  R.id.rs1,  R.id.rs2,  R.id.rs3,  R.id.rs4,
            R.id.rs5,  R.id.rs6,  R.id.rs7,  R.id.rs8,  R.id.rs9,
            R.id.rs10, R.id.rs11, R.id.rs12, R.id.rs13, R.id.rs14
        )

        private val COLOR_RANGE_ACTIVE   = Color.parseColor("#9AAFCE")
        private val COLOR_RANGE_INACTIVE = Color.parseColor("#D8CECC")

        private fun getMetricId(ctx: Context, widgetId: Int): String? =
            ctx.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE).getString("m_$widgetId", null)

        fun updateWidget(ctx: Context, id: Int) {
            val metricId = getMetricId(ctx, id) ?: return
            val metric   = DataRepository.getCustomMetrics(ctx).find { it.id == metricId } ?: return
            val mgr      = AppWidgetManager.getInstance(ctx)

            val dayKey   = DataRepository.getCurrentDayKey(ctx)
            val value    = DataRepository.getCustomMetricValuesForDay(ctx, dayKey)[metricId]

            when (metric.type) {
                MetricType.YES_NO  -> updateYesNoWidget(ctx, mgr, id, metric, value)
                MetricType.COUNTER -> updateCounterWidget(ctx, mgr, id, metric, value)
                MetricType.RANGE   -> updateRangeWidget(ctx, mgr, id, metric, value)
            }
        }

        private fun updateYesNoWidget(ctx: Context, mgr: AppWidgetManager, id: Int, m: CustomMetric, value: Float?) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_custom_metric_yesno)
            v.setTextViewText(R.id.tv_widget_metric_name, m.name)

            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val intent = Intent(ctx, CustomMetricWidget::class.java).apply {
                action = ACTION_YESNO_TOGGLE
                putExtra(EXTRA_WIDGET_ID, id)
                putExtra(EXTRA_METRIC_ID, m.id)
            }
            v.setOnClickPendingIntent(R.id.tv_widget_metric_value, PendingIntent.getBroadcast(ctx, id, intent, flags))

            val label = if (value == 1f) "YES" else if (value == 0f) "NO" else "—"
            v.setTextViewText(R.id.tv_widget_metric_value, label)

            val color = when (value) {
                1f   -> Color.parseColor("#F4956A")
                0f   -> Color.parseColor("#B0A8A4")
                else -> Color.parseColor("#D8CECC")
            }
            v.setInt(R.id.tv_widget_metric_value, "setTextColor", color)
            
            v.setOnClickPendingIntent(R.id.tv_widget_metric_name, openAppIntent(ctx, id))
            mgr.updateAppWidget(id, v)
        }

        private fun updateCounterWidget(ctx: Context, mgr: AppWidgetManager, id: Int, m: CustomMetric, value: Float?) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_custom_metric)
            v.setTextViewText(R.id.tv_widget_metric_name, m.name)
            v.setTextViewText(R.id.tv_widget_metric_value, value?.toInt()?.toString() ?: "0")

            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            
            val dec = Intent(ctx, CustomMetricWidget::class.java).apply {
                action = ACTION_COUNTER_DEC
                putExtra(EXTRA_WIDGET_ID, id)
                putExtra(EXTRA_METRIC_ID, m.id)
            }
            v.setOnClickPendingIntent(R.id.tv_widget_btn_dec, PendingIntent.getBroadcast(ctx, id * 2, dec, flags))
            
            val inc = Intent(ctx, CustomMetricWidget::class.java).apply {
                action = ACTION_COUNTER_INC
                putExtra(EXTRA_WIDGET_ID, id)
                putExtra(EXTRA_METRIC_ID, m.id)
            }
            v.setOnClickPendingIntent(R.id.tv_widget_btn_inc, PendingIntent.getBroadcast(ctx, id * 2 + 1, inc, flags))

            v.setViewVisibility(R.id.tv_widget_btn_dec, if (m.counterWidgetIncOnly) View.GONE else View.VISIBLE)

            v.setOnClickPendingIntent(R.id.tv_widget_metric_name, openAppIntent(ctx, id))
            mgr.updateAppWidget(id, v)
        }

        private fun updateRangeWidget(ctx: Context, mgr: AppWidgetManager, id: Int, m: CustomMetric, value: Float?) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_custom_metric_range)
            v.setTextViewText(R.id.tv_widget_metric_name, m.name)

            val rawValue = value ?: -1f
            val rMin = m.rangeMin
            val rMax = m.rangeMax
            val range = (rMax - rMin).coerceAtLeast(1)
            
            val activeStep = if (rawValue < 0f) -1 
                             else ((rawValue - rMin) / range * (NUM_RANGE_SEGS - 1)).toInt()

            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            for (i in 0 until NUM_RANGE_SEGS) {
                val color = if (i <= activeStep) COLOR_RANGE_ACTIVE else COLOR_RANGE_INACTIVE
                v.setInt(RANGE_SEG_IDS[i], "setBackgroundColor", color)

                val intent = Intent(ctx, CustomMetricWidget::class.java).apply {
                    action = ACTION_RANGE_SET
                    putExtra(EXTRA_WIDGET_ID, id)
                    putExtra(EXTRA_METRIC_ID, m.id)
                    putExtra(EXTRA_RANGE_STEP, i)
                }
                v.setOnClickPendingIntent(RANGE_SEG_IDS[i], PendingIntent.getBroadcast(ctx, id * 20 + i, intent, flags))
            }

            v.setTextViewText(R.id.tv_widget_metric_value, if (rawValue < 0f) "—" else rawValue.toInt().toString())
            v.setOnClickPendingIntent(R.id.tv_widget_metric_name, openAppIntent(ctx, id))
            mgr.updateAppWidget(id, v)
        }

        private fun openAppIntent(ctx: Context, id: Int): PendingIntent {
            val intent = Intent(ctx, MainActivity::class.java)
            return PendingIntent.getActivity(ctx, id, intent, PendingIntent.FLAG_IMMUTABLE)
        }

        fun refreshAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(android.content.ComponentName(ctx, CustomMetricWidget::class.java))
            ids.forEach { updateWidget(ctx, it) }
        }
    }

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { updateWidget(ctx, it) }
    }

    override fun onDeleted(ctx: Context, ids: IntArray) {
        val p = ctx.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE).edit()
        ids.forEach { p.remove("m_$it") }
        p.apply()
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        val action = intent.action ?: return
        val wId = intent.getIntExtra(EXTRA_WIDGET_ID, -1)
        val mId = intent.getStringExtra(EXTRA_METRIC_ID) ?: return
        val dayKey = DataRepository.getCurrentDayKey(ctx)

        when (action) {
            ACTION_YESNO_TOGGLE -> {
                val current = DataRepository.getCustomMetricValuesForDay(ctx, dayKey)[mId] ?: -1f
                val next = if (current == 1f) 0f else if (current == 0f) -1f else 1f
                DataRepository.saveCustomMetricValue(ctx, dayKey, mId, next)
                updateWidget(ctx, wId)
            }
            ACTION_COUNTER_INC -> {
                val current = DataRepository.getCustomMetricValuesForDay(ctx, dayKey)[mId] ?: 0f
                DataRepository.saveCustomMetricValue(ctx, dayKey, mId, current + 1f)
                updateWidget(ctx, wId)
            }
            ACTION_COUNTER_DEC -> {
                val current = DataRepository.getCustomMetricValuesForDay(ctx, dayKey)[mId] ?: 0f
                DataRepository.saveCustomMetricValue(ctx, dayKey, mId, (current - 1f).coerceAtLeast(0f))
                updateWidget(ctx, wId)
            }
            ACTION_RANGE_SET -> {
                val step = intent.getIntExtra(EXTRA_RANGE_STEP, 0)
                val m = DataRepository.getCustomMetrics(ctx).find { it.id == mId } ?: return
                val rMin = m.rangeMin
                val rMax = m.rangeMax
                val newVal = rMin + (step.toFloat() / (NUM_RANGE_SEGS - 1)) * (rMax - rMin)
                DataRepository.saveCustomMetricValue(ctx, dayKey, mId, Math.round(newVal).toFloat())
                updateWidget(ctx, wId)
            }
        }
    }
}
