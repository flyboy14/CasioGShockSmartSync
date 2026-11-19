package org.avmedia.gshockGoogleSync

import org.avmedia.gshockGoogleSync.h5600.AlarmPool
import org.avmedia.gshockGoogleSync.h5600.AlarmPool.PhoneAlarm
import org.avmedia.gshockapi.model.Alarm
import org.avmedia.gshockapi.model.SoundMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDateTime

class AlarmPoolTest {
    // 2026-11-16 = Monday, 20 = Friday, 21 = Saturday.
    private fun at(day: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, day, h, m)
    private val weekdays = setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY)

    private val wake = PhoneAlarm(id = "wake", hour = 8, minute = 10, days = weekdays, vibeOnly = true, snoozeMin = 5, since = at(15, 0)) // set on Sunday
    private val cats1 = PhoneAlarm(id = "cats1", hour = 12, minute = 30)
    private val cats2 = PhoneAlarm(id = "cats2", hour = 20, minute = 20)
    private val pills = PhoneAlarm(id = "pills", hour = 9, minute = 0)
    private val tea = PhoneAlarm(id = "tea", hour = 16, minute = 0)
    private val sat = PhoneAlarm(id = "sat", hour = 10, minute = 0, days = setOf(SATURDAY, SUNDAY))
    private val pool = listOf(wake, cats1, cats2, pills, tea, sat) // 6 alarms > 4 slots

    private fun empty() = List<String?>(4) { null }

    @Test fun mondayMorningLoadsNextFour() =
        assertEquals(listOf("wake", "pills", "cats1", "tea"), AlarmPool.plan(pool, empty(), at(16, 7, 0)))

    @Test fun afterWakeRangSnoozeKeepsSlotAndCats2Waits() {
        val plan = AlarmPool.plan(pool, listOf("wake", "pills", "cats1", "tea"), at(16, 8, 11))
        assertEquals(listOf("wake", "pills", "cats1", "tea"), plan)
    }

    @Test fun snoozeKeepsItsSlotUntilStopped() =
        assertEquals("wake", AlarmPool.plan(pool, listOf("wake", "pills", "cats1", "tea"), at(16, 11, 0))[0])

    @Test fun afterStopCats2TakesFreedSlot() {
        val stopped = AlarmPool.dismiss(pool, "wake", at(16, 8, 20))
        val plan = AlarmPool.plan(stopped, listOf("wake", "pills", "cats1", "tea"), at(16, 8, 21))
        assertEquals(listOf("cats2", "pills", "cats1", "tea"), plan) // wake's next ring is tomorrow 8:10 (<24h) but later than the others
    }

    @Test fun weekendAlarmNotLoadedOnWeekday() =
        assertTrue("sat" !in AlarmPool.plan(pool, empty(), at(16, 7, 0)))

    @Test fun weekdayAlarmNotLoadedFridayNight() =
        assertTrue("wake" !in AlarmPool.plan(pool.map { it.copy(snoozeMin = 0) }, empty(), at(20, 21, 0)))

    @Test fun saturdayAlarmWaitsWhenFourAreDueSooner() =
        assertTrue("sat" !in AlarmPool.plan(pool, empty(), at(20, 10, 1)))

    @Test fun saturdayAlarmLoadedOnceASlotFrees() =
        assertTrue("sat" in AlarmPool.plan(pool, empty(), at(20, 21, 0))) // after 20:20 rang

    @Test fun neverLoadsAlarmThatWouldRingEarlierThanIntended() {
        // Friday 07:00: Saturday's 10:00 alarm is 27 h away -> must not be in a daily slot yet
        assertTrue("sat" !in AlarmPool.plan(pool, empty(), at(20, 7, 0)))
    }

    @Test fun toWatchDisablesUnusedSlots() {
        val watch = List(5) { Alarm(0, 0, enabled = true, snooze = true) }
        val w = AlarmPool.toWatchAlarms(listOf("cats1", null, null, null), pool, watch, at(16, 7))
        assertEquals(Alarm(12, 30, enabled = true, snooze = false), w[0]) // the watch's own snooze is never used
        assertEquals(false, w[1].enabled)
        assertEquals(watch[4], w[4]) // 5th slot untouched
    }

    // Sound mode: the user's base mode, except in an alarm's window (5 min before -> end of hold).
    private val base = SoundMode.CUSTOM

    @Test fun userModeOutsideAlarmWindows() {
        assertEquals(SoundMode.CUSTOM, AlarmPool.desiredSoundMode(pool, at(15, 22, 0), base)) // evening before vibe alarm
        assertEquals(SoundMode.CUSTOM, AlarmPool.desiredSoundMode(pool, at(16, 8, 4), base))  // 6 min before wake
        assertEquals(SoundMode.ALL_ON, AlarmPool.desiredSoundMode(pool, at(16, 7, 0), SoundMode.ALL_ON))
    }

    @Test fun vibeAlarmBorrowsTheWatch() {
        assertEquals(SoundMode.VIBE_ONLY, AlarmPool.desiredSoundMode(pool, at(16, 8, 5), base))  // 5 min before
        assertEquals(SoundMode.VIBE_ONLY, AlarmPool.desiredSoundMode(pool, at(16, 10, 30), base)) // still snoozing
        val stopped = AlarmPool.dismiss(pool, "wake", at(16, 10, 30))
        assertEquals(SoundMode.CUSTOM, AlarmPool.desiredSoundMode(stopped, at(16, 10, 31), base)) // snooze stopped
    }

    @Test fun soundAlarmNeedsSoundWhenUserIsOnVibe() {
        assertEquals(SoundMode.CUSTOM, AlarmPool.desiredSoundMode(pool, at(16, 12, 28), SoundMode.VIBE_ONLY))
        assertEquals(SoundMode.VIBE_ONLY, AlarmPool.desiredSoundMode(pool, at(16, 14, 0), SoundMode.VIBE_ONLY))
        assertEquals(SoundMode.SOUND_ONLY, AlarmPool.desiredSoundMode(pool, at(16, 12, 28), SoundMode.SOUND_ONLY))
    }

    @Test fun stoppedSnoozeReturnsToUserMode() {
        val stopped = AlarmPool.dismiss(pool, "wake", at(16, 8, 15))
        assertEquals(SoundMode.CUSTOM, AlarmPool.desiredSoundMode(stopped, at(16, 8, 15), base))
    }

    // Do not disturb at night (23:00-08:20): Off at night, alarms still borrow the watch.
    private val night = (23 * 60) to (8 * 60 + 20)

    @Test fun nightIsSilentExceptAlarms() {
        val b = { t: LocalDateTime -> AlarmPool.baseAt(base, night, t) }
        assertEquals(SoundMode.ALL_OFF, b(at(15, 23, 30)))
        assertEquals(SoundMode.ALL_OFF, b(at(16, 3, 0)))
        assertEquals(SoundMode.CUSTOM, b(at(16, 8, 20)))
        assertEquals(SoundMode.CUSTOM, b(at(15, 22, 59)))
        assertEquals(SoundMode.VIBE_ONLY, AlarmPool.desiredSoundMode(pool, at(16, 8, 6), b(at(16, 8, 6))))  // wake alarm
        assertEquals(SoundMode.ALL_OFF, AlarmPool.desiredSoundMode(pool, at(16, 8, 4), b(at(16, 8, 4))))    // before it
        assertEquals(SoundMode.CUSTOM, AlarmPool.desiredSoundMode(listOf(cats1.copy(hour = 1)), at(16, 1, 27), b(at(16, 1, 27)))) // sound alarm at night (01:30)
        assertEquals(at(15, 23, 0), AlarmPool.nextNightBoundary(night, at(15, 22, 0)))
        assertEquals(at(16, 8, 20), AlarmPool.nextNightBoundary(night, at(16, 1, 0)))
    }

    @Test fun soundBoundariesAreWindowEdges() {
        assertEquals(at(16, 8, 5), AlarmPool.nextSoundBoundary(listOf(wake), at(16, 7, 0)))
        assertEquals(at(16, 8, 11), AlarmPool.nextSoundBoundary(listOf(wake.copy(snoozeMin = 0)), at(16, 8, 6)))
        assertEquals(at(17, 8, 5), AlarmPool.nextSoundBoundary(listOf(wake), at(16, 8, 6))) // snoozing: no end of its own
    }

    // Smart alarms: a smart 08:10 Mon-Fri (window 30) plus normal alarms.
    private val smartWake = wake.copy(id = "smartWake", smartWindowMin = 30)
    private val smartPool = listOf(smartWake, cats1, cats2)

    @Test fun smartAlarmNeverInNormalSlots() =
        assertTrue("smartWake" !in AlarmPool.plan(smartPool, empty(), at(16, 7, 0)))

    @Test fun smartSlotTakesNextSmartWithin24h() =
        assertEquals("smartWake", AlarmPool.planSmart(smartPool, at(15, 22, 0))) // Sun 22:00 -> Mon 08:10

    @Test fun smartSlotEmptyOnFridayNight() =
        assertEquals(null, AlarmPool.planSmart(smartPool, at(20, 22, 0)))

    @Test fun smartSlotKeptForTomorrowAfterItsTime() = // next smart ring is tomorrow 08:10: not rewritten
        assertEquals("smartWake", AlarmPool.planSmart(smartPool, at(16, 8, 30)))

    @Test fun smartSnoozeRepeatsInANormalSlotFromTheTargetTime() {
        val plan = AlarmPool.plan(smartPool, empty(), at(16, 7, 45)) // inside the window
        assertTrue("smartWake" in plan)
        val w = AlarmPool.toWatchAlarms(plan, smartPool, List(4) { Alarm(0, 0, enabled = false) }, at(16, 7, 45))
        assertEquals(Alarm(8, 15, enabled = true, snooze = false), w[plan.indexOf("smartWake")])
        assertEquals("smartWake", AlarmPool.planSmart(smartPool, at(16, 7, 45)))
    }

    @Test fun smartStoppedInItsWindowFreesBothSlots() {
        val stopped = AlarmPool.dismiss(smartPool, "smartWake", at(16, 7, 50)) // woken early, stopped
        assertEquals(null, AlarmPool.planSmart(stopped, at(16, 7, 51)))
        assertTrue("smartWake" !in AlarmPool.plan(stopped, empty(), at(16, 7, 51)))
        assertTrue(AlarmPool.snoozing(stopped, at(16, 7, 51)).isEmpty())
    }

    @Test fun soundModeSwitchesBeforeSmartWindow() {
        val vibeSmart = smartWake.copy(vibeOnly = true)
        // 07:45 is inside the 30-min window before 08:10 -> must already be vibe-only
        assertEquals(SoundMode.VIBE_ONLY, AlarmPool.desiredSoundMode(listOf(vibeSmart, cats1), at(16, 7, 35), base)) // 5 min before window
        assertEquals(SoundMode.CUSTOM, AlarmPool.desiredSoundMode(listOf(vibeSmart, cats1), at(16, 7, 30), base))
    }

    // ---- one-time alarms ----

    private val nap = PhoneAlarm(id = "nap", hour = 15, minute = 0, once = true)

    @Test fun armSetsTodayWhenStillAhead() =
        assertEquals(java.time.LocalDate.of(2026, 11, 16), AlarmPool.arm(nap, at(16, 14)).onceDate)

    @Test fun armSetsTomorrowWhenPassed() =
        assertEquals(java.time.LocalDate.of(2026, 11, 17), AlarmPool.arm(nap, at(16, 15, 0)).onceDate)

    @Test fun armClearsDateOfRepeatingAlarm() =
        assertEquals(null, AlarmPool.arm(cats1.copy(onceDate = java.time.LocalDate.of(2026, 11, 16)), at(16, 9)).onceDate)

    @Test fun oneTimeRingsOnlyOnItsDay() {
        val armed = AlarmPool.arm(nap, at(16, 14))
        assertEquals(at(16, 15), AlarmPool.nextRing(armed, at(16, 14)))
        assertEquals(null, AlarmPool.nextRing(armed, at(16, 16))) // not again tomorrow
    }

    @Test fun oneTimeLoadedOnWatchBeforeItRings() {
        val armed = AlarmPool.arm(nap, at(16, 14))
        assertTrue("nap" in AlarmPool.plan(listOf(armed), List(AlarmPool.SLOTS) { null }, at(16, 14)))
    }

    @Test fun oneTimeKeptWhileSnoozing() {
        val armed = AlarmPool.arm(nap.copy(snoozeMin = 5), at(16, 14))
        assertEquals(listOf(armed), AlarmPool.expireOnce(listOf(armed), at(17, 9, 0))) // no end until stopped
    }

    @Test fun oneTimeSwitchedOffWhenSnoozeStopped() {
        val armed = AlarmPool.arm(nap.copy(snoozeMin = 5), at(16, 14))
        val after = AlarmPool.expireOnce(AlarmPool.dismiss(listOf(armed, cats1), "nap", at(16, 15, 40)), at(16, 15, 40))
        assertEquals(false, after[0].enabled)
        assertEquals(cats1, after[1]) // repeating alarms untouched
        assertTrue(AlarmPool.plan(after, List(AlarmPool.SLOTS) { "nap" }, at(16, 15, 40)).none { it == "nap" })
    }

    @Test fun oneTimeReArmedWhenSwitchedBackOn() {
        val off = AlarmPool.arm(nap, at(16, 14)).copy(enabled = false)
        assertEquals(java.time.LocalDate.of(2026, 11, 17), AlarmPool.arm(off.copy(enabled = true), at(16, 18)).onceDate)
    }

    @Test fun oneTimeWithoutSnoozeSwitchedOffRightAfterRing() {
        val armed = AlarmPool.arm(nap, at(16, 14))
        assertEquals(true, AlarmPool.expireOnce(listOf(armed), at(16, 15, 0))[0].enabled) // ringing now
        assertEquals(false, AlarmPool.expireOnce(listOf(armed), at(16, 15, 1))[0].enabled) // 1 min later
    }

    @Test fun soundModeNotHeldAfterAlarmWithoutSnooze() {
        // wake (vibe only, 08:10) without snooze: at 08:12 the next alarm (cats1, sound) governs.
        val noSnooze = wake.copy(snoozeMin = 0)
        assertEquals(SoundMode.CUSTOM, AlarmPool.desiredSoundMode(listOf(noSnooze, cats1), at(16, 8, 12), base))
        assertEquals(SoundMode.VIBE_ONLY, AlarmPool.desiredSoundMode(listOf(wake, cats1), at(16, 8, 12), base)) // with snooze: held
    }

    // ---- phone-side snooze ----

    private val cats1z = cats1.copy(snoozeMin = 5, since = at(16, 0))

    @Test fun snoozingOnlyWithSnoozeOnAndWithoutEnd() {
        val list = listOf(cats1z, cats2)
        assertEquals(listOf("cats1" to at(16, 12, 50)), AlarmPool.snoozing(list, at(16, 12, 45)).map { it.first.id to it.second })
        assertEquals(listOf("cats1"), AlarmPool.snoozing(list, at(16, 23, 0)).map { it.first.id }) // still, hours later
        assertEquals(listOf("cats1"), AlarmPool.snoozing(list, at(16, 20, 25)).map { it.first.id }) // cats2 has no snooze
    }

    @Test fun slotFollowsTheRepeats() {
        val slot = { t: LocalDateTime -> AlarmPool.slotTime(cats1z, t) }
        assertEquals(java.time.LocalTime.of(12, 30), slot(at(16, 12, 0)))
        assertEquals(java.time.LocalTime.of(12, 35), slot(at(16, 12, 31)))
        assertEquals(java.time.LocalTime.of(12, 40), slot(at(16, 12, 35)))   // the 12:35 ring is now: next one
        assertEquals(java.time.LocalTime.of(12, 40), slot(at(16, 12, 36)))
        assertEquals(java.time.LocalTime.of(12, 30), slot(at(17, 12, 29)))   // the next day's ring comes first
        assertEquals(java.time.LocalTime.of(12, 30), AlarmPool.slotTime(AlarmPool.dismiss(listOf(cats1z), "cats1", at(16, 12, 40))[0], at(16, 12, 41)))
    }

    @Test fun nextDaysRingStartsANewRound() {
        assertEquals(at(17, 12, 35), AlarmPool.snoozing(listOf(cats1z), at(17, 12, 31)).single().second)
        val stoppedYesterday = AlarmPool.dismiss(listOf(cats1z), "cats1", at(16, 13, 0))
        assertEquals(1, AlarmPool.snoozing(stoppedYesterday, at(17, 12, 40)).size)
    }

    @Test fun boundariesAMinuteAfterEachRingAndRepeat() {
        assertEquals(at(16, 12, 31), AlarmPool.nextBoundary(listOf(cats1z), at(16, 12, 0)))  // not at 12:30: it rings then
        assertEquals(at(16, 12, 36), AlarmPool.nextBoundary(listOf(cats1z), at(16, 12, 31).plusSeconds(5)))
        assertEquals(at(16, 7, 40), AlarmPool.nextBoundary(listOf(smartWake), at(16, 7, 0))) // smart window opens
    }

    @Test fun ringingOnlyDuringRingMinutes() {
        assertTrue(AlarmPool.ringing(listOf(cats1z), at(16, 12, 30).plusSeconds(20)))
        assertTrue(!AlarmPool.ringing(listOf(cats1z), at(16, 12, 31).plusSeconds(5)))
        assertTrue(AlarmPool.ringing(listOf(cats1z), at(16, 12, 45).plusSeconds(5)))  // a repeat
        assertTrue(!AlarmPool.ringing(listOf(cats1), at(16, 12, 45).plusSeconds(5))) // no snooze
        assertTrue(!AlarmPool.ringing(listOf(smartWake), at(16, 7, 45)))             // smart window: unknown, not held
    }

    @Test fun ringsBeforeTheAlarmWasSetNeverSnooze() {
        val setLater = cats1z.copy(since = at(16, 18, 0)) // snooze switched on in the evening
        assertTrue(AlarmPool.snoozing(listOf(setLater), at(16, 19, 0)).isEmpty())
        assertEquals(1, AlarmPool.snoozing(listOf(setLater), at(17, 12, 31)).size)
    }

    @Test fun touchOnlyForChangesToWhenItRings() {
        val now = at(16, 12, 40)
        assertEquals(now, AlarmPool.touch(cats1, cats1.copy(snoozeMin = 5), now).since)  // snooze switched on
        assertEquals(now, AlarmPool.touch(cats1z, cats1z.copy(hour = 13), now).since)
        assertEquals(cats1z.since, AlarmPool.touch(cats1z, cats1z.copy(label = "x", snoozeMin = 10), now).since) // keeps snoozing
    }

    @Test fun dismissEndsTheSnoozeNow() {
        val after = AlarmPool.dismiss(listOf(cats1z), "cats1", at(16, 12, 35))
        assertEquals(at(16, 12, 30), after[0].dismissedRing)
        assertTrue(AlarmPool.snoozing(after, at(16, 12, 35)).isEmpty())
        assertEquals(1, AlarmPool.snoozing(listOf(cats1z), at(16, 12, 35)).size) // without the stop: still snoozing
    }

    @Test fun dismissedOneTimeAlarmSwitchesOff() {
        val armed = AlarmPool.arm(nap.copy(snoozeMin = 5), at(16, 14))
        val stopped = AlarmPool.dismiss(listOf(armed), "nap", at(16, 15, 3))
        assertEquals(false, AlarmPool.expireOnce(stopped, at(16, 15, 3))[0].enabled)
    }
}
