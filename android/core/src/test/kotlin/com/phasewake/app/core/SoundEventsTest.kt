package com.phasewake.app.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SoundEventsTest {
    private val zone: ZoneId = ZoneId.of("Europe/Zurich")
    private fun at(text: String): Instant = LocalDateTime.parse(text).atZone(zone).toInstant()
    private val night = LocalDate.of(2026, 10, 27)

    /** Speist Pegel ein: [quiet] dB, ausser in den lauten Bereichen (ms, Ende exklusiv). */
    private fun run(totalMs: Long, loud: List<LongRange>, quiet: Double = -60.0, loudDb: Double = -30.0): List<Pair<Long, SoundEventDetector.Action>> {
        val detector = SoundEventDetector()
        val actions = mutableListOf<Pair<Long, SoundEventDetector.Action>>()
        var t = 0L
        while (t < totalMs) {
            val level = if (loud.any { t in it }) loudDb else quiet
            detector.onFrame(t, level).forEach { actions += t to it }
            t += SoundEventDetector.FRAME_MS
        }
        detector.finish(t).forEach { actions += t to it }
        return actions
    }

    @Test
    fun noDetectionDuringWarmup() {
        // In den ersten 10 s gibt es noch kein Grundrauschen.
        assertEquals(emptyList(), run(9_000, listOf(2_000L until 5_000L)).map { it.second })
    }

    @Test
    fun shortSoundIsIgnoredLongerOneIsAnEvent() {
        // 0,4 s laut: zu kurz. 3 s laut ab 40 s: ein Geräusch mit Ausschnitt ab 38 s.
        val actions = run(120_000, listOf(20_000L until 20_400L, 40_000L until 43_000L)).map { it.second }
        assertEquals(
            listOf(
                SoundEventDetector.Action.ClipStart(38_000, 40_000),
                // 2 s nach dem letzten lauten Block (endet bei 43 s) ist der Ausschnitt zu Ende.
                SoundEventDetector.Action.ClipEnd(40_000, 45_000),
                SoundEventDetector.Action.EventEnd(40_000, 3_000, -30.0),
            ),
            actions,
        )
    }

    @Test
    fun clipIsAtMostTenSecondsAndEventMergesWithin30Seconds() {
        // Laut 30–45 s, Pause 20 s, nochmals laut 65–70 s: ein Geräusch (Pause < 30 s), ein Ausschnitt.
        val actions = run(200_000, listOf(30_000L until 45_000L, 65_000L until 70_000L)).map { it.second }
        assertEquals(SoundEventDetector.Action.ClipStart(28_000, 30_000), actions[0])
        assertEquals(SoundEventDetector.Action.ClipEnd(30_000, 38_000), actions[1])
        assertEquals(SoundEventDetector.Action.EventEnd(30_000, 40_000, -30.0), actions[2])
        assertEquals(3, actions.size)
    }

    @Test
    fun nextEventNeedsThirtySecondsQuiet() {
        // Zweites Geräusch 31 s nach dem Ende des ersten: eigener Ausschnitt.
        val actions = run(200_000, listOf(30_000L until 31_000L, 62_000L until 63_000L)).map { it.second }
        assertEquals(2, actions.count { it is SoundEventDetector.Action.ClipStart })
        assertEquals(2, actions.count { it is SoundEventDetector.Action.EventEnd })
    }

    @Test
    fun finishClosesOpenEvent() {
        val actions = run(36_000, listOf(30_000L until 36_000L)).map { it.second }
        assertEquals(SoundEventDetector.Action.ClipStart(28_000, 30_000), actions[0])
        assertEquals(SoundEventDetector.Action.ClipEnd(30_000, 36_000), actions[1])
        assertEquals(SoundEventDetector.Action.EventEnd(30_000, 6_000, -30.0), actions[2])
    }

    @Test
    fun thresholdFollowsNoiseFloor() {
        val detector = SoundEventDetector()
        var t = 0L
        repeat(150) { detector.onFrame(t, -50.0); t += 100 }
        assertEquals(-38.0, detector.threshold!!, 1e-9)
        // Lauter als vorher, aber nicht 12 dB über dem Grundrauschen: kein Geräusch.
        val actions = (0 until 20).flatMap { detector.onFrame(t + it * 100L, -40.0) }
        assertEquals(emptyList(), actions)
    }

    @Test
    fun quietSoundsBelowMinus50DbAreIgnored() {
        // Sehr stilles Zimmer (−70 dB): −55 dB liegt 15 dB darüber, ist aber leiser als −50 dBFS.
        assertEquals(emptyList(), run(120_000, listOf(40_000L until 43_000L), quiet = -70.0, loudDb = -55.0))
        // −50 dB zählt (Grenze eingeschlossen).
        val actions = run(120_000, listOf(40_000L until 43_000L), quiet = -70.0, loudDb = -50.0).map { it.second }
        assertEquals(SoundEventDetector.Action.EventEnd(40_000, 3_000, -50.0), actions.last())
    }

    @Test
    fun autoStartFromBedtimeOfTheComingNight() {
        val target = at("2026-10-28T06:30")
        // Schlafziel 7,5 Std.: Bettzeit 23:00.
        assertEquals(false, SoundEvents.mayAutoStart(at("2026-10-27T22:59"), target, 450, zone))
        assertTrue(SoundEvents.mayAutoStart(at("2026-10-27T23:00"), target, 450, zone))
        assertTrue(SoundEvents.mayAutoStart(at("2026-10-28T02:00"), target, 450, zone))
        // Im Weckfenster nicht mehr automatisch.
        assertEquals(false, SoundEvents.mayAutoStart(target.minusSeconds(600), target, 450, zone, target.minusSeconds(1800)))
        // Ab 21 Uhr gilt mit Wecker die Bettzeit, nicht 21 Uhr.
        assertEquals(false, SoundEvents.mayAutoStart(at("2026-10-27T21:30"), target, 450, zone))
        // Nach der Zielzeit zählt der nächste Wecker (hier am Abend darauf noch vor der Bettzeit).
        assertEquals(false, SoundEvents.mayAutoStart(at("2026-10-28T07:00"), at("2026-10-29T06:30"), 450, zone))
    }

    @Test
    fun autoStartWithoutAlarmFrom21() {
        assertEquals(false, SoundEvents.mayAutoStart(at("2026-10-27T20:59"), null, 450, zone))
        assertTrue(SoundEvents.mayAutoStart(at("2026-10-27T21:00"), null, 450, zone))
        assertTrue(SoundEvents.mayAutoStart(at("2026-10-27T23:59"), null, 450, zone))
        assertEquals(false, SoundEvents.mayAutoStart(at("2026-10-28T00:30"), null, 450, zone))
        // Wecker erst in mehr als 24 Std.: wie ohne Wecker.
        assertTrue(SoundEvents.mayAutoStart(at("2026-10-27T21:30"), at("2026-10-29T06:30"), 450, zone))
        assertEquals(false, SoundEvents.mayAutoStart(at("2026-10-27T20:00"), at("2026-10-29T06:30"), 450, zone))
    }

    @Test
    fun nightsWithoutRecordNewestFirst() {
        fun clip(night: LocalDate, time: String, file: String) = SoundClip(night, at(time), SoundType.SNORE, 1_000, -30, file)
        val clips = listOf(
            clip(night, "2026-10-27T23:00", "a.m4a"),
            clip(night, "2026-10-28T03:00", "b.m4a"),
            clip(night.minusDays(1), "2026-10-27T01:00", "c.m4a"),
            clip(night.minusDays(2), "2026-10-26T02:00", "d.m4a"),
        )
        assertEquals(
            listOf(night to at("2026-10-28T03:00"), night.minusDays(2) to at("2026-10-26T02:00")),
            SoundEvents.nightsWithoutRecord(clips, listOf(night.minusDays(1))),
        )
        assertEquals(emptyList(), SoundEvents.nightsWithoutRecord(clips, listOf(night, night.minusDays(1), night.minusDays(2))))
    }

    @Test
    fun labelsMapToTypes() {
        assertEquals(SoundType.SNORE, SoundEvents.typeOf("Snoring"))
        assertEquals(SoundType.SNORE, SoundEvents.typeOf("snoring"))
        assertEquals(SoundType.TALK, SoundEvents.typeOf("Speech"))
        assertEquals(SoundType.TALK, SoundEvents.typeOf("whispering"))
        assertEquals(SoundType.TALK, SoundEvents.typeOf("Conversation"))
        assertEquals(SoundType.TALK, SoundEvents.typeOf("shout"))
        assertEquals(SoundType.OTHER, SoundEvents.typeOf("Dog"))
        assertEquals(SoundType.SNORE, SoundEvents.classify(mapOf("Snoring" to 0.6, "Speech" to 0.3, "Silence" to 0.1)))
        // Nur Ziel-Bezeichnungen zählen: Atmen schlägt Schnarchen nicht.
        assertEquals(SoundType.SNORE, SoundEvents.classify(mapOf("Snoring" to 0.2, "Breathing" to 0.5)))
        assertEquals(SoundType.TALK, SoundEvents.classify(mapOf("Snoring" to 0.2, "Whispering" to 0.1, "Speech" to 0.3)))
        // Unter dem Mindestwert: anderes.
        assertEquals(SoundType.OTHER, SoundEvents.classify(mapOf("Snoring" to 0.1, "Speech" to 0.149, "Silence" to 0.9)))
        assertEquals(SoundType.SNORE, SoundEvents.classify(mapOf("Snoring" to 0.15, "Speech" to 0.15)))
        assertEquals(SoundType.OTHER, SoundEvents.classify(emptyMap()))
    }

    @Test
    fun scoresAreAveragedOverFrames() {
        // Fehlt eine Bezeichnung in einem Abschnitt, zählt sie dort als 0.
        val frames = listOf(mapOf("Snoring" to 0.6, "Speech" to 0.1), mapOf("Speech" to 0.3), mapOf("Snoring" to 0.0))
        val average = SoundEvents.averageScores(frames)
        assertEquals(0.2, average.getValue("Snoring"), 1e-9)
        assertEquals(0.4 / 3, average.getValue("Speech"), 1e-9)
        assertEquals(SoundType.SNORE, SoundEvents.classify(average))
        // Ein kurzer Ausreisser in einem von zehn Abschnitten reicht nicht.
        val spike = listOf(mapOf("Speech" to 0.9)) + List(9) { mapOf("Silence" to 0.9) }
        assertEquals(SoundType.OTHER, SoundEvents.classify(SoundEvents.averageScores(spike)))
        assertEquals(emptyMap(), SoundEvents.averageScores(emptyList()))
    }

    @Test
    fun levelOfSilenceAndFullScale() {
        assertEquals(SoundEvents.SILENCE_DB, SoundEvents.levelDb(ShortArray(160)))
        val square = ShortArray(160) { if (it % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE }
        assertEquals(0.0, SoundEvents.levelDb(square), 0.01)
    }

    @Test
    fun recordingEndsThirtyMinutesAfterTargetOrAfterTwelveHours() {
        val start = at("2026-10-27T22:00")
        assertEquals(at("2026-10-28T07:00"), SoundEvents.recordingEnd(start, at("2026-10-28T06:30")))
        assertEquals(at("2026-10-28T10:00"), SoundEvents.recordingEnd(start, null))
        // Wecker mehr als 24 Std. entfernt: wie ohne Wecker.
        assertEquals(at("2026-10-28T10:00"), SoundEvents.recordingEnd(start, at("2026-10-29T06:30")))
        // Früh eingeschaltet (18:00), Wecker 07:00 (13 Std.): bis Zielzeit + 30 Min, nicht schon um 06:00.
        assertEquals(at("2026-10-28T07:30"), SoundEvents.recordingEnd(at("2026-10-27T18:00"), at("2026-10-28T07:00")))
        // Genau 24 Std. zählt noch.
        assertEquals(at("2026-10-28T22:30"), SoundEvents.recordingEnd(start, at("2026-10-28T22:00")))
    }

    private fun clip(time: String, type: SoundType, duration: Long, peak: Int, file: String = "$time.m4a".replace(":", "-")) =
        SoundClip(SoundEvents.nightDate(at(time), zone), at(time), type, duration, peak, file)

    @Test
    fun summaryCountsSnoreMinutesAndClips() {
        val clips = listOf(
            clip("2026-10-27T23:10", SoundType.SNORE, 6 * 60_000L, -30),
            clip("2026-10-28T01:00", SoundType.SNORE, 5 * 60_000L + 40_000, -32),
            clip("2026-10-28T02:00", SoundType.TALK, 3_000, -25),
            clip("2026-10-28T03:00", SoundType.OTHER, 2_000, -40),
        )
        val summary = SoundEvents.summary(clips)
        assertEquals(12, summary.snoreMinutes)
        assertEquals(2, summary.snoreClips)
        assertEquals(1, summary.talkClips)
        assertEquals(4, summary.clips)
        assertEquals(1, SoundEvents.summary(listOf(clip("2026-10-27T23:10", SoundType.SNORE, 5_000, -30))).snoreMinutes)
        assertEquals(0, SoundEvents.summary(emptyList()).snoreMinutes)
        assertTrue(clips.all { it.night == night })
    }

    @Test
    fun capKeepsLoudestClipsOfTheNight() {
        val existing = listOf(
            clip("2026-10-27T23:00", SoundType.OTHER, 1_000, -40, "a.m4a"),
            clip("2026-10-27T23:30", SoundType.OTHER, 1_000, -20, "b.m4a"),
            // Andere Nacht: zählt nicht mit.
            clip("2026-10-26T23:30", SoundType.OTHER, 1_000, -50, "x.m4a"),
        )
        val louder = SoundEvents.add(existing, clip("2026-10-28T01:00", SoundType.SNORE, 1_000, -30, "c.m4a"), max = 2)
        assertEquals(listOf("a.m4a"), louder.dropped.map { it.file })
        assertEquals(listOf("x.m4a", "b.m4a", "c.m4a"), louder.kept.map { it.file })
        val quieter = SoundEvents.add(existing, clip("2026-10-28T01:00", SoundType.SNORE, 1_000, -45, "d.m4a"), max = 2)
        assertEquals(listOf("d.m4a"), quieter.dropped.map { it.file })
        assertEquals(0, SoundEvents.add(existing, clip("2026-10-28T01:00", SoundType.SNORE, 1_000, -45, "e.m4a")).dropped.size)
    }

    @Test
    fun expireAfterSevenDays() {
        // 7 × 24 h zurück (über die Zeitumstellung am 25. 10. hinweg): 21. 10., 23:15 Sommerzeit.
        val old = clip("2026-10-20T23:00", SoundType.SNORE, 1_000, -30, "old.m4a")
        val fresh = clip("2026-10-21T23:30", SoundType.SNORE, 1_000, -30, "fresh.m4a")
        val (kept, expired) = SoundEvents.expire(listOf(old, fresh), at("2026-10-28T22:15"))
        assertEquals(listOf(fresh), kept)
        assertEquals(listOf(old), expired)
    }

    @Test
    fun storageRoundTripSkipsBrokenLinesAndPaths() {
        val clips = listOf(
            clip("2026-10-28T01:00", SoundType.TALK, 4_200, -21, "b.m4a"),
            clip("2026-10-27T23:00", SoundType.SNORE, 61_000, -33, "a.m4a"),
        )
        val text = SoundEvents.encode(clips)
        assertEquals("2026-10-27|${at("2026-10-27T23:00").toEpochMilli()}|SNORE|61000|-33|a.m4a", text.lines().first())
        assertEquals(clips.sortedBy { it.at }, SoundEvents.decode(text))
        val broken = text + "\nkaputt\n2026-10-27|1|SNORE|1|-3|../etwas\n2026-10-27|2|LAUT|1|-3|z.m4a"
        val read = SoundEvents.decode(broken)
        assertEquals(3, read.size)
        assertEquals(SoundType.OTHER, read.first { it.file == "z.m4a" }.type)
        assertEquals(emptyList(), SoundEvents.decode(null))
        assertTrue(!SoundEvents.isSafeFile("../x") && SoundEvents.isSafeFile("a.m4a"))
    }

    @Test
    fun forNightFilters() {
        val clips = listOf(clip("2026-10-28T01:00", SoundType.TALK, 1, -1, "b.m4a"), clip("2026-10-26T23:00", SoundType.SNORE, 1, -1, "a.m4a"))
        assertEquals(listOf("b.m4a"), SoundEvents.forNight(clips, night).map { it.file })
        assertNull(SoundEvents.forNight(clips, night.plusDays(5)).firstOrNull())
    }
}
