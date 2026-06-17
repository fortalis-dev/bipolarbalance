package com.bipolar.balance

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Configuration Activity launched by Android when the user adds a
 * Custom Metric widget from the home-screen widget picker.
 *
 * The user picks which custom data point this widget instance will display.
 */
class CustomMetricWidgetConfig : AppCompatActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var selectedMetricId: String? = null
    private lateinit var btnAdd: Button
    private val radioButtons = mutableListOf<RadioButton>()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Always set RESULT_CANCELED so back-press doesn't create a broken widget
        setResult(RESULT_CANCELED)

        appWidgetId = intent?.extras
            ?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContentView(R.layout.activity_widget_config)

        // Handle window insets
        val root = findViewById<LinearLayout>(android.R.id.content).getChildAt(0)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(
                left = insets.left,
                top = insets.top,
                right = insets.right,
                bottom = insets.bottom
            )
            windowInsets
        }

        btnAdd = findViewById(R.id.btn_add_widget)

        val container = findViewById<LinearLayout>(R.id.config_metrics_container)
        val metrics   = DataRepository.getCustomMetrics(this)
        val dp        = resources.displayMetrics.density

        if (metrics.isEmpty()) {
            container.addView(TextView(this).apply {
                text      = "No custom data points configured yet.\n\nGo to Settings › Custom Data to add some."
                textSize  = 14f
                setTextColor(Color.parseColor("#A09090"))
                gravity   = Gravity.CENTER
                setPadding(0, (32 * dp).toInt(), 0, (32 * dp).toInt())
            })
        } else {
            for (metric in metrics) {
                val rb = RadioButton(this).apply {
                    text     = metric.name
                    id       = metric.id.hashCode() and 0x7FFFFFFF   // unique positive ID
                    tag      = metric.id
                    textSize = 16f
                    setTextColor(Color.parseColor("#3D3030"))
                    setTypeface(typeface, Typeface.NORMAL)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.bottomMargin = (4 * dp).toInt() }
                    setPadding((8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt())
                }

                // Label below radio button shows type
                val typeLabel = TextView(this).apply {
                    text     = when (metric.type) {
                        MetricType.YES_NO  -> "Yes / No"
                        MetricType.RANGE   -> "Scale (${metric.rangeMin}–${metric.rangeMax})"
                        MetricType.COUNTER -> "Counter"
                    }
                    textSize = 11f
                    setTextColor(Color.parseColor("#A09090"))
                    setPadding((32 * dp).toInt(), 0, (8 * dp).toInt(), (8 * dp).toInt())
                }

                val divider = android.view.View(this).apply {
                    setBackgroundColor(Color.parseColor("#1A000000"))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt()
                    )
                }

                rb.setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        selectedMetricId = metric.id
                        // Uncheck all others
                        radioButtons.filter { it !== rb }.forEach { it.isChecked = false }
                        btnAdd.isEnabled = true
                    }
                }

                radioButtons.add(rb)
                container.addView(rb)
                container.addView(typeLabel)
                container.addView(divider)
            }
        }

        btnAdd.setOnClickListener { confirmSelection() }
    }

    private fun confirmSelection() {
        val metricId = selectedMetricId ?: return

        // Persist metric-id → widget-id mapping
        getSharedPreferences("widget_config", MODE_PRIVATE)
            .edit()
            .putString("metric_$appWidgetId", metricId)
            .apply()

        // Force an immediate update of the new widget
        CustomMetricWidget.updateWidget(this, appWidgetId)

        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        finish()
    }
}
