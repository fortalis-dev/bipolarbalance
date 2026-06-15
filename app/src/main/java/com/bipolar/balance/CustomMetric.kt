package com.bipolar.balance

import org.json.JSONObject
import java.util.UUID

enum class MetricType { YES_NO, RANGE, COUNTER }

/**
 * Definition of a user-created custom metric.
 * Stored once per metric; separate [CustomMetricValue] records hold per-day values.
 */
data class CustomMetric(
    val id:               String = UUID.randomUUID().toString(),
    val name:             String,
    val type:             MetricType,
    val widgetEnabled:    Boolean = false,
    val persistsToNextDay: Boolean = false,
    /** For RANGE type: minimum value on the scale (inclusive). */
    val rangeMin:         Int = 0,
    /** For RANGE type: maximum value on the scale (inclusive). */
    val rangeMax:         Int = 24,
    /** For COUNTER type: show only the + button on the widget (no − button). */
    val counterWidgetIncOnly: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id",       id)
        .put("name",     name)
        .put("type",     type.name)
        .put("widget",   widgetEnabled)
        .put("persist",  persistsToNextDay)
        .put("rmin",     rangeMin)
        .put("rmax",     rangeMax)
        .put("inc_only", counterWidgetIncOnly)

    companion object {
        fun fromJson(o: JSONObject): CustomMetric {
            // "HOURS" was the old name for RANGE — maintain backward compatibility
            val typeStr = o.optString("type", "YES_NO")
                .let { if (it == "HOURS") "RANGE" else it }
            return CustomMetric(
                id                    = o.getString("id"),
                name                  = o.getString("name"),
                type                  = try { MetricType.valueOf(typeStr) }
                                        catch (_: Exception) { MetricType.YES_NO },
                widgetEnabled         = o.optBoolean("widget",   false),
                persistsToNextDay     = o.optBoolean("persist",  false),
                rangeMin              = o.optInt("rmin", 0),
                rangeMax              = o.optInt("rmax", 24),
                counterWidgetIncOnly  = o.optBoolean("inc_only", true),
            )
        }
    }
}

/**
 * A daily value for one custom metric.
 * YES_NO:  1f = yes, 0f = no, -1f = not set.
 * RANGE:   any Float within [rangeMin, rangeMax].
 * COUNTER: non-negative Int stored as Float.
 */
data class CustomMetricValue(
    val dayKey:   String,
    val metricId: String,
    val value:    Float,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("day", dayKey)
        .put("mid", metricId)
        .put("v",   value)

    companion object {
        fun fromJson(o: JSONObject) = CustomMetricValue(
            dayKey   = o.getString("day"),
            metricId = o.getString("mid"),
            value    = o.getDouble("v").toFloat(),
        )
    }
}
