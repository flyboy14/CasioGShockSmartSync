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
            .put("hourly", org.json.JSONArray(d.hourlySteps.map { it ?: 0 }))
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
            val hourly = o.getJSONArray("hourly").let { a -> (0 until a.length()).map { a.getInt(it) } }
            val data = StepCounterData(
                timestamp = ts,
                dayOfWeek = o.optInt("dow").takeIf { it >= 0 },
                month = o.optInt("month").takeIf { it >= 0 },
                dayOfMonth = o.optInt("dom").takeIf { it >= 0 },
                hourlySteps = hourly,
                dailyHistory = emptyList(),
                currentDaySteps = o.optInt("steps").takeIf { it >= 0 },
                hourlyByHour = hourly,
            )
            if (cache == null) cache = at to data
            data
        }.getOrNull()

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
