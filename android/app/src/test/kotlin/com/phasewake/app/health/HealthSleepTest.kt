package com.phasewake.app.health

import android.app.Application
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Metadata
import com.phasewake.app.core.NightRecord
import com.phasewake.app.core.SleepStage
import com.phasewake.app.core.StageSpan
import com.phasewake.app.core.WakeReason
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Lesen aus Health Connect ([HealthSleep], Audit F-02) mit [FakeHealthClient]: nur verfügbar und mit Zugriff, alle Seiten,
 * Phasen und Herkunft richtig übernommen, sortiert; ein Fehler beim Lesen gibt eine leere Liste statt eines Absturzes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HealthSleepTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val client = FakeHealthClient()
    private var status = HealthConnectClient.SDK_AVAILABLE
    private val health by lazy { HealthSleep(context, { status }, { client }) }
    private val t0 = Instant.parse("2026-11-01T22:00:00Z")

    private fun at(minutes: Long): Instant = t0.plus(Duration.ofMinutes(minutes))

    /**
     * Sitzung von [from] bis [to] Minuten nach [t0] mit [stages] (Art, von, bis). [origin]: schreibende App; Health Connect
     * setzt sie selbst, darum hier über Reflexion (ohne Angabe bleibt sie leer wie bei einer unbekannten Herkunft).
     */
    private fun session(from: Long, to: Long, origin: String? = null, vararg stages: Triple<Int, Long, Long>): SleepSessionRecord {
        val metadata = Metadata.manualEntry()
        if (origin != null) {
            Metadata::class.java.getDeclaredField("dataOrigin").apply { isAccessible = true }.set(metadata, DataOrigin(origin))
        }
        return SleepSessionRecord(
            startTime = at(from),
            startZoneOffset = null,
            endTime = at(to),
            endZoneOffset = null,
            metadata = metadata,
            stages = stages.map { (type, start, end) -> SleepSessionRecord.Stage(at(start), at(end), type) },
        )
    }

    private fun grant() {
        client.granted = health.permissions
    }

    @Test
    fun withoutHealthConnectNothingIsRead() = runBlocking {
        status = HealthConnectClient.SDK_UNAVAILABLE
        grant()
        client.pages = listOf(listOf(session(0, 60)))
        assertFalse(health.isAvailable)
        assertFalse(health.needsUpdate)
        assertFalse(health.isGranted())
        assertEquals(emptyList(), health.sessions(t0, at(600)))
        assertTrue(client.requests.isEmpty())
        status = HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED
        assertTrue(health.needsUpdate)
    }

    @Test
    fun withoutPermissionNothingIsRead() = runBlocking {
        client.pages = listOf(listOf(session(0, 60)))
        assertTrue(health.isAvailable)
        assertFalse(health.isGranted())
        assertEquals(emptyList(), health.sessions(t0, at(600)))
        assertEquals(emptyList(), health.stagesFor(night()))
        assertTrue(client.requests.isEmpty())
    }

    @Test
    fun readsAllPagesWithStagesAndSource() = runBlocking {
        grant()
        client.pages = listOf(
            listOf(
                session(
                    480, 540, HealthSource.SAMSUNG_HEALTH,
                    Triple(SleepSessionRecord.STAGE_TYPE_REM, 510, 540),
                    Triple(SleepSessionRecord.STAGE_TYPE_LIGHT, 480, 490),
                    Triple(SleepSessionRecord.STAGE_TYPE_DEEP, 490, 510),
                ),
            ),
            listOf(session(0, 420)),
        )
        val sessions = health.sessions(t0, at(600))
        // Beide Seiten gelesen, die zweite mit der Seiten-Kennung der ersten; Zeitraum wie verlangt.
        assertEquals(listOf(null, "1"), client.requests.map { it.pageToken })
        assertEquals(t0, client.requests.first().timeRangeFilter.startTime)
        assertEquals(at(600), client.requests.first().timeRangeFilter.endTime)
        // Älteste zuerst; ohne Herkunft `null`, Phasen nach Beginn sortiert.
        assertEquals(listOf(at(0), at(480)), sessions.map { it.start })
        assertNull(sessions[0].source)
        assertTrue(sessions[0].stages.isEmpty())
        assertEquals(HealthSource.SAMSUNG_HEALTH, sessions[1].source)
        assertEquals(at(540), sessions[1].end)
        assertEquals(
            listOf(StageSpan(at(480), at(490), SleepStage.LIGHT), StageSpan(at(490), at(510), SleepStage.DEEP), StageSpan(at(510), at(540), SleepStage.REM)),
            sessions[1].stages,
        )
    }

    @Test
    fun stageTypesAreMapped() = runBlocking {
        grant()
        val types = listOf(
            SleepSessionRecord.STAGE_TYPE_AWAKE to SleepStage.AWAKE,
            SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED to SleepStage.AWAKE,
            SleepSessionRecord.STAGE_TYPE_OUT_OF_BED to SleepStage.AWAKE,
            SleepSessionRecord.STAGE_TYPE_LIGHT to SleepStage.LIGHT,
            SleepSessionRecord.STAGE_TYPE_DEEP to SleepStage.DEEP,
            SleepSessionRecord.STAGE_TYPE_REM to SleepStage.REM,
            // «Schläft» ohne Phase und Unbekanntes: keine Phase, die Phase Wake bewerten könnte.
            SleepSessionRecord.STAGE_TYPE_SLEEPING to SleepStage.UNKNOWN,
            SleepSessionRecord.STAGE_TYPE_UNKNOWN to SleepStage.UNKNOWN,
        )
        val stages = types.mapIndexed { i, (type, _) -> Triple(type, i * 10L, i * 10L + 10) }.toTypedArray()
        client.pages = listOf(listOf(session(0, types.size * 10L, null, *stages)))
        assertEquals(types.map { it.second }, health.sessions(t0, at(600)).single().stages.map { it.stage })
    }

    @Test
    fun readErrorGivesEmptyList() = runBlocking {
        grant()
        client.failure = IllegalStateException("Health Connect weg")
        assertEquals(emptyList(), health.sessions(t0, at(600)))
        assertEquals(emptyList(), health.stagesFor(night()))
    }

    /** Nacht mit Fensterbeginn 06:30 und Aufwachen 06:50 (UTC) am Morgen nach [t0]. */
    private fun night() = NightRecord("a", at(510), at(540), at(530), WakeReason.DEADLINE, 0, emptyList())

    @Test
    fun stagesAroundTheNightAreReadFromAllSessions() = runBlocking {
        grant()
        client.pages = listOf(
            listOf(
                session(300, 540, null, Triple(SleepSessionRecord.STAGE_TYPE_DEEP, 300, 400), Triple(SleepSessionRecord.STAGE_TYPE_LIGHT, 400, 540)),
                session(0, 120, null, Triple(SleepSessionRecord.STAGE_TYPE_REM, 0, 120)),
            ),
        )
        val stages = health.stagesFor(night())
        // 14 Std. vor dem Fensterbeginn bis 2 Std. nach dem Aufwachen.
        val filter = client.requests.single().timeRangeFilter
        assertEquals(at(510).minus(Duration.ofHours(14)), filter.startTime)
        assertEquals(at(530).plus(Duration.ofHours(2)), filter.endTime)
        assertEquals(listOf(SleepStage.REM, SleepStage.DEEP, SleepStage.LIGHT), stages.map { it.stage })
    }

    @Test
    fun recentSleepCountsANightWithoutStages() = runBlocking {
        grant()
        val zone = ZoneId.systemDefault()
        val start = LocalDate.now(zone).minusDays(2).atTime(23, 0).atZone(zone).toInstant()
        val end = start.plus(Duration.ofHours(7))
        client.pages = listOf(
            listOf(SleepSessionRecord(startTime = start, startZoneOffset = null, endTime = end, endZoneOffset = null, metadata = Metadata.manualEntry())),
        )
        val summary = health.recentSleep(days = 7, clearedAt = null).single()
        assertEquals(start, summary.start)
        assertEquals(end, summary.end)
        // Ausgeblendet, was bis «Verlauf löschen» endete.
        assertTrue(health.recentSleep(days = 7, clearedAt = end).isEmpty())
    }
}
