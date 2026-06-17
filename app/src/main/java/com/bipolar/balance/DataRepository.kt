package com.bipolar.balance

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.pow
import kotlin.math.roundToInt

object DataRepository {

    private const val PREFS_NAME         = "bar_level_prefs_v2"
    private const val KEY_LEVEL          = "current_drive_level"
    private const val KEY_SUSPEND        = "current_suspend_minutes"
    private const val KEY_DRIVE_LOG      = "drive_log"
    private const val KEY_SUSPEND_LOG    = "suspend_log"
    private const val KEY_DAILY_LOG      = "daily_log"
    private const val KEY_LAST_BACKUP_MS   = "last_backup_ms"
    private const val KEY_CUSTOM_METRICS  = "custom_metrics"
    private const val KEY_CUSTOM_VALUES   = "custom_values"
    private const val KEY_NOTES_LOG       = "notes_log"
    private const val KEY_SUSPEND_WIDGET_ENABLED = "suspend_widget_enabled"
    private const val KEY_SUSPEND_TRACKING_ENABLED = "suspend_tracking_enabled"
    private const val KEY_NOTIFS_ENABLED          = "notifications_enabled"
    private const val KEY_NOTIF_TIME_HOUR         = "notification_time_hour"
    private const val KEY_NOTIF_TIME_MIN          = "notification_time_min"
    private const val KEY_START_DAY_HOUR          = "start_day_hour"
    private const val KEY_START_DAY_MIN           = "start_day_min"

    fun getNotificationsEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_NOTIFS_ENABLED, false)

    fun setNotificationsEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_NOTIFS_ENABLED, enabled).apply()

    fun getNotificationTime(ctx: Context): Pair<Int, Int> =
        prefs(ctx).getInt(KEY_NOTIF_TIME_HOUR, 20) to prefs(ctx).getInt(KEY_NOTIF_TIME_MIN, 0)

    fun setNotificationTime(ctx: Context, hour: Int, min: Int) =
        prefs(ctx).edit()
            .putInt(KEY_NOTIF_TIME_HOUR, hour)
            .putInt(KEY_NOTIF_TIME_MIN, min)
            .apply()

    fun getStartDayTime(ctx: Context): Pair<Int, Int> =
        prefs(ctx).getInt(KEY_START_DAY_HOUR, 8) to prefs(ctx).getInt(KEY_START_DAY_MIN, 0)

    fun setStartDayTime(ctx: Context, hour: Int, min: Int) =
        prefs(ctx).edit()
            .putInt(KEY_START_DAY_HOUR, hour)
            .putInt(KEY_START_DAY_MIN, min)
            .apply()

    fun getSuspendTrackingEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SUSPEND_TRACKING_ENABLED, true)

    fun setSuspendTrackingEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_SUSPEND_TRACKING_ENABLED, enabled).apply()

    fun getSuspendWidgetEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SUSPEND_WIDGET_ENABLED, true)

    fun setSuspendWidgetEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_SUSPEND_WIDGET_ENABLED, enabled).apply()

    fun getLogicalDayKey(ms: Long, ctx: Context): String {
        val cal = Calendar.getInstance().apply { timeInMillis = ms }
        val (startH, startM) = getStartDayTime(ctx)
        
        val currentH = cal.get(Calendar.HOUR_OF_DAY)
        val currentM = cal.get(Calendar.MINUTE)
        
        if (currentH < startH || (currentH == startH && currentM < startM)) {
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
    }

    fun getCurrentDayKey(ctx: Context): String = getLogicalDayKey(System.currentTimeMillis(), ctx)

    fun dayKeyToMs(dayKey: String): Long =
        try { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dayKey)?.time ?: 0L }
        catch (_: Exception) { 0L }

    /** Returns the [startMs, endMs) window for a logical day based on user preference. */
    fun getLogicalDayWindow(dayKey: String, ctx: Context): Pair<Long, Long> {
        val (startH, startM) = getStartDayTime(ctx)
        val startMs = dayKeyToMs(dayKey) + (startH * 3600_000L) + (startM * 60_000L)
        return startMs to (startMs + 86_400_000L)
    }

    fun getCurrentLevel(ctx: Context): Int =
        prefs(ctx).getInt(KEY_LEVEL, 1)

    fun getCurrentSuspendMinutes(ctx: Context): Int =
        prefs(ctx).getInt(KEY_SUSPEND, 0)

    fun saveDailyEntry(
        ctx: Context,
        driveLevel: Int,
        suspendMinutes: Int,
        driveOverridden: Boolean = false,
        note: String? = null,            // null = preserve existing note
        autoDriveLevel: Int? = null,     // null = derive from context
    ) {
        val dayKey       = getCurrentDayKey(ctx)
        val now          = System.currentTimeMillis()
        val existing     = getTodaysDailyEntry(ctx)
        val existingNote = existing?.note.orEmpty()
        val finalNote    = note ?: existingNote
        // autoDriveLevel: explicitly provided > auto-mode (use driveLevel) > override (preserve existing)
        val finalAutoLevel = autoDriveLevel
            ?: if (!driveOverridden) driveLevel
            else (existing?.autoDriveLevel ?: driveLevel)
        val entry = DailyEntry(dayKey, driveLevel, finalAutoLevel, suspendMinutes, now, driveOverridden, finalNote)
        val list   = getDailyEntries(ctx).toMutableList()
        val idx    = list.indexOfFirst { it.dayKey == dayKey }
        if (idx >= 0) list[idx] = entry else list.add(entry)
        val arr = JSONArray().also { a ->
            list.sortedBy { it.dayKey }.forEach { a.put(it.toJson()) }
        }
        val editor = prefs(ctx).edit()
        // FIX: never propagate the daily average back to the "current level" indicator (KEY_LEVEL).
        // KEY_LEVEL should only be updated when the user explicitly taps a value (in saveDriveLevel).
        editor.putInt(KEY_SUSPEND, suspendMinutes)
        editor.putString(KEY_DAILY_LOG, arr.toString())
        editor.apply()
    }

    fun getTodaysDailyEntry(ctx: Context): DailyEntry? =
        getDailyEntries(ctx).find { it.dayKey == getCurrentDayKey(ctx) }

    fun getDailyEntries(ctx: Context): List<DailyEntry> {
        val arr = parseArrayOrEmpty(prefs(ctx).getString(KEY_DAILY_LOG, "[]"))
        return (0 until arr.length()).mapNotNull {
            try { DailyEntry.fromJson(arr.getJSONObject(it)) } catch (_: Exception) { null }
        }
    }

    fun getDailyHistogramCounts(ctx: Context, sinceMs: Long = 0L): IntArray {
        val counts = IntArray(7)
        getDailyEntries(ctx)
            .filter { it.lastUpdatedMs >= sinceMs }
            .forEach { counts[it.driveLevel - 1]++ }
        return counts
    }

    fun getDailySuspendAvgPerLevel(ctx: Context, sinceMs: Long = 0L): FloatArray {
        val sums   = FloatArray(7)
        val counts = IntArray(7)
        getDailyEntries(ctx)
            .filter { it.lastUpdatedMs >= sinceMs }
            .forEach { e ->
                val b = e.driveLevel - 1
                sums[b]   = sums[b] + e.suspendMinutes.toFloat()
                counts[b] = counts[b] + 1
            }
        return FloatArray(7) { i -> if (counts[i] > 0) sums[i] / counts[i] else 0f }
    }

    fun saveDriveLevel(ctx: Context, level: Int) {
        val entry = DriveEntry(System.currentTimeMillis(), level)
        val arr   = loadDriveLog(ctx)
        arr.put(entry.toJson())
        prefs(ctx).edit()
            .putInt(KEY_LEVEL, level)
            .putString(KEY_DRIVE_LOG, arr.toString())
            .commit()
    }

    fun saveSuspend(ctx: Context, minutes: Int) {
        val entry = SuspendEntry(System.currentTimeMillis(), minutes)
        val arr   = loadSuspendLog(ctx)
        arr.put(entry.toJson())
        prefs(ctx).edit()
            .putInt(KEY_SUSPEND, minutes)
            .putString(KEY_SUSPEND_LOG, arr.toString())
            .commit()
    }

    fun getDriveEntries(ctx: Context): List<DriveEntry> {
        val arr = loadDriveLog(ctx)
        return (0 until arr.length()).map { DriveEntry.fromJson(arr.getJSONObject(it)) }
    }

    /**
     * Returns the time-weighted average Drive level for [dayKey] based on user preference.
     *
     * Uses linear interpolation between taps:
     * - The first tap's value is assumed to start at day start.
     * - Each interval between taps uses the midpoint value (linear transition).
     * - The last tap's value is assumed to hold until next day start.
     */
    fun getDailyDriveAverage(ctx: Context, dayKey: String): Float? {
        val (windowStart, windowEnd) = getLogicalDayWindow(dayKey, ctx)
        val entries = getDriveEntries(ctx)
            .filter { it.timestampMs in windowStart until windowEnd }
            .sortedBy { it.timestampMs }
        
        if (entries.isEmpty()) return null
        
        var totalWeightedValue = 0.0
        val totalDuration = (windowEnd - windowStart).toDouble()

        // 1. Initial period: [Start of Day, First Entry]
        totalWeightedValue += entries.first().level * (entries.first().timestampMs - windowStart)

        // 2. Intermediate periods: [Entry i, Entry i+1]
        for (i in 0 until entries.size - 1) {
            val duration = entries[i+1].timestampMs - entries[i].timestampMs
            val avgValue = (entries[i].level + entries[i+1].level) / 2.0
            totalWeightedValue += avgValue * duration
        }

        // 3. Final period: [Last Entry, End of Day]
        totalWeightedValue += entries.last().level * (windowEnd - entries.last().timestampMs)

        return (totalWeightedValue / totalDuration).toFloat()
    }

    /**
     * Called after any raw [DriveEntry] is saved.
     * Recomputes today's average and writes it to today's [DailyEntry],
     * UNLESS the user has manually overridden the drive level.
     */
    fun autoUpdateDailyDrive(ctx: Context) {
        val dayKey   = getCurrentDayKey(ctx)
        val avg      = getDailyDriveAverage(ctx, dayKey) ?: return
        val existing = getTodaysDailyEntry(ctx)
        if (existing != null && existing.driveOverridden) return
        val level   = avg.roundToInt().coerceIn(1, 7)
        // Set default suspend to 7h (420 min) if brand new entry
        val suspend = existing?.suspendMinutes ?: 420
        saveDailyEntry(ctx, level, suspend, driveOverridden = false, autoDriveLevel = level)
    }

    fun getSuspendEntries(ctx: Context): List<SuspendEntry> {
        val arr = loadSuspendLog(ctx)
        return (0 until arr.length()).map { SuspendEntry.fromJson(arr.getJSONObject(it)) }
    }

    fun exportJson(ctx: Context): String {
        val root = JSONObject()
        root.put("daily_log",      parseArrayOrEmpty(prefs(ctx).getString(KEY_DAILY_LOG, "[]")))
        root.put("drive_log",      loadDriveLog(ctx))
        root.put("suspend_log",    loadSuspendLog(ctx))
        root.put("notes_log",      parseArrayOrEmpty(prefs(ctx).getString(KEY_NOTES_LOG, "[]")))
        root.put("custom_metrics", parseArrayOrEmpty(prefs(ctx).getString(KEY_CUSTOM_METRICS, "[]")))
        root.put("custom_values",  parseArrayOrEmpty(prefs(ctx).getString(KEY_CUSTOM_VALUES, "[]")))
        return root.toString(2)
    }

    enum class ImportMode { MERGE, OVERWRITE_LOCAL }

    fun importJson(ctx: Context, json: String, mode: ImportMode = ImportMode.MERGE) {
        val root = JSONObject(json)

        if (mode == ImportMode.OVERWRITE_LOCAL) {
            prefs(ctx).edit()
                .putString(KEY_DAILY_LOG,      root.optString("daily_log", "[]"))
                .putString(KEY_DRIVE_LOG,      root.optString("drive_log", "[]"))
                .putString(KEY_SUSPEND_LOG,    root.optString("suspend_log", "[]"))
                .putString(KEY_NOTES_LOG,      root.optString("notes_log", "[]"))
                .putString(KEY_CUSTOM_METRICS, root.optString("custom_metrics", "[]"))
                .putString(KEY_CUSTOM_VALUES,  root.optString("custom_values", "[]"))
                .apply()
            return
        }

        // --- MERGE LOGIC ---

        // 1. Daily Entries (Conflict Resolution: pick entry with more data)
        val localDailyMap  = getDailyEntries(ctx).associateBy { it.dayKey }.toMutableMap()
        val remoteDailyArr = root.optJSONArray("daily_log") ?: JSONArray()
        val allCustomValues = getCustomMetricValuesForAllDays(ctx) // helper below

        for (i in 0 until remoteDailyArr.length()) {
            try {
                val remoteEntry = DailyEntry.fromJson(remoteDailyArr.getJSONObject(i))
                val localEntry  = localDailyMap[remoteEntry.dayKey]
                if (localEntry == null) {
                    localDailyMap[remoteEntry.dayKey] = remoteEntry
                } else {
                    // Conflict! Pick one with most data.
                    // Score = (1 if note) + (count of custom values) + (1 if driveOverridden)
                    val localScore  = (if (localEntry.note.isNotBlank()) 1 else 0) +
                            (allCustomValues[localEntry.dayKey]?.size ?: 0) +
                            (if (localEntry.driveOverridden) 1 else 0)
                    val remoteScore = (if (remoteEntry.note.isNotBlank()) 1 else 0) +
                            // remote score for custom values is harder to calc without parsing them all first
                            // but let's assume remote has its own values. For now, keep it simple:
                            (if (remoteEntry.note.length > localEntry.note.length) 1 else 0)
                    
                    if (remoteScore > localScore) localDailyMap[remoteEntry.dayKey] = remoteEntry
                }
            } catch (_: Exception) {}
        }

        // 2. Drive & Suspend logs (Union by timestamp)
        val existingDrive   = getDriveEntries(ctx).associateBy { it.timestampMs }.toMutableMap()
        val existingSuspend = getSuspendEntries(ctx).associateBy { it.timestampMs }.toMutableMap()
        val importDrive     = root.optJSONArray("drive_log") ?: JSONArray()
        val importSuspend   = root.optJSONArray("suspend_log") ?: JSONArray()
        for (i in 0 until importDrive.length()) {
            try {
                val e = DriveEntry.fromJson(importDrive.getJSONObject(i))
                existingDrive.putIfAbsent(e.timestampMs, e)
            } catch (_: Exception) {}
        }
        for (i in 0 until importSuspend.length()) {
            try {
                val e = SuspendEntry.fromJson(importSuspend.getJSONObject(i))
                existingSuspend.putIfAbsent(e.timestampMs, e)
            } catch (_: Exception) {}
        }

        // 3. Notes (Union by ID)
        val existingNotes  = getNoteEntries(ctx).associateBy { it.id }.toMutableMap()
        val importNotesArr = root.optJSONArray("notes_log") ?: JSONArray()
        for (i in 0 until importNotesArr.length()) {
            try {
                val e = NoteEntry.fromJson(importNotesArr.getJSONObject(i))
                existingNotes.putIfAbsent(e.id, e)
            } catch (_: Exception) {}
        }

        // 4. Custom Metrics (Union by ID)
        val existingMetrics = getCustomMetrics(ctx).associateBy { it.id }.toMutableMap()
        val importMetricsArr = root.optJSONArray("custom_metrics") ?: JSONArray()
        for (i in 0 until importMetricsArr.length()) {
            try {
                val m = CustomMetric.fromJson(importMetricsArr.getJSONObject(i))
                existingMetrics.putIfAbsent(m.id, m)
            } catch (_: Exception) {}
        }

        // 5. Custom Values (Union by dayKey + metricId)
        val localValues = getCustomMetricValuesForAllDays(ctx).toMutableMap()
        val importValuesArr = root.optJSONArray("custom_values") ?: JSONArray()
        for (i in 0 until importValuesArr.length()) {
            try {
                val v = CustomMetricValue.fromJson(importValuesArr.getJSONObject(i))
                val dayMap = localValues.getOrPut(v.dayKey) { mutableMapOf() }
                dayMap.putIfAbsent(v.metricId, v.value)
            } catch (_: Exception) {}
        }

        // Serialize back
        val mergedDaily = JSONArray().also { a ->
            localDailyMap.values.sortedBy { it.dayKey }.forEach { a.put(it.toJson()) }
        }
        val mergedDrive = JSONArray().also { a ->
            existingDrive.values.sortedBy { it.timestampMs }.forEach { a.put(it.toJson()) }
        }
        val mergedSuspend = JSONArray().also { a ->
            existingSuspend.values.sortedBy { it.timestampMs }.forEach { a.put(it.toJson()) }
        }
        val mergedNotes = JSONArray().also { a ->
            existingNotes.values.sortedBy { it.timestampMs }.forEach { a.put(it.toJson()) }
        }
        val mergedMetrics = JSONArray().also { a ->
            existingMetrics.values.forEach { a.put(it.toJson()) }
        }
        val flattenedValues = JSONArray().also { a ->
            localValues.forEach { (day, map) ->
                map.forEach { (mid, v) -> a.put(CustomMetricValue(day, mid, v).toJson()) }
            }
        }

        prefs(ctx).edit()
            .putString(KEY_DAILY_LOG,      mergedDaily.toString())
            .putString(KEY_DRIVE_LOG,      mergedDrive.toString())
            .putString(KEY_SUSPEND_LOG,    mergedSuspend.toString())
            .putString(KEY_NOTES_LOG,      mergedNotes.toString())
            .putString(KEY_CUSTOM_METRICS, mergedMetrics.toString())
            .putString(KEY_CUSTOM_VALUES,  flattenedValues.toString())
            .apply()
    }

    private fun getCustomMetricValuesForAllDays(ctx: Context): Map<String, MutableMap<String, Float>> {
        val arr = parseArrayOrEmpty(prefs(ctx).getString(KEY_CUSTOM_VALUES, "[]"))
        val result = mutableMapOf<String, MutableMap<String, Float>>()
        for (i in 0 until arr.length()) {
            try {
                val v = CustomMetricValue.fromJson(arr.getJSONObject(i))
                result.getOrPut(v.dayKey) { mutableMapOf() }[v.metricId] = v.value
            } catch (_: Exception) {}
        }
        return result
    }

    fun clearAll(ctx: Context) {
        prefs(ctx).edit()
            .putString(KEY_DAILY_LOG,   "[]")
            .putString(KEY_DRIVE_LOG,   "[]")
            .putString(KEY_SUSPEND_LOG, "[]")
            .apply()
    }

    // ─── Quality / Balance Score formula ──────────────────────────────────────

    /**
     * Computes the Balance Score.
     *
     * Base: Drive 4 = balanced origin (score 0). Range −3 … +3.
     * Impact: Sleep offset from 7h. 7h is neutral (0 impact).
     * Formula: (Drive - 4) + ((SleepHours - 7) * 0.2)
     */
    fun computeQuality(driveLevel: Float, suspendHours: Float): Float {
        val driveOffset = driveLevel - 4f
        val sleepOffset = (suspendHours - 7f) * 0.2f
        return driveOffset + sleepOffset
    }

    /** Legacy shim for drive-only computation */
    fun computeQuality(driveLevel: Float): Float = computeQuality(driveLevel, 7f)

    // ── Notes ────────────────────────────────────────────────────────────────

    /** Save a new note entry for today. Returns the saved NoteEntry. */
    fun saveNoteEntry(ctx: Context, text: String): NoteEntry {
        val entry = NoteEntry(
            dayKey      = getCurrentDayKey(ctx),
            timestampMs = System.currentTimeMillis(),
            text        = text,
        )
        val list = getNoteEntries(ctx).toMutableList()
        list.add(entry)
        val arr = JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }
        prefs(ctx).edit().putString(KEY_NOTES_LOG, arr.toString()).apply()
        return entry
    }

    /** Delete a note entry by id. */
    fun deleteNoteEntry(ctx: Context, id: String) {
        val list = getNoteEntries(ctx).filter { it.id != id }
        val arr  = JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }
        prefs(ctx).edit().putString(KEY_NOTES_LOG, arr.toString()).apply()
    }

    /** All note entries across all days, sorted by timestamp. */
    fun getNoteEntries(ctx: Context): List<NoteEntry> {
        val arr = parseArrayOrEmpty(prefs(ctx).getString(KEY_NOTES_LOG, "[]"))
        return (0 until arr.length()).mapNotNull {
            try { NoteEntry.fromJson(arr.getJSONObject(it)) } catch (_: Exception) { null }
        }.sortedBy { it.timestampMs }
    }

    /** Notes for one specific day, sorted by timestamp. */
    fun getNotesForDay(ctx: Context, dayKey: String): List<NoteEntry> =
        getNoteEntries(ctx).filter { it.dayKey == dayKey }

    /** Map of dayKey → text of newest note for pin display on graph. Includes legacy DailyEntry.note. */
    fun getNoteSeries(ctx: Context): Map<String, String> {
        val result = mutableMapOf<String, String>()
        // Legacy notes from DailyEntry.note field
        getDailyEntries(ctx).filter { it.note.isNotBlank() }.forEach { result[it.dayKey] = it.note }
        // New NoteEntry records override legacy for the same day
        getNoteEntries(ctx).groupBy { it.dayKey }.forEach { (day, entries) ->
            result[day] = entries.maxByOrNull { it.timestampMs }?.text.orEmpty()
        }
        return result
    }

    /** Legacy shim — delegates to saveNoteEntry. */
    fun saveNote(ctx: Context, note: String) {
        if (note.isNotBlank()) saveNoteEntry(ctx, note)
    }

    // ── Custom metrics ────────────────────────────────────────────────────────

    fun getCustomMetrics(ctx: Context): List<CustomMetric> {
        val arr = parseArrayOrEmpty(prefs(ctx).getString(KEY_CUSTOM_METRICS, "[]"))
        return (0 until arr.length()).mapNotNull {
            try { CustomMetric.fromJson(arr.getJSONObject(it)) } catch (_: Exception) { null }
        }
    }

    fun saveCustomMetric(ctx: Context, metric: CustomMetric) {
        val list = getCustomMetrics(ctx).toMutableList()
        val idx  = list.indexOfFirst { it.id == metric.id }
        if (idx >= 0) list[idx] = metric else list.add(metric)
        val arr = JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }
        prefs(ctx).edit().putString(KEY_CUSTOM_METRICS, arr.toString()).apply()
    }

    fun deleteCustomMetric(ctx: Context, id: String) {
        val list = getCustomMetrics(ctx).filter { it.id != id }
        val arr  = JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }
        prefs(ctx).edit().putString(KEY_CUSTOM_METRICS, arr.toString()).apply()
    }

    fun deleteCustomMetricValues(ctx: Context, metricId: String) {
        val raw  = parseArrayOrEmpty(prefs(ctx).getString(KEY_CUSTOM_VALUES, "[]"))
        val list = (0 until raw.length()).mapNotNull {
            try { CustomMetricValue.fromJson(raw.getJSONObject(it)) } catch (_: Exception) { null }
        }.filter { it.metricId != metricId }
        val arr = JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }
        prefs(ctx).edit().putString(KEY_CUSTOM_VALUES, arr.toString()).apply()
    }

    fun getCustomMetricValuesForDay(ctx: Context, dayKey: String): Map<String, Float> {
        val arr = parseArrayOrEmpty(prefs(ctx).getString(KEY_CUSTOM_VALUES, "[]"))
        return (0 until arr.length()).mapNotNull {
            try { CustomMetricValue.fromJson(arr.getJSONObject(it)) } catch (_: Exception) { null }
        }.filter { it.dayKey == dayKey }.associate { it.metricId to it.value }
    }

    fun saveCustomMetricValue(ctx: Context, dayKey: String, metricId: String, value: Float) {
        val raw  = parseArrayOrEmpty(prefs(ctx).getString(KEY_CUSTOM_VALUES, "[]"))
        val list = (0 until raw.length()).mapNotNull {
            try { CustomMetricValue.fromJson(raw.getJSONObject(it)) } catch (_: Exception) { null }
        }.toMutableList()
        val idx = list.indexOfFirst { it.dayKey == dayKey && it.metricId == metricId }
        val v   = CustomMetricValue(dayKey, metricId, value)
        if (idx >= 0) list[idx] = v else list.add(v)
        val arr = JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }
        prefs(ctx).edit().putString(KEY_CUSTOM_VALUES, arr.toString()).apply()
    }

    /** All recorded values for one metric (only set days, sorted by date). */
    fun getAllValuesForMetric(ctx: Context, metricId: String): List<Pair<String, Float>> {
        val raw = parseArrayOrEmpty(prefs(ctx).getString(KEY_CUSTOM_VALUES, "[]"))
        return (0 until raw.length()).mapNotNull {
            try { CustomMetricValue.fromJson(raw.getJSONObject(it)) } catch (_: Exception) { null }
        }.filter { it.metricId == metricId && it.value >= 0f }
          .map { it.dayKey to it.value }
          .sortedBy { it.first }
    }

    // ── Pearson correlation ───────────────────────────────────────────────────

    /**
     * Computes Pearson r for two parallel value lists.
     * Returns null if fewer than 3 paired observations.
     */
    fun computePearsonR(xs: List<Float>, ys: List<Float>): Float? {
        val n = minOf(xs.size, ys.size)
        if (n < 3) return null
        val mx = xs.take(n).average().toFloat()
        val my = ys.take(n).average().toFloat()
        var num = 0f; var dx2 = 0f; var dy2 = 0f
        for (i in 0 until n) {
            val dx = xs[i] - mx; val dy = ys[i] - my
            num += dx * dy; dx2 += dx * dx; dy2 += dy * dy
        }
        val denom = kotlin.math.sqrt((dx2 * dy2).toDouble()).toFloat()
        return if (denom < 0.0001f) null else (num / denom).coerceIn(-1f, 1f)
    }

    /** Pearson r between drive levels and a named metric series (aligned by dayKey). */
    fun correlationWithDrive(ctx: Context, metricValues: Map<String, Float>): Float? {
        val drives = getDriveLevelSeries(ctx).associate { it.first to it.second }
        val keys   = drives.keys.intersect(metricValues.keys).sorted()
        if (keys.size < 3) return null
        return computePearsonR(keys.map { drives[it]!! }, keys.map { metricValues[it]!! })
    }

    // ── Quality series + helpers ──────────────────────────────────────────────

    /** Returns (dayKey, balance score) pairs for all daily entries, sorted by date.
     *  Uses autoDriveLevel so manual overrides don't distort the score. */
    fun getQualitySeries(ctx: Context): List<Pair<String, Float>> =
        getDailyEntries(ctx)
            .sortedBy { it.dayKey }
            .map { it.dayKey to computeQuality(it.autoDriveLevel.toFloat(), it.suspendMinutes / 60f) }

    /** Returns (dayKey, suspendHours) pairs for all daily entries, sorted by date. */
    fun getSuspendSeries(ctx: Context): List<Pair<String, Float>> =
        getDailyEntries(ctx)
            .sortedBy { it.dayKey }
            .map { it.dayKey to (it.suspendMinutes / 60f) }

    /** Returns (dayKey, driveLevel) pairs — the manual override if set, else auto avg. */
    fun getDriveLevelSeries(ctx: Context): List<Pair<String, Float>> =
        getDailyEntries(ctx)
            .sortedBy { it.dayKey }
            .map { it.dayKey to it.driveLevel.toFloat() }

    /** Returns (dayKey, autoDriveLevel) — always the auto-computed average, never manual. */
    fun getAutoDriveSeries(ctx: Context): List<Pair<String, Float>> =
        getDailyEntries(ctx)
            .sortedBy { it.dayKey }
            .map { it.dayKey to it.autoDriveLevel.toFloat() }

    /** Get entry for a specific day key. */
    fun getEntryForDayKey(ctx: Context, dayKey: String): DailyEntry? =
        getDailyEntries(ctx).find { it.dayKey == dayKey }

    /** All raw drive entries for a specific day (for intraday chart). */
    fun getIntraDayDriveEntries(ctx: Context, dayKey: String): List<DriveEntry> {
        val (start, end) = getLogicalDayWindow(dayKey, ctx)
        return getDriveEntries(ctx).filter { it.timestampMs in start until end }
    }

    /** Compute balance score for a specific DailyEntry directly.
     *  Uses autoDriveLevel so manual overrides don't affect the score. */
    fun getQualityForEntry(entry: DailyEntry) =
        computeQuality(entry.autoDriveLevel.toFloat(), entry.suspendMinutes / 60f)

    // ── Backup timestamp ─────────────────────────────────────────────────────

    fun getLastBackupMs(ctx: Context): Long =
        prefs(ctx).getLong(KEY_LAST_BACKUP_MS, 0L)

    fun saveLastBackupMs(ctx: Context, ms: Long) {
        prefs(ctx).edit().putLong(KEY_LAST_BACKUP_MS, ms).apply()
    }

    // ── Sleep/Drive insights ─────────────────────────────────────────────────

    data class SleepDriveInsights(
        val avgDriveHighSleep: Float?,   // avg drive when sleep >= 7 h
        val avgDriveLowSleep: Float?,    // avg drive when sleep < 7 h
        val avgSleepAllTime: Float,
        val dayCount: Int,
    )

    fun getSleepDriveInsights(ctx: Context, sinceMs: Long = 0L): SleepDriveInsights {
        val entries = getDailyEntries(ctx).filter { it.lastUpdatedMs >= sinceMs }
        val high    = entries.filter { it.suspendMinutes >= 420 }  // >= 7 h
        val low     = entries.filter { it.suspendMinutes < 420 }
        return SleepDriveInsights(
            avgDriveHighSleep = if (high.isEmpty()) null else high.map { it.driveLevel }.average().toFloat(),
            avgDriveLowSleep  = if (low.isEmpty())  null else low.map { it.driveLevel }.average().toFloat(),
            avgSleepAllTime   = if (entries.isEmpty()) 0f
                                else (entries.sumOf { it.suspendMinutes } / 60f / entries.size),
            dayCount          = entries.size,
        )
    }

    /**
     * Lag-effect analysis: for each day compare drive to sleep N days earlier.
     * Returns a list of (lagDays, avgDriveAfterHighSleep, avgDriveAfterLowSleep).
     */
    fun getLagInsights(ctx: Context, maxLag: Int = 3): List<Triple<Int, Float?, Float?>> {
        val entries = getDailyEntries(ctx).sortedBy { it.dayKey }
        if (entries.size < 2) return emptyList()
        return (1..maxLag).map { lag ->
            val pairs = (lag until entries.size).mapNotNull { i ->
                val srcSleep = entries[i - lag].suspendMinutes
                val tgtDrive = entries[i].driveLevel
                srcSleep to tgtDrive
            }
            val highPairs = pairs.filter { it.first >= 420 }
            val lowPairs  = pairs.filter { it.first < 420 }
            Triple(
                lag,
                if (highPairs.isEmpty()) null else highPairs.map { it.second }.average().toFloat(),
                if (lowPairs.isEmpty())  null else lowPairs.map  { it.second }.average().toFloat(),
            )
        }
    }

    private fun loadDriveLog(ctx: Context): JSONArray =
        parseArrayOrEmpty(prefs(ctx).getString(KEY_DRIVE_LOG, "[]"))

    private fun loadSuspendLog(ctx: Context): JSONArray =
        parseArrayOrEmpty(prefs(ctx).getString(KEY_SUSPEND_LOG, "[]"))

    private fun parseArrayOrEmpty(raw: String?): JSONArray =
        try { JSONArray(raw ?: "[]") } catch (_: Exception) { JSONArray() }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

data class DailyEntry(
    val dayKey: String,
    val driveLevel: Int,         // Final drive value (manual override OR auto avg)
    val autoDriveLevel: Int,     // Always the auto-computed daily average (used for Balance Score)
    val suspendMinutes: Int,
    val lastUpdatedMs: Long,
    val driveOverridden: Boolean = false,
    val note: String = "",       // Legacy field kept for backward compat
) {
    fun toJson(): JSONObject = JSONObject()
        .put("day",              dayKey)
        .put("level",            driveLevel)
        .put("auto_drive",       autoDriveLevel)
        .put("suspend",          suspendMinutes)
        .put("ts",               lastUpdatedMs)
        .put("drive_overridden", driveOverridden)
        .put("note",             note)

    companion object {
        fun fromJson(o: JSONObject) = DailyEntry(
            dayKey          = o.getString("day"),
            driveLevel      = o.getInt("level"),
            autoDriveLevel  = o.optInt("auto_drive", o.getInt("level")), // backward compat
            suspendMinutes  = o.getInt("suspend"),
            lastUpdatedMs   = o.getLong("ts"),
            driveOverridden = o.optBoolean("drive_overridden", false),
            note            = o.optString("note", ""),
        )
    }
}

data class DriveEntry(val timestampMs: Long, val level: Int) {
    fun toJson() = JSONObject().put("ts", timestampMs).put("level", level)
    companion object {
        fun fromJson(o: JSONObject) = DriveEntry(o.getLong("ts"), o.getInt("level"))
    }
}

data class SuspendEntry(val timestampMs: Long, val minutes: Int) {
    fun toJson() = JSONObject().put("ts", timestampMs).put("minutes", minutes)
    companion object {
        fun fromJson(o: JSONObject) = SuspendEntry(o.getLong("ts"), o.getInt("minutes"))
    }
}

data class NoteEntry(
    val id:          String = UUID.randomUUID().toString(),
    val dayKey:      String,
    val timestampMs: Long,
    val text:        String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id",   id)
        .put("day",  dayKey)
        .put("ts",   timestampMs)
        .put("text", text)

    companion object {
        fun fromJson(o: JSONObject) = NoteEntry(
            id          = o.optString("id", UUID.randomUUID().toString()),
            dayKey      = o.getString("day"),
            timestampMs = o.getLong("ts"),
            text        = o.getString("text"),
        )
    }
}
