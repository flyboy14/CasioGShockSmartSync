package org.avmedia.gshockGoogleSync.h5600

import org.avmedia.gshockapi.io.H5600LifeLog
import org.avmedia.gshockapi.model.StepCounterData
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Maps a DW-H5600 LifeLog result onto the Health tab's StepCounterData, with a short-lived cache. */
object H5600Steps {
    private const val CACHE_MS = 15 * 60_000L

    @Volatile
    private var cache: Pair<Long, StepCounterData>? = null

    /** When the cached data was synced from the watch (epoch ms), or null. */
    fun syncedAtMillis(): Long? = cache?.first

    fun cached(now: Long = System.currentTimeMillis()): StepCounterData? =
        cache?.takeIf { now - it.first < CACHE_MS }?.second

    /** Forces the next fetch to run a fresh LifeLog sync (pull-to-refresh). */
    fun invalidate() {
        cache = null
    }

    fun store(data: StepCounterData, now: Long = System.currentTimeMillis(), context: android.content.Context? = null) {
        cache = now to data
        context?.let { persist(it, now, data) }
    }

    // ---- Last sync kept on the phone: shown at once after a restart, while a new sync runs ----

    private const val KEY_LAST = "h5600_last_steps"

    private fun persist(context: android.content.Context, at: Long, d: StepCounterData) {
        val o = org.json.JSONObject()
            .put("at", at)
            .put("ts", d.timestamp?.toString() ?: "")
            .put("steps", d.currentDaySteps ?: -1)
            .put("dow", d.dayOfWeek ?: -1).put("month", d.month ?: -1).put("dom", d.dayOfMonth ?: -1)
            .put("hourly", org.json.JSONArray(d.hourlySteps.map { it ?: -1 })) // -1 = unknown hour
        H5600Prefs.get(context).edit().putString(KEY_LAST, o.toString()).apply()
    }

    /**
     * The last synced steps if they are from today (local date), else null. Also primes the
     * in-memory cache with them, so "Synced at" shows the real sync time.
     */
    fun loadStored(context: android.content.Context, zone: ZoneId = ZoneId.systemDefault()): StepCounterData? =
        runCatching {
            val o = org.json.JSONObject(H5600Prefs.get(context).getString(KEY_LAST, null) ?: return null)
            val at = o.getLong("at")
            val ts = o.optString("ts").takeIf { it.isNotEmpty() }?.let(LocalDateTime::parse) ?: return null
            if (ts.toLocalDate() != java.time.LocalDate.now(zone)) return null // yesterday's steps are not today's
            val hourly = o.getJSONArray("hourly").let { a -> (0 until a.length()).map { a.getInt(it).takeIf { v -> v >= 0 } } }
            val data = StepCounterData(
                timestamp = ts,
                dayOfWeek = o.optInt("dow").takeIf { it >= 0 },
                month = o.optInt("month").takeIf { it >= 0 },
                dayOfMonth = o.optInt("dom").takeIf { it >= 0 },
                hourlySteps = hourly,
                dailyHistory = loadMap(context, KEY_DAYS).let { days ->
                    (HISTORY_DAYS downTo 1).map { days[ts.toLocalDate().minusDays(it.toLong()).toString()] }
                },
                currentDaySteps = o.optInt("steps").takeIf { it >= 0 },
                hourlyByHour = hourly,
            )
            if (cache == null) cache = at to data
            data
        }.getOrNull()

    // ---- History kept on the phone ----
    // The watch only sends what the phone has not fetched yet (a sync after a sync carries just the
    // newest 5-minute slots), so slots and daily totals are stored here and merged, keyed by time /
    // date (a re-sent slot or day replaces itself, never counts twice).

    private const val KEY_SLOTS = "h5600_step_slots" // {"<epoch minute UTC>": steps}
    private const val KEY_DAYS = "h5600_step_days"   // {"yyyy-MM-dd": steps}
    private const val KEEP_DAYS = 8L
    const val HISTORY_DAYS = 6

    private fun loadMap(context: android.content.Context, key: String): MutableMap<String, Int> =
        runCatching {
            val o = org.json.JSONObject(H5600Prefs.get(context).getString(key, null) ?: "{}")
            o.keys().asSequence().associateWith { o.getInt(it) }.toMutableMap()
        }.getOrDefault(mutableMapOf())

    private fun saveMap(context: android.content.Context, key: String, map: Map<String, Int>) =
        H5600Prefs.get(context).edit().putString(key, org.json.JSONObject(map as Map<*, *>).toString()).apply()

    /** Stores this sync's slots and days, then builds today's data with hourly bars and past days from the store. */
    fun merge(context: android.content.Context, r: H5600LifeLog.Result, zone: ZoneId): StepCounterData {
        val base = toStepCounterData(r, zone)
        val today = base.timestamp?.toLocalDate() ?: java.time.LocalDate.now(zone)
        val cutoff = r.headerTimeUtc.minusDays(KEEP_DAYS).toEpochSecond(ZoneOffset.UTC) / 60

        val slots = loadMap(context, KEY_SLOTS)
        r.slots.forEach { slots[(it.timeUtc.toEpochSecond(ZoneOffset.UTC) / 60).toString()] = it.steps }
        slots.keys.removeAll { (it.toLongOrNull() ?: 0) < cutoff }
        saveMap(context, KEY_SLOTS, slots)

        val days = loadMap(context, KEY_DAYS)
        r.days.forEach { days[it.date.toString()] = it.steps }
        r.headerSteps.takeIf { it >= 0 }?.let { days[today.toString()] = it }
        days.keys.removeAll { runCatching { java.time.LocalDate.parse(it) }.getOrNull()?.isBefore(today.minusDays(60)) ?: true }
        saveMap(context, KEY_DAYS, days)

        // Hours without any stored slot are unknown (null), not 0.
        val perHour = arrayOfNulls<Int>(24)
        slots.forEach { (k, v) ->
            val t = toLocal(LocalDateTime.ofEpochSecond(k.toLong() * 60, 0, ZoneOffset.UTC), zone)
            if (t.toLocalDate() == today) perHour[t.hour] = (perHour[t.hour] ?: 0) + v
        }
        val hourly = (0..(base.timestamp?.hour ?: 23)).map { perHour[it] }
        val history = (HISTORY_DAYS downTo 1).map { days[today.minusDays(it.toLong()).toString()] } // null = no data
        timber.log.Timber.i("H5600 steps merged: ${slots.size} stored slots, days=$days")
        return base.copy(hourlySteps = hourly, hourlyByHour = hourly, dailyHistory = history)
    }

    private fun toLocal(utc: LocalDateTime, zone: ZoneId): LocalDateTime =
        utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(zone).toLocalDateTime()

    /**
     * Pure. Today's total = the LifeLog header steps (matched the official app in capture -4).
     * hourlySteps = today's 5-minute slots summed per local hour, midnight up to the header hour.
     */
    fun toStepCounterData(r: H5600LifeLog.Result, zone: ZoneId): StepCounterData {
        val headerLocal = toLocal(r.headerTimeUtc, zone)
        val today = headerLocal.toLocalDate()
        val perHour = IntArray(24)
        r.slots.forEach { slot ->
            val t = toLocal(slot.timeUtc, zone)
            if (t.toLocalDate() == today) perHour[t.hour] += slot.steps
        }
        val hourly = (0..headerLocal.hour).map { perHour[it] }
        timber.log.Timber.i("H5600 steps: header ${r.headerSteps} at $headerLocal, ${r.slots.size} slots, hourly=$hourly")
        return StepCounterData(
            timestamp = headerLocal,
            dayOfWeek = headerLocal.dayOfWeek.value,
            month = headerLocal.monthValue,
            dayOfMonth = headerLocal.dayOfMonth,
            hourlySteps = hourly,
            dailyHistory = emptyList(), // daily-summary frame (0x63) not decoded yet
            currentDaySteps = r.headerSteps,
            hourlyByHour = hourly,
        )
    }
}
