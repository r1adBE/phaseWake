package com.phasewake.app.ring

import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.WakePlan
import com.phasewake.app.shared.OnsetShift
import com.phasewake.app.shared.RingMessage
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
    fun watchEndsPhoneSnoozeOnlyOfItsNight() {
        // «stop» oder Nacht der Uhr hebt Schlummern bzw. Wiederaufnahme am Handy derselben Nacht auf (04.11.2026).
        assertTrue(RingRules.watchEndsPhoneSnooze("a", 1L, savedId = "a", savedTarget = 1L))
        assertFalse(RingRules.watchEndsPhoneSnooze("b", 2L, savedId = "a", savedTarget = 1L), "anderer Wecker")
        assertFalse(RingRules.watchEndsPhoneSnooze("a", 2L, savedId = "a", savedTarget = 1L), "gleicher Wecker, andere Nacht")
        // Fehlende Angaben (ältere Einträge, ältere Uhr) widersprechen nicht.
        assertTrue(RingRules.watchEndsPhoneSnooze("a", 1L, savedId = "a", savedTarget = null))
        assertTrue(RingRules.watchEndsPhoneSnooze(null, null, savedId = "a", savedTarget = 1L))
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

    private val tokyo = ZoneId.of("Asia/Tokyo")
    private val windowed = AlarmSpec(id = "a", hour = 7, minute = 0, windowMinutes = 30)
    private val zurichSeven = at("2026-11-04T07:00")

    /** Zweitwecker in Tokio für den in Zürich geplanten Wecker «a» 07:00 (eingestellte Zielzeit [zurichSeven]). */
    private fun inTokyo(
        now: Instant,
        plannedZone: ZoneId? = zurich,
        suppressed: Map<String, Instant> = emptyMap(),
        backups: List<AlarmSpec> = listOf(windowed),
        earlier: Instant? = null,
        transient: WakePlan? = null,
    ) = RingRules.backupPlan(
        backups, transient, "a", earlier ?: zurichSeven, suppressed, now, grace, tokyo, emptySet(),
        RingRules.PlannedBackup("a", zurichSeven, plannedZone, earlier),
    )

    @Test
    fun zoneChangeInWindowKeepsPlannedBackup() {
        // 07:00 Zürich, Fenster 30 min; um 06:40 wechselt die Zone nach Tokio (14:40): Der Zweitwecker bleibt 07:00 Zürich.
        val planned = zurichSeven
        val spec = windowed
        assertEquals(planned, inTokyo(at("2026-11-04T06:40"))?.target)
        // In der Nachfrist noch, danach der nächste Termin in Tokio.
        assertEquals(planned, inTokyo(at("2026-11-04T07:01"))?.target)
        assertTrue(inTokyo(at("2026-11-04T07:02"))!!.target.isAfter(planned.plus(Duration.ofHours(1))))
        // Vor dem Fenster, ohne Zone (alte Daten), ausgesetzt oder geänderter Wecker: wie bisher (Ortszeit Tokio).
        val tokyoNext = RingRules.backupPlan(listOf(spec), null, null, null, emptyMap(), at("2026-11-04T06:40"), grace, tokyo, emptySet())
        assertEquals(tokyoNext, inTokyo(at("2026-11-04T06:40"), plannedZone = null))
        assertTrue(inTokyo(at("2026-11-04T06:20"))!!.target != planned)
        assertTrue(inTokyo(at("2026-11-04T06:40"), suppressed = mapOf("a" to planned))!!.target != planned)
        assertTrue(inTokyo(at("2026-11-04T06:40"), backups = listOf(spec.copy(minute = 30)))!!.target != planned)
        val kept = RingRules.PlannedBackup("a", planned, zurich)
        assertNull(RingRules.keptBackup(listOf(spec), kept, emptyMap(), at("2026-11-04T06:40"), grace, zurich, emptySet()))
        // Zürich → Berlin: Ortszeit gleich, kein Behalten (ein früherer neuer Wecker gilt wie bisher).
        val berlin = ZoneId.of("Europe/Berlin")
        assertNull(RingRules.keptBackup(listOf(spec), kept, emptyMap(), at("2026-11-04T06:40"), grace, berlin, emptySet()))
    }

    @Test
    fun zoneChangeKeepsOnsetShiftedBackup() {
        // Einschlaf-Modus: Die Uhr hat 07:00 auf 06:20 vorverlegt (Fenster ab 06:20). Wechsel nach Tokio um 06:21.
        val earlier = at("2026-11-04T06:20")
        val now = at("2026-11-04T06:21")
        val shift = OnsetShift("a", zurichSeven.toEpochMilli(), earlier.toEpochMilli(), at("2026-11-04T01:00").toEpochMilli())
        val kept = assertNotNull(inTokyo(now, earlier = earlier))
        assertEquals(zurichSeven, kept.target, "eingestellte Zielzeit der Nacht")
        // Wie gewohnt darüber gelegt: Der Zweitwecker klingelt zur früheren Zielzeit.
        assertEquals(earlier, shift.applyTo(kept, now, grace).target)
        // Ohne frühere Zielzeit hätte das Fenster (06:30) noch nicht begonnen: wie bisher die Ortszeit Tokio.
        assertTrue(inTokyo(now)!!.target != zurichSeven)
    }

    @Test
    fun otherAlarmInKeptWindowDoesNotDisplaceIt() {
        // «b» 14:50 in Tokio fällt (06:50 Zürich) ins behaltene Fenster von «a»: «a» bleibt der Zweitwecker.
        val b = AlarmSpec(id = "b", hour = 14, minute = 50)
        val both = listOf(windowed, b)
        assertEquals("a", inTokyo(at("2026-11-04T06:40"), backups = both)?.alarmId)
        // Ohne die Regel hätte «b» ihn verdrängt.
        val plain = RingRules.backupPlan(both, null, null, null, emptyMap(), at("2026-11-04T06:40"), grace, tokyo, emptySet())
        assertEquals("b", plain?.alarmId)
        // Nach dem Klingeln von «a» (ausgesetzt): wieder wie gewohnt nach der Ortszeit Tokio.
        val handled = mapOf("a" to at("2026-11-04T07:02"))
        val after = inTokyo(at("2026-11-04T07:02"), suppressed = handled, backups = both)
        assertEquals(RingRules.backupPlan(both, null, null, null, handled, at("2026-11-04T07:02"), grace, tokyo, emptySet()), after)
        // Ein Probewecker oder Powernap davor klingelt trotzdem.
        val nap = WakePlan("nap", at("2026-11-04T06:41"), at("2026-11-04T06:45"))
        assertEquals(nap, inTokyo(at("2026-11-04T06:40"), backups = both, transient = nap))
    }
}
