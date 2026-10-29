package com.phasewake.app.shared

import android.app.Application
import android.net.Uri
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.HolidayList
import com.phasewake.app.core.NightRecord
import com.phasewake.app.core.WakeReason
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test

/**
 * Ohne Wear-Dienste (wie auf einem Handy ohne gekoppelte Uhr) darf kein Aufruf von [WearSync] abstürzen:
 * Fehler werden geschluckt, die App läuft allein weiter. Ob die Dienste sofort mit Fehler antworten oder
 * gar nicht, ist auf der Testplattform offen; darum hat jeder Aufruf eine Frist.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WearSyncOfflineTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val sync = WearSync(app)

    private fun tolerant(block: suspend () -> Unit) = runBlocking { withTimeoutOrNull(TIMEOUT_MS) { block() } }

    @Test
    fun publishingWithoutWatchDoesNotThrow() {
        tolerant { sync.publishAlarms(AlarmSnapshot(listOf(AlarmSpec(id = "a", hour = 7, minute = 0)), 1L)) }
        tolerant {
            sync.publishNight(NightRecord("a", Instant.ofEpochMilli(1_000), Instant.ofEpochMilli(2_000), Instant.ofEpochMilli(1_500), WakeReason.DEADLINE, 0, emptyList()))
        }
        tolerant { sync.publishWatchReady(true, 50) }
        tolerant { sync.publishHolidays(HolidayList("CH", emptySet()), 1L) }
        tolerant { sync.clearHistoryEverywhere() }
        tolerant { sync.deleteNights() }
        tolerant { sync.delete(Uri.parse("wear://*/night")) }
        tolerant { sync.message(DataLayer.CAPABILITY_PHONE, DataLayer.PATH_STOP) }
    }

    @Test
    fun readingWithoutWatchDoesNotThrow() {
        tolerant { sync.fetchAlarms() }
        tolerant { sync.watchReady() }
        tolerant { sync.watchBattery() }
        tolerant { sync.watchSeenAt() }
        tolerant { sync.fetchHolidays() }
        tolerant { sync.fetchNights() }
        tolerant { sync.nodesWithout(DataLayer.CAPABILITY_WATCH) }
        tolerant { sync.isReachable(DataLayer.CAPABILITY_WATCH) }
    }

    private companion object {
        const val TIMEOUT_MS = 3_000L
    }
}
