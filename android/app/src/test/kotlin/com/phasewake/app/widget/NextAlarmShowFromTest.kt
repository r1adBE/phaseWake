package com.phasewake.app.widget

import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

/** Mitteilung «Nächster Wecker» ab der Bettzeit-Erinnerung, mit der einen Bettzeit (Audit O-03). */
class NextAlarmShowFromTest {
    private val target = Instant.parse("2026-11-15T06:30:00Z")

    @Test
    fun followsTheReminderWithLearnedLatency() {
        // 7½ Std. Schlafziel, 30 Min Vorlauf: 22:30; mit 15 Min gelernter Einschlafdauer 22:15.
        assertEquals(Instant.parse("2026-11-14T22:30:00Z"), NextAlarmNotice.showFrom(target, ZoneOffset.UTC, 450, true))
        assertEquals(Instant.parse("2026-11-14T22:15:00Z"), NextAlarmNotice.showFrom(target, ZoneOffset.UTC, 450, true, latencyMinutes = 15))
        assertEquals(target.minus(Duration.ofMinutes(495)), NextAlarmNotice.showFrom(target, ZoneOffset.UTC, 450, true, 15))
    }
}
