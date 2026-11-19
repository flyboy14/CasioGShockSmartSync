package org.avmedia.gshockGoogleSync.h5600

import android.content.Context
import org.avmedia.gshockapi.model.Alarm
import org.avmedia.gshockapi.model.SoundMode
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

/**
 * Phone-side alarm list for the DW-H5600: any number of alarms, each with its own days and
 * vibe-only / sound choice. The watch only has [SLOTS] usable alarm slots, so the phone keeps the
 * alarms that ring next in those slots and reloads them as alarms ring ([plan]).
 *
 * Watch slots ring every day, so an alarm is only loaded when its next ring is less than 24 h away
 * (otherwise it would also ring today at the same time).
 */
object AlarmPool {
    const val SLOTS = 4

    /** Snooze intervals on the alarm editor's slider (minutes), shown once snooze is switched on. */
    val SNOOZE_CHOICES = listOf(5, 10, 15, 20, 30)
    const val DEFAULT_SNOOZE_MIN = 5
    /** Interval given to alarms that had the watch's own snooze on before the phone-side snooze. */
    const val MIGRATED_SNOOZE_MIN = 5
    private const val RING_MIN = 1L
    /** End of a snooze that runs until stopped and has no later ring to end it (one-time alarm). */
    private val FOREVER: LocalDateTime = LocalDateTime.MAX

    /*
     * Snooze is done by the phone, not the watch: the watch's own snooze is off on every slot (it
     * stops after 7 repeats, and time syncs / slot writes cancel it). After an alarm with snooze
     * rings, the phone moves its watch slot to the next repeat time — ring + X, ring + 2X, … — a
     * minute after each ring, until the snooze is stopped from the phone (card or notification),
     * the alarm is switched off or edited, or its next regular ring starts a new round.
     */

    /**
     * The ring an alarm is on now: the last one that has started. A smart alarm starts at the
     * beginning of its window (it may wake you any time in it), so its snooze can be stopped early.
     */
    private fun currentRing(a: PhoneAlarm, now: LocalDateTime): LocalDateTime? =
        rings(a, now).lastOrNull { !start(a, it).isAfter(now) }

    private fun start(a: PhoneAlarm, ring: LocalDateTime) = ring.minusMinutes(a.smartWindowMin.toLong())

    /** True if [ring] snoozes: snooze on, the ring came after the alarm was last set, not stopped. */
    private fun snoozesFrom(a: PhoneAlarm, ring: LocalDateTime): Boolean =
        a.snoozes && ring != a.dismissedRing && (a.since == null || ring.isAfter(a.since))

    /**
     * End of an alarm's hold after [ring] (keeps its slot and its sound mode): with snooze, until
     * stopped — at the latest when its next ring starts; otherwise the ring minute itself.
     */
    fun holdEnd(a: PhoneAlarm, ring: LocalDateTime): LocalDateTime =
        if (snoozesFrom(a, ring)) rings(a, ring).firstOrNull { it.isAfter(ring) }?.let { start(a, it) } ?: FOREVER
        else ring.plusMinutes(RING_MIN)

    private fun inHold(a: PhoneAlarm, now: LocalDateTime): Boolean =
        currentRing(a, now)?.let { holdEnd(a, it).isAfter(now) } == true

    /** The first repeat of [ring] (ring + k x interval, k ≥ 1) after [now]. */
    private fun nextRepeat(a: PhoneAlarm, ring: LocalDateTime, now: LocalDateTime): LocalDateTime {
        val x = a.snoozeMin.toLong()
        val k = maxOf(1L, Math.floorDiv(Duration.between(ring, now).toMinutes(), x) + 1)
        return ring.plusMinutes(k * x)
    }

    /** Alarms snoozing now, each with its next repeat time. */
    fun snoozing(pool: List<PhoneAlarm>, now: LocalDateTime): List<Pair<PhoneAlarm, LocalDateTime>> =
        pool.mapNotNull { a ->
            currentRing(a, now)?.takeIf { snoozesFrom(a, it) && holdEnd(a, it).isAfter(now) }?.let { a to nextRepeat(a, it, now) }
        }

    /**
     * True during the minute an alarm or a snooze repeat rings (a smart alarm only at its target
     * time). Time syncs wait for it to pass: a write to the watch may silence a ringing alarm.
     */
    fun ringing(pool: List<PhoneAlarm>, now: LocalDateTime): Boolean = pool.any { a ->
        val ring = currentRing(a, now)?.takeIf { !it.isAfter(now) } ?: return@any false
        val last = if (snoozesFrom(a, ring) && holdEnd(a, ring).isAfter(now)) {
            val x = a.snoozeMin.toLong()
            ring.plusMinutes(Duration.between(ring, now).toMinutes() / x * x)
        } else ring
        now.isBefore(last.plusMinutes(RING_MIN))
    }

    /** Stops alarm [id]'s snooze (marks its current ring as stopped). */
    fun dismiss(pool: List<PhoneAlarm>, id: String, now: LocalDateTime): List<PhoneAlarm> =
        pool.map { a -> if (a.id == id) currentRing(a, now)?.let { a.copy(dismissedRing = it) } ?: a else a }

    /**
     * [edited] as saved from the editor or a switch: a change to when it rings (time, days, once,
     * on/off) or turning snooze on sets [PhoneAlarm.since], so an earlier ring never starts
     * snoozing (e.g. snooze switched on in the evening for an alarm that rang this morning).
     * A new label or interval leaves a running snooze alone.
     */
    fun touch(old: PhoneAlarm?, edited: PhoneAlarm, now: LocalDateTime): PhoneAlarm {
        val changed = old == null || old.hour != edited.hour || old.minute != edited.minute || old.days != edited.days ||
            old.once != edited.once || old.enabled != edited.enabled || old.snoozes != edited.snoozes
        return if (changed) edited.copy(since = now) else edited
    }

    data class PhoneAlarm(
        val id: String = UUID.randomUUID().toString(),
        val hour: Int,
        val minute: Int,
        val enabled: Boolean = true,
        val days: Set<DayOfWeek> = AlarmDayRules.ALL_DAYS,
        val vibeOnly: Boolean = false,
        val label: String = "", // phone-side only; the watch has no alarm names
        /** Phone-side snooze: 0 = off, else rings again every this many minutes until stopped. */
        val snoozeMin: Int = 0,
        /** 0 = normal alarm; 15/30/45/60 = smart alarm waking up to that many minutes earlier. */
        val smartWindowMin: Int = 0,
        /** One-time alarm: rings once on [onceDate] (ignores [days]), then switches itself off. */
        val once: Boolean = false,
        /** The day a one-time alarm is set for (see [arm]); null until armed. */
        val onceDate: LocalDate? = null,
        /** Ring whose snooze was stopped from the phone ([dismiss]): its hold ends right away. */
        val dismissedRing: LocalDateTime? = null,
        /** When it was last set ([touch]): rings before this never snooze. Null = no limit. */
        val since: LocalDateTime? = null,
    ) {
        val isSmart: Boolean get() = smartWindowMin > 0
        val snoozes: Boolean get() = snoozeMin > 0
        val minuteOfDay: Int get() = hour * 60 + minute
    }

    // ---- storage ----
    private const val PREFS = org.avmedia.gshockGoogleSync.h5600.H5600Prefs.NAME
    private const val KEY_POOL = "alarm_pool_json"
    private const val KEY_LOADED = "alarm_pool_loaded_ids"
    private const val KEY_SMART_LOADED = "alarm_pool_smart_loaded_id"

    fun loadedSmartId(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SMART_LOADED, null)

    fun saveLoadedSmartId(context: Context, id: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SMART_LOADED, id).apply()
    }

    fun load(context: Context): List<PhoneAlarm>? {
        var migrated = false
        val now = LocalDateTime.now()
        val pool = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_POOL, null)?.let { json ->
            runCatching {
                val arr = JSONArray(json)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    PhoneAlarm(
                        id = o.getString("id"),
                        hour = o.getInt("hour"),
                        minute = o.getInt("minute"),
                        enabled = o.optBoolean("enabled", true),
                        days = o.optString("days", "").split(',').mapNotNull { it.toIntOrNull()?.let(DayOfWeek::of) }
                            .toSet().ifEmpty { AlarmDayRules.ALL_DAYS },
                        vibeOnly = o.optBoolean("vibeOnly", false),
                        label = o.optString("label", ""),
                        // Before the phone-side snooze: the watch's snooze on/off.
                        snoozeMin = if (o.has("snoozeMin")) o.getInt("snoozeMin")
                            else if (o.optBoolean("snooze", true)) MIGRATED_SNOOZE_MIN else 0,
                        smartWindowMin = o.optInt("smartWindowMin", 0),
                        once = o.optBoolean("once", false),
                        onceDate = o.optString("onceDate", "").takeIf { it.isNotEmpty() }
                            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                        dismissedRing = o.optString("dismissedRing", "").takeIf { it.isNotEmpty() }
                            ?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() },
                        // Missing (saved before the phone-side snooze): from now, so rings already
                        // past do not start snoozing on the update.
                        since = o.optString("since", "").takeIf { it.isNotEmpty() }
                            ?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
                            ?: now.also { migrated = true },
                    )
                }
            }.getOrNull()
        }
        if (migrated && pool != null) save(context, pool)
        return pool
    }

    fun save(context: Context, alarms: List<PhoneAlarm>) {
        val arr = JSONArray()
        alarms.forEach { a -> // kept in the user's order (hold and drag on the Alarms tab)
            arr.put(
                JSONObject()
                    .put("id", a.id).put("hour", a.hour).put("minute", a.minute).put("enabled", a.enabled)
                    .put("days", a.days.sorted().joinToString(",") { it.value.toString() })
                    .put("vibeOnly", a.vibeOnly)
                    .put("label", a.label)
                    .put("snoozeMin", a.snoozeMin)
                    .put("smartWindowMin", a.smartWindowMin)
                    .put("once", a.once)
                    .put("onceDate", a.onceDate?.toString() ?: "")
                    .put("dismissedRing", a.dismissedRing?.toString() ?: "")
                    .put("since", a.since?.toString() ?: ""),
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_POOL, arr.toString()).apply()
    }

    fun loadedIds(context: Context): List<String?> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LOADED, null)
            ?.split(',')?.map { it.ifEmpty { null } } ?: List(SLOTS) { null }

    fun saveLoadedIds(context: Context, ids: List<String?>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LOADED, ids.joinToString(",") { it ?: "" }).apply()
    }

    /** First use: take over the watch's current alarms, with any per-alarm settings made earlier. */
    fun importFrom(watch: List<Alarm>, rules: Map<Int, Set<DayOfWeek>>, vibe: Set<Int>, now: LocalDateTime): List<PhoneAlarm> =
        watch.take(SLOTS).map { a ->
            val m = a.hour * 60 + a.minute
            PhoneAlarm(hour = a.hour, minute = a.minute, enabled = a.enabled,
                days = rules[m] ?: AlarmDayRules.ALL_DAYS, vibeOnly = m in vibe,
                snoozeMin = if (a.snooze == true) MIGRATED_SNOOZE_MIN else 0, since = now)
        }

    // ---- pure planning ----

    /** Ring times of an alarm from 8 days back to 8 days ahead (a one-time alarm: its one ring). */
    private fun rings(a: PhoneAlarm, now: LocalDateTime): List<LocalDateTime> =
        if (!a.enabled) emptyList()
        else if (a.once) listOfNotNull(a.onceDate?.atTime(a.hour, a.minute))
        else (-8L..8L).map { now.toLocalDate().plusDays(it) }
            .filter { it.dayOfWeek in a.days }
            .map { it.atTime(a.hour, a.minute) }

    /**
     * Sets a one-time alarm for the next time its hh:mm comes round (today if still ahead,
     * otherwise tomorrow). Called when it is created, edited or switched back on. No-op for others.
     */
    fun arm(a: PhoneAlarm, now: LocalDateTime): PhoneAlarm {
        if (!a.once) return if (a.onceDate == null) a else a.copy(onceDate = null)
        val today = now.toLocalDate().atTime(a.hour, a.minute)
        return a.copy(onceDate = (if (today.isAfter(now)) today else today.plusDays(1)).toLocalDate())
    }

    /**
     * One-time alarms whose ring is over (rang, and its snooze, if any, was stopped) are switched
     * off. Returns the list unchanged if none expired.
     */
    fun expireOnce(pool: List<PhoneAlarm>, now: LocalDateTime): List<PhoneAlarm> =
        pool.map { a ->
            val ring = a.onceDate?.atTime(a.hour, a.minute)
            if (a.once && a.enabled && ring != null && !holdEnd(a, ring).isAfter(now)) a.copy(enabled = false) else a
        }

    fun nextRing(a: PhoneAlarm, now: LocalDateTime): LocalDateTime? = rings(a, now).firstOrNull { it.isAfter(now) }

    /**
     * Which alarm goes in which watch slot. Keeps alarms in the slot they already occupy, keeps
     * alarms that are ringing or snoozing (a snoozing smart alarm too: its repeats use a normal
     * slot), then fills free slots with the alarms due soonest within 24 h.
     */
    fun plan(allAlarms: List<PhoneAlarm>, current: List<String?>, now: LocalDateTime): List<String?> {
        val held = allAlarms.filter { a -> inHold(a, now) && (!a.isSmart || snoozing(listOf(a), now).isNotEmpty()) }.map { it.id }
        val dueSoon = allAlarms.filterNot { it.isSmart } // smart alarms use the watch's single smart slot
            .mapNotNull { a -> nextRing(a, now)?.let { a to it } }
            .filter { (_, t) -> t.isBefore(now.plusHours(24)) }
            .sortedBy { it.second }.map { it.first.id }
        val wanted = (held + dueSoon).distinct().take(SLOTS)

        val slots = MutableList<String?>(SLOTS) { i -> current.getOrNull(i)?.takeIf { it in wanted } }
        wanted.filter { it !in slots }.forEach { id ->
            val free = slots.indexOfFirst { it == null }
            if (free >= 0) slots[free] = id
        }
        return slots
    }

    /**
     * Time a loaded alarm's watch slot is set to: its next snooze repeat while snoozing (unless its
     * regular ring comes first), otherwise its own time.
     */
    fun slotTime(a: PhoneAlarm, now: LocalDateTime): LocalTime {
        val repeat = currentRing(a, now)?.takeIf { snoozesFrom(a, it) && holdEnd(a, it).isAfter(now) }?.let { nextRepeat(a, it, now) }
        val regular = if (a.isSmart) null else nextRing(a, now)
        return listOfNotNull(repeat, regular).minOrNull()?.toLocalTime() ?: LocalTime.of(a.hour, a.minute)
    }

    /**
     * Which alarm goes into the watch's single smart-alarm slot: the next smart alarm due within
     * 24 h whose snooze was not already stopped in its window (none -> slot off).
     */
    fun planSmart(all: List<PhoneAlarm>, now: LocalDateTime): String? =
        all.filter { it.isSmart }
            .mapNotNull { a -> nextRing(a, now)?.takeIf { it != a.dismissedRing }?.let { a to it } }
            .filter { (_, t) -> t.isBefore(now.plusHours(24)) }
            .minByOrNull { it.second }?.first?.id

    /**
     * Next time the slots must be re-planned: a minute after every ring and every snooze repeat
     * (the slot then gets the next repeat; the ring itself is over by then, so the write does not
     * cut it short — hence no 1-min interval), and the start of each smart window (its snooze can
     * be stopped from then on).
     */
    fun nextBoundary(pool: List<PhoneAlarm>, now: LocalDateTime): LocalDateTime? =
        (pool.flatMap { a -> rings(a, now).flatMap { listOfNotNull(it.plusMinutes(RING_MIN), if (a.isSmart) start(a, it) else null) } } +
            snoozing(pool, now).map { (_, next) -> next.plusMinutes(RING_MIN) })
            .filter { it.isAfter(now) }
            .minOrNull()

    /** True if the watch already has what [wanted] asks for, in the fields the app manages. */
    fun sameOnWatch(wanted: List<Alarm>, watch: List<Alarm>): Boolean =
        wanted.size == watch.size && wanted.zip(watch).all { (x, w) ->
            x.enabled == w.enabled && (!x.enabled || (x.hour == w.hour && x.minute == w.minute &&
                (x.snooze == null || w.snooze == null || x.snooze == w.snooze)))
        }

    /**
     * Watch alarm list for a plan: loaded slots enabled at their [slotTime], with the watch's own
     * snooze off (the phone snoozes); other slots disabled.
     */
    fun toWatchAlarms(plan: List<String?>, pool: List<PhoneAlarm>, watch: List<Alarm>, now: LocalDateTime): List<Alarm> {
        val byId = pool.associateBy { it.id }
        return watch.mapIndexed { i, w ->
            if (i >= SLOTS) w // slots beyond the usable four are left as they are
            else byId[plan.getOrNull(i)]?.let { a ->
                val t = slotTime(a, now)
                w.copy(hour = t.hour, minute = t.minute, enabled = true, oneTime = false, snooze = false)
            } ?: w.copy(enabled = false)
        }
    }

    /** Minutes before an alarm (before a smart alarm's window) that its sound mode is set. */
    const val PRE_ALARM_MIN = 5L

    /**
     * When an alarm "borrows" the watch's sound mode for one ring: from [PRE_ALARM_MIN] minutes
     * before it can ring (before its smart window) until its hold ends (1 min, or until its snooze
     * is stopped from the phone).
     */
    fun soundWindow(a: PhoneAlarm, ring: LocalDateTime): Pair<LocalDateTime, LocalDateTime> =
        ring.minusMinutes(a.smartWindowMin + PRE_ALARM_MIN) to holdEnd(a, ring)

    /** The mode an alarm needs, given the user's own [base] mode. */
    fun alarmMode(a: PhoneAlarm, base: SoundMode): SoundMode = when {
        a.vibeOnly -> SoundMode.VIBE_ONLY
        base == SoundMode.ALL_ON || base == SoundMode.SOUND_ONLY || base == SoundMode.CUSTOM -> base // already rings
        else -> SoundMode.CUSTOM // base is vibe only / off: the alarm needs its sound
    }

    /**
     * The sound mode the watch should have now: the user's own [base] mode, except inside an
     * alarm's [soundWindow] (the alarm whose window started last wins when windows overlap).
     */
    fun desiredSoundMode(pool: List<PhoneAlarm>, now: LocalDateTime, base: SoundMode): SoundMode =
        pool.flatMap { a -> rings(a, now).map { a to soundWindow(a, it) } }
            .filter { (_, w) -> !w.first.isAfter(now) && w.second.isAfter(now) }
            .maxByOrNull { (_, w) -> w.first }
            ?.let { (a, _) -> alarmMode(a, base) }
            ?: base

    /**
     * The user's mode at [now]: [userBase], or ALL_OFF inside the night window [night] (minutes of
     * the day, start to end, may cross midnight) when "Do not disturb during night time" is on.
     * Alarms still borrow the watch inside the night (see [desiredSoundMode]).
     */
    fun baseAt(userBase: SoundMode, night: Pair<Int, Int>?, now: LocalDateTime): SoundMode {
        if (night == null || night.first == night.second) return userBase
        val m = now.hour * 60 + now.minute
        val (start, end) = night
        val inNight = if (start < end) m in start until end else m >= start || m < end
        return if (inNight) SoundMode.ALL_OFF else userBase
    }

    /** Next start or end of the night window (scheduler wake-up); null when there is none. */
    fun nextNightBoundary(night: Pair<Int, Int>?, now: LocalDateTime): LocalDateTime? =
        night?.toList()?.map { min ->
            val t = now.toLocalDate().atStartOfDay().plusMinutes(min.toLong())
            if (t.isAfter(now)) t else t.plusDays(1)
        }?.minOrNull()

    /** Start and end of every alarm's [soundWindow] ahead of [now] (scheduler wake-ups). */
    fun nextSoundBoundary(pool: List<PhoneAlarm>, now: LocalDateTime): LocalDateTime? =
        pool.flatMap { a -> rings(a, now).flatMap { soundWindow(a, it).toList() } }
            .filter { it.isAfter(now) && it != FOREVER }
            .minOrNull()
}
