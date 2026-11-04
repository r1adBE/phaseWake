package com.phasewake.app.ring

import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.WakePlan
import com.phasewake.app.shared.RingMessage
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Entscheidungen des Zweitweckers ([RingRules]): Nachfrist, Ablösen, «stop» nur für die eigene Nacht. */
class RingRulesTest {

    private val zurich = ZoneId.of("Europe/Zurich")
    private val grace = Duration.ofMinutes(2)

    private fun at(text: String): Instant = LocalDateTime.parse(text).atZone(zurich).toInstant()

    private fun alarm(id: String, hour: Int = 7, minute: Int = 0) = AlarmSpec(id = id, hour = hour, minute = minute)

    private fun plan(
        backups: List<AlarmSpec>,
        now: Instant,
        plannedId: String? = null,
        plannedTarget: Instant? = null,
        suppressed: Map<String, Instant> = emptyMap(),
        grace: Duration = this.grace,
        transient: WakePlan? = null,
    ): WakePlan? = RingRules.backupPlan(backups, transient, plannedId, plannedTarget, suppressed, now, grace, zurich, emptySet())

    @Test
    fun graceKeepsPlannedBackupAtProcessStart() {
        // Prozessstart um 07:01: Der Zweitwecker 07:00 (klingelt 07:02) bleibt, statt auf morgen zu rutschen.
        val kept = plan(listOf(alarm("a")), at("2026-10-28T07:01"), "a", at("2026-10-28T07:00"))!!
        assertEquals(at("2026-10-28T07:00"), kept.target)
        // Ohne Uhr (Nachfrist null) gibt es keine Nachfrist: der nächste Termin.
        val noWatch = plan(listOf(alarm("a")), at("2026-10-28T07:01"), "a", at("2026-10-28T07:00"), grace = Duration.ZERO)!!
        assertEquals(at("2026-10-29T07:00"), noWatch.target)
        // Nach der Nachfrist oder ausgesetzt (Uhr hat übernommen, Zweitwecker hat geklingelt): nächster Termin.
        assertEquals(at("2026-10-29T07:00"), plan(listOf(alarm("a")), at("2026-10-28T07:02"), "a", at("2026-10-28T07:00"))!!.target)
        val handled = mapOf("a" to at("2026-10-28T07:02"))
        assertEquals(at("2026-10-29T07:00"), plan(listOf(alarm("a")), at("2026-10-28T07:01"), "a", at("2026-10-28T07:00"), handled)!!.target)
    }

    @Test
    fun secondAlarmStaysPlannedAfterFirstRang() {
        // 07:00 hat geklingelt (bis 07:02 ausgesetzt): 07:05 desselben Morgens bleibt der nächste Zweitwecker.
        val specs = listOf(alarm("a"), alarm("b", minute = 5))
        val next = plan(specs, at("2026-10-28T07:02"), "a", at("2026-10-28T07:00"), mapOf("a" to at("2026-10-28T07:02")))!!
        assertEquals("b", next.alarmId)
        assertEquals(at("2026-10-28T07:05"), next.target)
    }

    @Test
    fun transientKeepsItsGrace() {
        val nap = WakePlan("nap:1", at("2026-10-28T13:00"), at("2026-10-28T13:20"))
        assertEquals(nap, plan(emptyList(), at("2026-10-28T13:21"), transient = nap))
        assertNull(plan(emptyList(), at("2026-10-28T13:22"), transient = nap))
        assertNull(plan(emptyList(), at("2026-10-28T13:21"), suppressed = mapOf("nap:1" to at("2026-10-28T13:22")), transient = nap))
    }

    @Test
    fun secondAlarmTakesOverRing() {
        assertEquals(RingRules.Incoming.NEW_ROUND, RingRules.incoming(false, "b", 2L, "a", 1L))
        assertEquals(RingRules.Incoming.TAKE_OVER, RingRules.incoming(true, "b", 2L, "a", 1L))
        assertEquals(RingRules.Incoming.TAKE_OVER, RingRules.incoming(true, "a", 2L, "a", 1L))
        assertEquals(RingRules.Incoming.SAME_NIGHT, RingRules.incoming(true, "a", 1L, "a", 1L))
        // Schlummer-Ende oder ältere Uhr ohne Zielzeit: kein Widerspruch, dieselbe Nacht.
        assertEquals(RingRules.Incoming.SAME_NIGHT, RingRules.incoming(true, "a", null, "a", 1L))
    }

    @Test
    fun onlyRingingAlarmCountsAsWoken() {
        assertEquals(RingResult.STARTED, RingRules.result(started = true, ringing = false))
        assertEquals(RingResult.TOOK_OVER, RingRules.result(started = true, ringing = true))
        assertEquals(RingResult.REJECTED, RingRules.result(started = false, ringing = true))
        assertTrue(RingResult.STARTED.rings)
        assertTrue(RingResult.TOOK_OVER.rings)
        assertFalse(RingResult.REJECTED.rings)
    }

    @Test
    fun stopOnlyForOwnNight() {
        val now = 1_000_000_000L
        val own = RingMessage("a", 1L, now - 1_000)
        assertTrue(RingRules.stopApplies(own, now, active = true, currentId = "a", currentTarget = 1L))
        assertFalse(RingRules.stopApplies(own, now, active = false, currentId = "a", currentTarget = 1L), "nichts läuft")
        assertFalse(RingRules.stopApplies(own, now, active = true, currentId = "b", currentTarget = 2L), "andere Nacht")
        val stale = own.copy(sentAt = now - RingMessage.MAX_AGE_MS - 1)
        assertFalse(RingRules.stopApplies(stale, now, active = true, currentId = "a", currentTarget = 1L), "veraltet")
        // Ältere Uhr ohne Kennung und Sendezeit: wie bisher.
        assertTrue(RingRules.stopApplies(RingMessage(null), now, active = true, currentId = "a", currentTarget = 1L))
    }

    @Test
    fun directBootKeepsFollowingBackups() {
        // Zwei Wecker am Morgen (06:30 täglich, 07:00 einmalig): nach dem ersten der zweite, dann der nächste Tag.
        val daily = alarm("d", 6, 30).copy(days = java.time.DayOfWeek.entries.toSet())
        val once = alarm("o", 7, 0)
        val first = plan(listOf(daily, once), at("2026-10-28T01:00"))!!
        val plans = RingRules.directBootPlans(first, listOf(daily, once), null, emptyMap(), grace, grace, zurich, emptySet(), 4)
        assertEquals(
            listOf("d" to at("2026-10-28T06:30"), "o" to at("2026-10-28T07:00"), "d" to at("2026-10-29T06:30"), "d" to at("2026-10-30T06:30")),
            plans.map { it.alarmId to it.target },
        )
        // Der Einmal-Wecker kommt nach seinem Klingeln nicht wieder; ohne weitere Wecker endet die Liste.
        val onlyOnce = RingRules.directBootPlans(plan(listOf(once), at("2026-10-28T01:00"))!!, listOf(once), null, emptyMap(), grace, grace, zurich, emptySet(), 4)
        assertEquals(listOf(at("2026-10-28T07:00")), onlyOnce.map { it.target })
    }

    @Test
    fun zoneChangeInWindowKeepsPlannedBackup() {
        // 07:00 Zürich, Fenster 30 min; um 06:40 wechselt die Zone nach Tokio (14:40): Der Zweitwecker bleibt 07:00 Zürich.
        val tokyo = ZoneId.of("Asia/Tokyo")
        val spec = AlarmSpec(id = "a", hour = 7, minute = 0, windowMinutes = 30)
        val planned = at("2026-11-04T07:00")
        fun inTokyo(
            now: Instant,
            plannedZone: ZoneId?,
            suppressed: Map<String, Instant> = emptyMap(),
            backups: List<AlarmSpec> = listOf(spec),
        ) = RingRules.backupPlan(backups, null, "a", planned, suppressed, now, grace, tokyo, emptySet(), plannedZone)
        assertEquals(planned, inTokyo(at("2026-11-04T06:40"), zurich)?.target)
        // In der Nachfrist noch, danach der nächste Termin in Tokio.
        assertEquals(planned, inTokyo(at("2026-11-04T07:01"), zurich)?.target)
        assertTrue(inTokyo(at("2026-11-04T07:02"), zurich)!!.target.isAfter(planned.plus(Duration.ofHours(1))))
        // Vor dem Fenster, ohne Zone (alte Daten), ausgesetzt oder geänderter Wecker: wie bisher (Ortszeit Tokio).
        val tokyoNext = RingRules.backupPlan(listOf(spec), null, null, null, emptyMap(), at("2026-11-04T06:40"), grace, tokyo, emptySet())
        assertEquals(tokyoNext, inTokyo(at("2026-11-04T06:40"), null))
        assertTrue(inTokyo(at("2026-11-04T06:20"), zurich)!!.target != planned)
        assertTrue(inTokyo(at("2026-11-04T06:40"), zurich, mapOf("a" to planned))!!.target != planned)
        assertTrue(inTokyo(at("2026-11-04T06:40"), zurich, backups = listOf(spec.copy(minute = 30)))!!.target != planned)
        assertNull(RingRules.keptBackup(listOf(spec), "a", planned, zurich, emptyMap(), at("2026-11-04T06:40"), grace, zurich, emptySet()))
        // Zürich → Berlin: Ortszeit gleich, kein Behalten (ein früherer neuer Wecker gilt wie bisher).
        val berlin = ZoneId.of("Europe/Berlin")
        assertNull(RingRules.keptBackup(listOf(spec), "a", planned, zurich, emptyMap(), at("2026-11-04T06:40"), grace, berlin, emptySet()))
    }
}
