package com.phasewake.app.ui.theme

import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** «Abends dunkel ab» in der App: Design-Auflösung und der Ticker zur nächsten Grenze. */
class EveningDarkTickerTest {

    private val zone = ZoneId.of("Europe/Zurich")

    @Test
    fun darkStaysDarkAndEveningOnlyDarkens() {
        // Dunkel bleibt dunkel; abends werden «Hell» und «System» dunkel, sonst gilt die Wahl.
        for (system in listOf(false, true)) for (evening in listOf(false, true)) {
            assertTrue(resolveDark(ThemeMode.DARK, system, evening))
            assertEquals(evening, resolveDark(ThemeMode.LIGHT, system, evening))
            assertEquals(evening || system, resolveDark(ThemeMode.SYSTEM, system, evening))
        }
    }

    @Test
    fun switchesExactlyAtTheBoundary() = runBlocking {
        // Fester Beginn 18:00, 17:59:59.9 Ortszeit: zuerst hell, dann (nach ~100 ms, nicht nach einer Minute Abfrage)
        // zur Grenze dunkel.
        val times = ArrayDeque(listOf(Instant.parse("2026-11-16T16:59:59.900Z"), Instant.parse("2026-11-16T17:00:00Z")))
        val flow = EveningDarkTicker.flow(
            flowOf(EveningDarkSetting(fromMinutes = 18 * 60)),
            now = { times.removeFirstOrNull() ?: Instant.parse("2026-11-16T17:00:01Z") },
            zone = { zone },
        )
        val started = System.nanoTime()
        val values = withTimeout(5_000) { flow.take(2).toList() }
        assertEquals(listOf(false, true), values)
        assertTrue(System.nanoTime() - started < 3_000_000_000L, "ohne Warten auf eine volle Minute")
    }

    @Test
    fun changedSettingRestartsAtOnce() = runBlocking {
        // 20:00 Ortszeit: an → dunkel; ausgeschaltet → sofort hell, ohne auf die nächste Grenze zu warten.
        val setting = MutableStateFlow(EveningDarkSetting())
        val at = Instant.parse("2026-11-16T19:00:00Z")
        val seen = mutableListOf<Boolean>()
        withTimeout(5_000) {
            EveningDarkTicker.flow(setting, now = { at }, zone = { zone }).take(2).collect {
                seen += it
                if (seen.size == 1) setting.value = EveningDarkSetting(enabled = false)
            }
        }
        assertEquals(listOf(true, false), seen)
    }

    @Test
    fun initialUsesTheDefaults() {
        assertTrue(EveningDarkTicker.initial(Instant.parse("2026-11-16T20:00:00Z"), zone))
        assertFalse(EveningDarkTicker.initial(Instant.parse("2026-11-16T11:00:00Z"), zone))
    }
}
