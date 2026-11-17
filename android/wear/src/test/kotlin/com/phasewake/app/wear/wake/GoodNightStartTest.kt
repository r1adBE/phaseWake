package com.phasewake.app.wear.wake

import com.phasewake.app.wear.schedule.AutoOnset
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Startentscheid von «Gute Nacht» ohne Android ([GoodNightStart], Audit CODE-08): laufende Nacht, Sperre, Wecker,
 * Weckfenster, Zeitgrenze und Akku in der Reihenfolge von [startGoodNight]. Feste Zeiten.
 */
class GoodNightStartTest {
    private val since = Instant.parse("2026-11-17T21:00:00Z")
    private val latest = Instant.parse("2026-11-18T06:30:00Z")
    private val windowStart = latest.minus(Duration.ofMinutes(30))
    private val low = AutoOnset.MIN_BATTERY_PERCENT - 1

    private fun decide(
        alarmFound: Boolean = true,
        hasDuration: Boolean = true,
        automatic: Boolean = false,
        latest: Instant? = this.latest,
        alreadyDone: Boolean = false,
        now: Instant = since,
        battery: Int? = 80,
        charging: Boolean = false,
    ) = GoodNightStart.decide(alarmFound, hasDuration, automatic, since, latest, windowStart.takeIf { latest != null }, alreadyDone, now, battery, charging)

    @Test
    fun gateRunningNightLockAndPending() {
        assertEquals(GoodNightStart.Gate.NIGHT_RUNNING, GoodNightStart.gate(nightRunning = true, onsetRunning = false, pending = false, unlocked = true))
        // Vor dem Entsperren: kein Protokoll, auch bei laufender Nacht.
        assertEquals(GoodNightStart.Gate.BUSY, GoodNightStart.gate(nightRunning = true, onsetRunning = false, pending = false, unlocked = false))
        assertEquals(GoodNightStart.Gate.BUSY, GoodNightStart.gate(nightRunning = false, onsetRunning = true, pending = false, unlocked = true))
        assertEquals(GoodNightStart.Gate.BUSY, GoodNightStart.gate(nightRunning = false, onsetRunning = false, pending = true, unlocked = false))
        assertEquals(GoodNightStart.Gate.LOCKED, GoodNightStart.gate(nightRunning = false, onsetRunning = false, pending = false, unlocked = false))
        assertEquals(GoodNightStart.Gate.TRY, GoodNightStart.gate(nightRunning = false, onsetRunning = false, pending = false, unlocked = true))
    }

    @Test
    fun measuresWithLimits() {
        assertEquals(
            GoodNightStart.Decision.Measure(latest, latest.minus(WakeService.ONSET_GIVE_UP), since.plus(OnsetMonitor.MAX_DURATION)),
            decide(),
        )
    }

    @Test
    fun reasonsAgainstTheStart() {
        assertEquals(GoodNightStart.Decision.NotStarted("no alarm"), decide(alarmFound = false, latest = null))
        assertEquals(GoodNightStart.Decision.NotStarted("no sleep duration"), decide(hasDuration = false))
        // Automatisch auch ohne Schlafdauer.
        assertEquals(latest, (decide(hasDuration = false, automatic = true) as GoodNightStart.Decision.Measure).latest)
        assertEquals(GoodNightStart.Decision.NotStarted("no alarm time"), decide(latest = null))
        assertEquals(GoodNightStart.Decision.NotStarted("too far ahead"), decide(latest = since.plus(Duration.ofHours(17))))
        // Automatisch: nicht mehr im Weckfenster (Grund vor der Zeitgrenze) und nicht zweimal in derselben Nacht.
        assertEquals(GoodNightStart.Decision.NotStarted("window begun"), decide(automatic = true, now = windowStart))
        assertEquals(GoodNightStart.Decision.NotStarted("already done"), decide(automatic = true, alreadyDone = true))
    }

    @Test
    fun giveUpWhenTheLimitHasPassed() {
        // Wiederaufgenommen nach der Höchstdauer, vor dem Weckfenster.
        val late = since.plus(OnsetMonitor.MAX_DURATION)
        assertEquals(GoodNightStart.Decision.GiveUp("give up (max duration)", latest), decide(now = late))
        // Kurz vor der Weckzeit (Fenster von Hand): «give up».
        val short = since.plus(Duration.ofHours(1))
        assertEquals(
            GoodNightStart.Decision.GiveUp("give up", short),
            GoodNightStart.decide(true, true, false, since, short, null, false, short.minus(WakeService.ONSET_GIVE_UP), 80, false),
        )
        // Kurz davor misst sie noch.
        assertEquals(GoodNightStart.Decision.Measure::class, decide(now = late.minusSeconds(1))::class)
    }

    @Test
    fun batteryOnlyForTheAutomaticMeasurement() {
        assertEquals(GoodNightStart.Decision.SkipBattery(latest), decide(automatic = true, battery = low))
        // Am Ladegerät, unbekannter Akku oder von Hand: misst.
        assertEquals(GoodNightStart.Decision.Measure::class, decide(automatic = true, battery = low, charging = true)::class)
        assertEquals(GoodNightStart.Decision.Measure::class, decide(automatic = true, battery = null)::class)
        assertEquals(GoodNightStart.Decision.Measure::class, decide(battery = low)::class)
        // Zuerst die Gründe gegen den Start, dann die Zeitgrenze, erst dann der Akku.
        assertEquals(GoodNightStart.Decision.NotStarted::class, decide(automatic = true, alreadyDone = true, battery = low)::class)
        assertEquals(GoodNightStart.Decision.GiveUp::class, decide(automatic = true, battery = low, now = since.plus(OnsetMonitor.MAX_DURATION))::class)
    }

    @Test
    fun extensionCapIsTheEarlierOfFourHoursAndTheWindow() {
        assertEquals(since.plus(OnsetMonitor.MAX_EXTENDED), GoodNightStart.extendCap(since, latest))
        assertEquals(windowStart, GoodNightStart.extendCap(since.plus(Duration.ofHours(8)), windowStart))
        assertEquals(null, GoodNightStart.extendCap(since, null))
    }
}
