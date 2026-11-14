package com.phasewake.app.ui.history

import com.phasewake.app.QuietApp
import com.phasewake.app.core.HealthSession
import com.phasewake.app.core.HistoryItem
import com.phasewake.app.core.HistoryKind
import com.phasewake.app.core.SleepStage
import com.phasewake.app.core.StageSpan
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** Nacht aus der Gesundheits-App in der Zone ihrer Aufzeichnung anzeigen (Audit F-3): Titel und Hypnogramm. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = QuietApp::class)
class NightZoneTest {
    private val tokyo = ZoneOffset.ofHours(9)

    /** Nacht in Tokio 23:00–07:00 (+09:00), also 14:00–22:00 UTC: Morgen ist dort schon der 2. Oktober. */
    private fun item(zone: ZoneOffset?): HistoryItem {
        val start = Instant.parse("2026-10-01T14:00:00Z")
        val end = Instant.parse("2026-10-01T22:00:00Z")
        val session = HealthSession(start, end, listOf(StageSpan(start, end, SleepStage.LIGHT)), timeZone = zone)
        return HistoryItem(HistoryKind.HEALTH, LocalDate.of(2026, 10, 1), session = session)
    }

    @Test
    fun titleUsesTheZoneOfTheRecording() {
        val item = item(tokyo)
        assertEquals(nightTitle(item, tokyo), nightTitle(item))
        assertNotEquals(nightTitle(item, ZoneOffset.UTC), nightTitle(item, tokyo))
    }

    @Test
    fun hypnogramGetsTheZoneOfTheRecording() {
        assertEquals(tokyo, nightView(item(tokyo), emptyList()).hypno?.zone)
        assertNull(nightView(item(null), emptyList()).hypno?.zone)
    }
}
