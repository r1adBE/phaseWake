package com.phasewake.app.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Probewecker, Powernap und Testergebnis; Gegenstück: `TransientTests` (Swift). */
class TransientTest {
    private val zurich = ZoneId.of("Europe/Zurich")
    private val now = LocalDateTime.parse("2026-10-29T14:00").atZone(zurich).toInstant()
    private val base = AlarmSpec(
        id = "a", hour = 7, minute = 0, phone = PhoneAlert.VIBRATE, watchSound = true, sound = "raw:wake_birds",
        sunrise = Sunrise.WINDOW, sunriseDevice = SunriseDevice.BOTH, wakeTask = WakeTask.MATH, label = "Arbeit",
    )

    @Test
    fun testPlanStartsInOneAndRingsInTwoMinutes() {
        val t = Transient.test(base, now, zurich, "1234")
        assertEquals(TransientKind.TEST, t.kind)
        assertEquals("test-1234", t.plan.alarmId)
        assertEquals(now.plusSeconds(60), t.plan.windowStart)
        assertEquals(now.plusSeconds(120), t.plan.target)
        assertEquals(t.plan.alarmId, t.spec.id)
        assertEquals(14, t.spec.hour)
        assertEquals(2, t.spec.minute)
        assertEquals(PhoneAlert.VIBRATE, t.spec.phone)
        assertTrue(t.spec.watchSound)
        assertEquals("raw:wake_birds", t.spec.sound)
        assertEquals(Sunrise.WINDOW, t.spec.sunrise)
        assertEquals(SunriseDevice.BOTH, t.spec.sunriseDevice)
        assertEquals(WakeTask.NONE, t.spec.wakeTask)
        assertEquals("", t.spec.label)
        assertTrue(t.spec.isOneShot)
    }

    @Test
    fun testWithoutAlarmUsesDefaults() {
        val t = Transient.test(null, now, zurich, "x")
        assertEquals(PhoneAlert.SOUND, t.spec.phone)
        assertFalse(t.spec.watchSound)
        assertEquals(Sunrise.OFF, t.spec.sunrise)
    }

    @Test
    fun napWindowAndLimits() {
        val nap = Transient.nap(base, now, zurich, 20, "n1")
        assertEquals("nap-n1", nap.plan.alarmId)
        assertEquals(now.plus(Duration.ofMinutes(20)), nap.plan.target)
        assertEquals(now.plus(Duration.ofMinutes(15)), nap.plan.windowStart)
        assertEquals(Sunrise.OFF, nap.spec.sunrise)
        assertEquals(PhoneAlert.VIBRATE, nap.spec.phone)
        val short = Transient.nap(null, now, zurich, 15, "n2")
        assertEquals(now.plus(Duration.ofMinutes(12)), short.plan.windowStart)
        assertEquals(now.plus(Duration.ofMinutes(10)), Transient.nap(null, now, zurich, 1, "n3").plan.target)
        assertEquals(now.plus(Duration.ofMinutes(90)), Transient.nap(null, now, zurich, 500, "n4").plan.target)
        assertEquals(3, Transient.napWindowMinutes(10))
        assertEquals(5, Transient.napWindowMinutes(16))
    }

    @Test
    fun kindAndSnoozeByPrefix() {
        assertEquals(TransientKind.TEST, Transient.kindOf("test-1"))
        assertEquals(TransientKind.NAP, Transient.kindOf("nap-1"))
        assertNull(Transient.kindOf("3f2a-77"))
        assertNull(Transient.kindOf(null))
        assertFalse(Transient.allowsSnooze("test-1"))
        assertTrue(Transient.allowsSnooze("nap-1"))
        assertTrue(Transient.allowsSnooze("3f2a"))
        assertTrue(Transient.allowsSnooze(null))
    }

    @Test
    fun activeUntilTwoHoursAfterTarget() {
        val t = Transient.test(base, now, zurich, "1")
        assertTrue(Transient.isActive(t, now))
        assertTrue(Transient.isActive(t, t.plan.target.plus(Duration.ofMinutes(119))))
        assertFalse(Transient.isActive(t, t.plan.target.plus(Duration.ofHours(2))))
    }

    @Test
    fun encodeAndDecode() {
        val t = Transient.test(base, now, zurich, "abc")
        val text = Transient.encode(t)
        assertTrue(text.startsWith("phasewake-transient 1\nTEST|${now.plusSeconds(60).toEpochMilli()}|${now.plusSeconds(120).toEpochMilli()}\nphasewake-alarms 1\n"))
        assertEquals(t, Transient.decode(text))
        val nap = Transient.nap(null, now, zurich, 30, "n")
        assertEquals(nap, Transient.decode(Transient.encode(nap)))
    }

    @Test
    fun decodeRejectsForeignOrBrokenText() {
        val good = Transient.encode(Transient.test(base, now, zurich, "abc"))
        assertFailsWith<CodecException> { Transient.decode("phasewake-alarms 1\n") }
        assertFailsWith<CodecException> { Transient.decode(good.replace("TEST|", "LAMP|")) }
        assertFailsWith<CodecException> { Transient.decode(good.replace("TEST|", "NAP|")) }
        assertFailsWith<CodecException> { Transient.decode("phasewake-transient 1\nTEST|x|1\n") }
        assertFailsWith<CodecException> { Transient.decode("phasewake-transient 1\nTEST|1|x\n") }
        assertFailsWith<CodecException> { Transient.decode("phasewake-transient 1\nTEST|1|2\nphasewake-alarms 1\n") }
        assertFailsWith<CodecException> { Transient.decode("phasewake-transient 1\nTEST|9|2\n" + Codec.encodeAlarms(listOf(base.copy(id = "test-1")))) }
    }

    @Test
    fun testRunRecordsFirstSeen() {
        val target = now.plusSeconds(120)
        var run = TestRun("test-1", target, PhoneAlert.SOUND)
        run = TestRuns.record(run, TestStep.RECEIVED, now)
        run = TestRuns.record(run, TestStep.RECEIVED, now.plusSeconds(5))
        assertEquals(now, run.seen[TestStep.RECEIVED])
        assertEquals(true, TestRuns.result(run, TestStep.RECEIVED))
        assertEquals(false, TestRuns.result(run, TestStep.WATCH_RANG))
        assertFalse(TestRuns.isFinished(run, target))
        assertTrue(TestRuns.isFinished(run, target.plus(TestRuns.RESULT_AFTER_TARGET)))
        run = TestRuns.record(run, TestStep.STOP_ARRIVED, target.plusSeconds(10))
        assertTrue(TestRuns.isFinished(run, target))
    }

    @Test
    fun phoneStepNotCheckedWhenPhoneAlarmOff() {
        val run = TestRun("test-1", now, PhoneAlert.OFF)
        assertNull(TestRuns.result(run, TestStep.PHONE_RANG))
        assertEquals(false, TestRuns.result(run, TestStep.STOP_ARRIVED))
    }

    @Test
    fun testRunCodec() {
        val run = TestRun("test-1", now, PhoneAlert.VIBRATE, mapOf(TestStep.WATCH_RANG to now.plusSeconds(3), TestStep.RECEIVED to now))
        val text = TestRuns.encode(run)
        assertEquals("test-1|${now.toEpochMilli()}|VIBRATE|RECEIVED=${now.toEpochMilli()},WATCH_RANG=${now.plusSeconds(3).toEpochMilli()}", text)
        assertEquals(run, TestRuns.decode(text))
        assertEquals(TestRun("t", now, PhoneAlert.SOUND), TestRuns.decode("t|${now.toEpochMilli()}|NEU|"))
        assertEquals(TestRun("t", now, PhoneAlert.SOUND), TestRuns.decode("t|${now.toEpochMilli()}|SOUND|LAMP=1,RECEIVED=x"))
        assertNull(TestRuns.decode(null))
        assertNull(TestRuns.decode("t|1"))
        assertNull(TestRuns.decode("t|x|SOUND|"))
    }
}
