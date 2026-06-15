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

    fun getSuspendWidgetEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SUSPEND_WIDGET_ENABLED, true)

    fun setSuspendWidgetEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_SUSPEND_WIDGET_ENABLED, enabled).apply()

    fun getCurrentDayKey(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    fun dayKeyToMs(dayKey: String): Long =
        try { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dayKey)?.time ?: 0L }
        catch (_: Exception) { 0L }

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
        val dayKey       = getCurrentDayKey()
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
        // Only propagate drive level to the "current level" indicator when NOT a manual override
        if (!driveOverridden) editor.putInt(KEY_LEVEL, driveLevel)
        editor.putInt(KEY_SUSPEND, suspendMinutes)
        editor.putString(KEY_DAILY_LOG, arr.toString())
        editor.apply()
    }

    fun getTodaysDailyEntry(ctx: Context): DailyEntry? =
        getDailyEntries(ctx).find { it.dayKey == getCurrentDayKey() }

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
            .apply()
    }

    fun saveSuspend(ctx: Context, minutes: Int) {
        val entry = SuspendEntry(System.currentTimeMillis(), minutes)
        val arr   = loadSuspendLog(ctx)
        arr.put(entry.toJson())
        prefs(ctx).edit()
            .putInt(KEY_SUSPEND, minutes)
            .putString(KEY_SUSPEND_LOG, arr.toString())
            .apply()
    }

    fun getDriveEntries(ctx: Context): List<DriveEntry> {
        val arr = loadDriveLog(ctx)
        return (0 until arr.length()).map { DriveEntry.fromJson(arr.getJSONObject(it)) }
    }

    /**
     * Returns the average raw Drive level for [dayKey] (midnight–midnight),
     * or null if no raw entries exist for that day.
     */
    fun getDailyDriveAverage(ctx: Context, dayKey: String): Float? {
        val startMs = dayKeyToMs(dayKey)
        val endMs   = startMs + 86_400_000L
        val entries = getDriveEntries(ctx).filter { it.timestampMs in startMs until endMs }
        return if (entries.isEmpty()) null else entries.map { it.level }.average().toFloat()
    }

    /**
     * Called after any raw [DriveEntry] is saved.
     * Recomputes today's average and writes it to today's [DailyEntry],
     * UNLESS the user has manually overridden the drive level.
     */
    fun autoUpdateDailyDrive(ctx: Context) {
        val dayKey   = getCurrentDayKey()
        val avg      = getDailyDriveAverage(ctx, dayKey) ?: return
        val existing = getTodaysDailyEntry(ctx)
        if (existing != null && existing.driveOverridden) return
        val level   = avg.roundToInt().coerceIn(1, 7)
        // BUG FIX: don't carry over sleep from a previous day when creating a brand-new entry
        val suspend = existing?.suspendMinutes ?: 0
        saveDailyEntry(ctx, level, suspend, driveOverridden = false, autoDriveLevel = level)
    }

    fun getSuspendEntries(ctx: Context): List<SuspendEntry> {
        val arr = loadSuspendLog(ctx)
        return (0 until arr.length()).map { SuspendEntry.fromJson(arr.getJSONObject(it)) }
    }

    fun exportJson(ctx: Context): String {
        val root = JSONObject()
        root.put("daily_log",   parseArrayOrEmpty(prefs(ctx).getString(KEY_DAILY_LOG, "[]")))
        root.put("drive_log",   loadDriveLog(ctx))
        root.put("suspend_log", loadSuspendLog(ctx))
        root.put("notes_log",   parseArrayOrEmpty(prefs(ctx).getString(KEY_NOTES_LOG, "[]")))
        return root.toString(2)
    }

    fun importJson(ctx: Context, json: String) {
        val root = JSONObject(json)
        val existingDaily = getDailyEntries(ctx).associateBy { it.dayKey }.toMutableMap()
        val importDailyArr = root.optJSONArray("daily_log") ?: JSONArray()
        for (i in 0 until importDailyArr.length()) {
            try {
                val e = DailyEntry.fromJson(importDailyArr.getJSONObject(i))
                existingDaily.putIfAbsent(e.dayKey, e)
            } catch (_: Exception) {}
        }
        val existingDrive   = getDriveEntries(ctx).associateBy { it.timestampMs }.toMutableMap()
        val existingSuspend = getSuspendEntries(ctx).associateBy { it.timestampMs }.toMutableMap()
        val importDrive   = root.optJSONArray("drive_log") ?: JSONArray()
        val importSuspend = root.optJSONArray("suspend_log") ?: JSONArray()
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
        val mergedDaily = JSONArray().also { a ->
            existingDaily.values.sortedBy { it.dayKey }.forEach { a.put(it.toJson()) }
        }
        val mergedDrive = JSONArray().also { a ->
            existingDrive.values.sortedBy { it.timestampMs }.forEach { a.put(it.toJson()) }
        }
        val mergedSuspend = JSONArray().also { a ->
            existingSuspend.values.sortedBy { it.timestampMs }.forEach { a.put(it.toJson()) }
        }
        // Merge notes
        val existingNotes  = getNoteEntries(ctx).associateBy { it.id }.toMutableMap()
        val importNotesArr = root.optJSONArray("notes_log") ?: JSONArray()
        for (i in 0 until importNotesArr.length()) {
            try {
                val e = NoteEntry.fromJson(importNotesArr.getJSONObject(i))
                existingNotes.putIfAbsent(e.id, e)
            } catch (_: Exception) {}
        }
        val mergedNotes = JSONArray().also { a ->
            existingNotes.values.sortedBy { it.timestampMs }.forEach { a.put(it.toJson()) }
        }
        prefs(ctx).edit()
            .putString(KEY_DAILY_LOG,   mergedDaily.toString())
            .putString(KEY_DRIVE_LOG,   mergedDrive.toString())
            .putString(KEY_SUSPEND_LOG, mergedSuspend.toString())
            .putString(KEY_NOTES_LOG,   mergedNotes.toString())
            .apply()
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
     * Computes the Balance Score from drive only.
     *
     * Drive 4 = balanced origin (score 0).
     * Each step away from 4 adds ±1, so the range is −3 … +3.
     * Positive = elevated/manic side; negative = low/depressed side.
     *
     * Sleep is intentionally excluded — it is used only for trend insights,
     * not for the balance score itself.
     */
    fun computeQuality(driveLevel: Float): Float = driveLevel - 4f

    /** Overload kept for callers that still pass suspendHours — ignored. */
    @Suppress("UNUSED_PARAMETER")
    fun computeQuality(driveLevel: Float, suspendHours: Float): Float = computeQuality(driveLevel)

    // ── Notes ────────────────────────────────────────────────────────────────

    /** Save a new note entry for today. Returns the saved NoteEntry. */
    fun saveNoteEntry(ctx: Context, text: String): NoteEntry {
        val entry = NoteEntry(
            dayKey      = getCurrentDayKey(),
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
            .map { it.dayKey to computeQuality(it.autoDriveLevel.toFloat()) }

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
        val startMs = dayKeyToMs(dayKey)
        val endMs   = startMs + 86_400_000L
        return getDriveEntries(ctx).filter { it.timestampMs in startMs until endMs }
    }

    /** Compute balance score for a specific DailyEntry directly.
     *  Uses autoDriveLevel so manual overrides don't affect the score. */
    fun getQualityForEntry(entry: DailyEntry) =
        computeQuality(entry.autoDriveLevel.toFloat())

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
